/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.player;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.PlayerTickMovementEvent;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.PreInit;
import meteordevelopment.meteorclient.utils.entity.Target;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.input.Input;
import net.minecraft.entity.Entity;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Rotation Coordinator — 每 tick 仅允许一个权威旋转进入 server 语义。
 *
 * 核心原则:
 * 1. 一拍只允许一个 authoritative server rotation
 * 2. 不再从 Rotations 主动补发额外 LookAndOnGround 包
 * 3. rotation hold 只保留逻辑状态 (serverYaw/serverPitch), 不再注入后续 packet
 * 4. 会触发动作的 rotation 在 PlayerTickMovementEvent 时段预应用到 player yaw/pitch
 * 5. 如果请求来得太晚 (movement 阶段已过), 自动延到下一 tick
 * 6. 同 tick 多个请求只做仲裁 (priority 越大越优先), loser 延期
 *
 * 生命周期:
 *   TickEvent.Pre                → 清理上一拍、重置窗口
 *   模块调用 rotate()            → 加入 pending 队列, resolver 立即求解一次
 *   PlayerTickMovementEvent      → 仲裁 winner, resolver 重新求解 (pre-physics 精确角), 预应用 yaw/pitch
 *   SendMovementPacketsEvent.Pre → 应用角度到 player, 执行 callback (Pre-Flying: 交互包在 Flying 前发出)
 *   sendMovementPackets          → 带正确角度的 vanilla movement packet 发出
 *   SendMovementPacketsEvent.Post → 恢复视角, 清理
 *
 * 旋转求解始终在 pre-physics 位置进行 — 与原版客户端一致 (旋转由鼠标在物理前确定),
 * 也与 GrimAC 的检查位置 (lastClaimedPosition = 上 tick Flying 位置) 自洽。
 */
public class Rotations {
    // ==================== 公共状态 (只读) ====================
    /** 当前服务端视角 (逻辑记账, 不再用于注入 packet) */
    public static float serverYaw;
    public static float serverPitch;
    /** 距离上一次有效旋转过了多少 tick */
    public static int rotationTimer;
    /** 是否有模块正在控制旋转 (本 tick 有 active 或 hold 尚未过期) */
    public static boolean rotating = false;

    // ==================== 内部状态 ====================
    private static final List<RotationRequest> pending = new ArrayList<>();
    private static RotationRequest active;  // 本 tick 仲裁出来的 winner
    private static boolean movementPhasePassed; // PlayerTickMovementEvent 已触发
    private static boolean appliedThisTick;     // 本 tick 是否预应用了旋转
    private static float savedYaw, savedPitch;  // 预应用前保存的原始角度

    // hold 逻辑状态 (只记账, 不注入)
    private static int holdTimer;
    private static final int HOLD_TICKS = 9;

    // ==================== MovementFix 状态 ====================
    /** 是否需要在 input.tick() TAIL 进行输入重映射 */
    private static boolean needsMoveFix;
    /** 预应用前保存的原始视觉 yaw (用于计算输入重映射) */
    private static float originalVisualYaw;

    private Rotations() {
    }

    @PreInit
    public static void init() {
        MeteorClient.EVENT_BUS.subscribe(Rotations.class);
    }

    // ==================== 公共 API ====================

    // ── 固定角度 API ──

    /**
     * 请求服务端语义旋转 (固定角度)。
     * priority 越大越优先。movement 阶段已过则自动 defer 到下一 tick。
     */
    public static void rotate(double yaw, double pitch, int priority, Runnable callback) {
        if (mc.player == null) return;
        float y = (float) yaw, p = (float) pitch;
        pending.add(new RotationRequest(y, p, priority, callback, movementPhasePassed,
            () -> new AimSolution(y, p)));
    }

    /** 兼容旧 API — clientSide 参数已废弃 */
    public static void rotate(double yaw, double pitch, int priority, boolean clientSide, Runnable callback) {
        rotate(yaw, pitch, priority, callback);
    }

    public static void rotate(double yaw, double pitch, Runnable callback) {
        rotate(yaw, pitch, 0, callback);
    }

    public static void rotate(double yaw, double pitch, int priority) {
        rotate(yaw, pitch, priority, null);
    }

    public static void rotate(double yaw, double pitch) {
        rotate(yaw, pitch, 0, null);
    }

    /**
     * 固定角度 + 确保本 tick 参与仲裁 (不 defer)。供 Printer 等 tick 早期提交者使用。
     */
    public static void requestPreMovement(float yaw, float pitch, int priority, Runnable callback) {
        if (mc.player == null) return;
        float y = yaw, p = pitch;
        pending.add(new RotationRequest(y, p, priority, callback, false,
            () -> new AimSolution(y, p)));
    }

    // ── 目标坐标 API ──

    /**
     * 面向固定世界坐标的旋转。
     * 在注册时从当前 (pre-physics) 位置计算角度 — 与原版客户端一致:
     * 原版的旋转在 pre-physics 时由鼠标确定, 不因本 tick 物理移动而改变。
     * GrimAC 从旧位置 (lastClaimedPosition) 检查旋转, 因此 pre-physics 角度与之自洽。
     */
    public static void rotateToward(Vec3d target, int priority, Runnable callback) {
        if (mc.player == null) return;
        rotateWith(() -> new AimSolution(
            (float) getYaw(target), (float) getPitch(target)
        ), priority, callback);
    }

    /** 固定坐标 + 确保本 tick 参与仲裁。供 Printer 等模块使用。 */
    public static void requestPreMovementToward(Vec3d target, int priority, Runnable callback) {
        if (mc.player == null) return;
        requestPreMovementWith(() -> new AimSolution(
            (float) getYaw(target), (float) getPitch(target)
        ), priority, callback);
    }

    // ── AimResolver API ──

    /**
     * 提交 AimResolver 驱动的旋转请求。
     * <p>
     * resolver 在两个时刻被调用:
     * <ul>
     *   <li>注册时 — 立即求解一次, 用于仲裁排序 (yaw/pitch 初始值)</li>
     *   <li>PlayerTickMovementEvent — winner 确定后重新求解, 获得 pre-physics 精确角度</li>
     * </ul>
     * <p>
     * 所有求解均在 pre-physics 位置进行 — 这与原版客户端一致 (旋转在物理前确定),
     * 也与 GrimAC 的 Reach/RotationPlace 检查位置 (lastClaimedPosition = 旧位置) 自洽。
     * <p>
     * 对于追踪移动实体 (如 KillAura), 建议 resolver 读取实体最新客户端插值位置。
     */
    public static void rotateWith(AimResolver resolver, int priority, Runnable callback) {
        if (mc.player == null) return;
        AimSolution aim = resolver.resolve();
        pending.add(new RotationRequest(aim.yaw(), aim.pitch(), priority, callback,
            movementPhasePassed, resolver));
    }

    /** AimResolver + 确保本 tick 参与仲裁 (不 defer)。 */
    public static void requestPreMovementWith(AimResolver resolver, int priority, Runnable callback) {
        if (mc.player == null) return;
        AimSolution aim = resolver.resolve();
        pending.add(new RotationRequest(aim.yaw(), aim.pitch(), priority, callback,
            false, resolver));
    }

    /**
     * 获取本 tick 是否有活跃的旋转请求被应用。
     */
    public static boolean hasActiveRotation() {
        return active != null;
    }

    /**
     * 获取本 tick 的 active rotation 的 callback (供高级模块在 Post 自行处理)。
     * 返回 null 表示没有活跃旋转或无 callback。
     */
    public static Runnable getActiveCallback() {
        return active != null ? active.callback : null;
    }

    // ==================== 生命周期事件 ====================

    @EventHandler(priority = EventPriority.HIGHEST)
    private static void onTickPre(TickEvent.Pre event) {
        // 每 tick 开始: 重置窗口
        movementPhasePassed = false;
        active = null;
        appliedThisTick = false;
        rotationTimer++;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private static void onPlayerTickMovement(PlayerTickMovementEvent event) {
        if (mc.player == null || mc.cameraEntity != mc.player) return;

        movementPhasePassed = true;

        // 仲裁: 选出 priority 最大的 winner
        RotationRequest winner = null;
        for (RotationRequest req : pending) {
            if (req.deferred) continue;
            if (winner == null || req.priority > winner.priority) {
                winner = req;
            }
        }

        if (winner != null) {
            active = winner;
            rotating = true;
            holdTimer = 0;

            // 求解: winner 确定后从当前 (pre-physics) 位置重新计算精确角度
            // 这是最终角度 — 与原版一致 (旋转在物理前由鼠标确定, 不因物理移动而改变)
            AimSolution aim = active.resolver.resolve();
            active.yaw = aim.yaw();
            active.pitch = aim.pitch();

            // 更新逻辑记账
            serverYaw = active.yaw;
            serverPitch = active.pitch;
            rotationTimer = 0;

            // 鞘翅守护: 追踪角不进入飞行物理, 仅在 send 时注入
            if (mc.player.isGliding()) {
                // PacketOnly — 不改本地 yaw/pitch, 不做 MovementFix
                // 鞘翅飞行方向由玩家真实视角决定, 不受追踪影响
                // 角度注入延迟到 onSendMovementPacketsPre
                needsMoveFix = false;
            } else {
                // 正常模式 — 预应用 + MovementFix
                savedYaw = mc.player.getYaw();
                savedPitch = mc.player.getPitch();
                originalVisualYaw = savedYaw;
                needsMoveFix = true;

                mc.player.setYaw(active.yaw);
                mc.player.setPitch(active.pitch);
                appliedThisTick = true;
            }

            // 所有非 winner 的请求标记为 deferred, 留到下一 tick
            for (RotationRequest req : pending) {
                if (req != winner) req.deferred = true;
            }
        } else {
            // 没有新请求: hold 逻辑 (只维持状态标记, 不注入 packet)
            needsMoveFix = false;
            if (rotating) {
                holdTimer++;
                if (holdTimer > HOLD_TICKS) {
                    rotating = false;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private static void onSendMovementPacketsPre(SendMovementPacketsEvent.Pre event) {
        if (active == null || mc.player == null) return;

        // 使用 onPlayerTickMovement 中已求解的角度 (pre-physics 位置)
        // 不再从 post-physics 位置重算 — 原版旋转在物理前确定,
        // GrimAC 也从旧位置 (lastClaimedPosition) 检查, 与 pre-physics 角度自洽。

        // 鞘翅延迟注入: onPlayerTickMovement 未预应用, 此刻才第一次触碰 player yaw/pitch
        if (!appliedThisTick) {
            savedYaw = mc.player.getYaw();
            savedPitch = mc.player.getPitch();
            appliedThisTick = true;
        }

        mc.player.setYaw(active.yaw);
        mc.player.setPitch(active.pitch);
        serverYaw = active.yaw;
        serverPitch = active.pitch;

        // 执行 winner 的 callback (Pre-Flying: 旋转已应用, movement packet 尚未发出)
        // 交互包 (INTERACT_ENTITY, HAND_SWING 等) 必须在 Flying 包之前发出,
        // 否则 GrimAC PacketOrderO 会将 Flying 后的非 async 包标记为违规。
        // GrimAC Reach 使用"排队 + Flying 到达时检查"机制:
        //   收到 INTERACT_ENTITY → 入队; 收到 Flying → 用 Flying 中的旋转做精确检测。
        // 因此 INTERACT_ENTITY 在 Flying 之前发出不影响 Reach 判定。
        if (active.callback != null) {
            active.callback.run();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    private static void onSendMovementPacketsPost(SendMovementPacketsEvent.Post event) {
        if (mc.player == null || mc.cameraEntity != mc.player) return;

        // 恢复玩家原始视角 (silent rotation: 视觉上不强制转头)
        if (appliedThisTick) {
            mc.player.setYaw(savedYaw);
            mc.player.setPitch(savedPitch);
            appliedThisTick = false;
        }

        // 清理本 tick 已处理的请求, 保留 deferred 到下一 tick
        pending.removeIf(req -> !req.deferred);
        // 重置 deferred 标记, 让它们在下一 tick 重新参与仲裁
        for (RotationRequest req : pending) {
            req.deferred = false;
        }

        active = null;
    }

    // ==================== MovementFix — 输入重映射 ====================

    /**
     * 是否需要在 input.tick() TAIL 做输入重映射。
     * 由 KeyboardInputMixin 在 input.tick() TAIL 调用。
     */
    public static boolean needsMoveFix() {
        return needsMoveFix;
    }

    /**
     * 在 input.tick() TAIL 中调用: 将 WASD 输入重映射到 targetYaw 下仍能保持原始移动方向的 8 向合法按键。
     *
     * 原理: 玩家视觉 yaw=originalVisualYaw 时, 按 W 向前走;
     * 我们把 yaw 改成 target 后, 需要换一组按键让 movement 物理仍走出相同的世界方向。
     *
     * 使用"当前帧舒适评分"方式: 枚举 8 个合法 WASD 候选,
     * 按 (方向误差 + 输入结构保持 + sprint 保护) 打分, 选最优。
     * 不依赖上一帧状态, 不产生连续浮点中间量。
     *
     * @param input 当前 player.input, 此时 input.tick() 刚执行完, 值是最新的键盘状态
     */
    public static void applyMoveFix(Input input) {
        if (!needsMoveFix || mc.player == null || active == null) return;
        needsMoveFix = false;

        PlayerInput pi = input.playerInput;
        float forward = pi.forward() == pi.backward() ? 0 : (pi.forward() ? 1.0f : -1.0f);
        float sideways = pi.left() == pi.right() ? 0 : (pi.left() ? 1.0f : -1.0f);

        // 没有移动输入, 不需要重映射
        if (forward == 0 && sideways == 0) return;

        float targetYaw = active.yaw;
        float yawDelta = MathHelper.wrapDegrees(targetYaw - originalVisualYaw);

        // 死区: 小角差不修输入, 避免微小旋转引发不必要的按键跳变
        if (Math.abs(yawDelta) < 7.0f) return;

        // Step 1: 计算 rawInput + renderYaw 下的"目标世界方向向量"
        // Minecraft movement: worldX = sideways*cos(yaw) - forward*sin(yaw)
        //                     worldZ = forward*cos(yaw)  + sideways*sin(yaw)
        float renderRad = (float) Math.toRadians(originalVisualYaw);
        float renderCos = MathHelper.cos(renderRad);
        float renderSin = MathHelper.sin(renderRad);
        float desiredX = sideways * renderCos - forward * renderSin;
        float desiredZ = forward * renderCos + sideways * renderSin;

        float desiredLen = MathHelper.sqrt(desiredX * desiredX + desiredZ * desiredZ);
        if (desiredLen < 1.0e-6f) return;
        desiredX /= desiredLen;
        desiredZ /= desiredLen;

        // Step 2: 枚举 8 个合法 WASD 候选, 在 packetYaw 下计算世界方向并打分
        float packetRad = (float) Math.toRadians(targetYaw);
        float packetCos = MathHelper.cos(packetRad);
        float packetSin = MathHelper.sin(packetRad);

        // 候选: [forward, sideways] — 顺序对应 W, W+A, A, S+A, S, S+D, D, W+D
        final float[][] CANDIDATES = {
            { 1,  0}, { 1,  1}, { 0,  1}, {-1,  1},
            {-1,  0}, {-1, -1}, { 0, -1}, { 1, -1}
        };

        float bestScore = Float.MAX_VALUE;
        int bestIdx = -1;
        boolean sprinting = mc.player.isSprinting();
        boolean rawHasForward = forward > 0;
        boolean rawHasBackward = forward < 0;

        for (int i = 0; i < CANDIDATES.length; i++) {
            float cf = CANDIDATES[i][0];
            float cs = CANDIDATES[i][1];

            // 归一化 (对角线输入长度为 sqrt(2))
            float len = MathHelper.sqrt(cf * cf + cs * cs);
            float nf = cf / len;
            float ns = cs / len;

            // 该候选在 packetYaw 下的世界方向
            float wx = ns * packetCos - nf * packetSin;
            float wz = nf * packetCos + ns * packetSin;

            // 评分 A: 世界方向角度误差 (0 ~ PI)
            float dot = desiredX * wx + desiredZ * wz;
            dot = MathHelper.clamp(dot, -1.0f, 1.0f);
            float dirError = (float) Math.acos(dot);

            // 评分 B: 输入结构保持 — 尽量不把前进变后退
            float structurePenalty = 0;
            if (rawHasForward && cf < 0) structurePenalty += 0.5f;
            if (rawHasBackward && cf > 0) structurePenalty += 0.3f;

            // 评分 C: sprint 保护 — 后退会打断 sprint
            float sprintPenalty = (sprinting && cf < 0) ? 1.0f : 0;

            float score = dirError * 2.0f + structurePenalty + sprintPenalty;
            if (score < bestScore) {
                bestScore = score;
                bestIdx = i;
            }
        }

        if (bestIdx < 0) return;

        // 将最优候选映射到 WASD 标志
        boolean w, s, a, d;
        switch (bestIdx) {
            case 0:   w=true;  s=false; a=false; d=false; break; // W
            case 1:   w=true;  s=false; a=true;  d=false; break; // W+A
            case 2:   w=false; s=false; a=true;  d=false; break; // A
            case 3:   w=false; s=true;  a=true;  d=false; break; // S+A
            case 4:   w=false; s=true;  a=false; d=false; break; // S
            case 5:   w=false; s=true;  a=false; d=true;  break; // S+D
            case 6:   w=false; s=false; a=false; d=true;  break; // D
            case 7:   w=true;  s=false; a=false; d=true;  break; // W+D
            default:  return;
        }

        // 更新 playerInput (保留 jump/sneak/sprint)
        PlayerInput oldPi = input.playerInput;
        input.playerInput = new PlayerInput(w, s, a, d, oldPi.jump(), oldPi.sneak(), oldPi.sprint());
        // 注意: movementVector 的同步由 KeyboardInputMixin 在调用 applyMoveFix 之后完成
    }

    // ==================== 工具方法 (角度计算) ====================

    public static double getYaw(Entity entity) {
        return mc.player.getYaw() + MathHelper.wrapDegrees((float) Math.toDegrees(Math.atan2(entity.getZ() - mc.player.getZ(), entity.getX() - mc.player.getX())) - 90f - mc.player.getYaw());
    }

    public static double getYaw(Vec3d pos) {
        return mc.player.getYaw() + MathHelper.wrapDegrees((float) Math.toDegrees(Math.atan2(pos.getZ() - mc.player.getZ(), pos.getX() - mc.player.getX())) - 90f - mc.player.getYaw());
    }

    public static double getPitch(Vec3d pos) {
        double diffX = pos.getX() - mc.player.getX();
        double diffY = pos.getY() - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
        double diffZ = pos.getZ() - mc.player.getZ();

        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);

        return mc.player.getPitch() + MathHelper.wrapDegrees((float) -Math.toDegrees(Math.atan2(diffY, diffXZ)) - mc.player.getPitch());
    }

    public static double getPitch(Entity entity, Target target) {
        double y;
        if (target == Target.Head) y = entity.getEyeY();
        else if (target == Target.Body) y = entity.getY() + entity.getHeight() / 2;
        else y = entity.getY();

        double diffX = entity.getX() - mc.player.getX();
        double diffY = y - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
        double diffZ = entity.getZ() - mc.player.getZ();

        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);

        return mc.player.getPitch() + MathHelper.wrapDegrees((float) -Math.toDegrees(Math.atan2(diffY, diffXZ)) - mc.player.getPitch());
    }

    public static double getPitch(Entity entity) {
        return getPitch(entity, Target.Body);
    }

    public static double getYaw(BlockPos pos) {
        return mc.player.getYaw() + MathHelper.wrapDegrees((float) Math.toDegrees(Math.atan2(pos.getZ() + 0.5 - mc.player.getZ(), pos.getX() + 0.5 - mc.player.getX())) - 90f - mc.player.getYaw());
    }

    public static double getPitch(BlockPos pos) {
        double diffX = pos.getX() + 0.5 - mc.player.getX();
        double diffY = pos.getY() + 0.5 - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
        double diffZ = pos.getZ() + 0.5 - mc.player.getZ();

        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);

        return mc.player.getPitch() + MathHelper.wrapDegrees((float) -Math.toDegrees(Math.atan2(diffY, diffXZ)) - mc.player.getPitch());
    }

    /**
     * 手动更新 serverYaw/serverPitch (供外部模块强制设置逻辑状态)。
     */
    public static void setCamRotation(double yaw, double pitch) {
        serverYaw = (float) yaw;
        serverPitch = (float) pitch;
        rotationTimer = 0;
    }

    // ==================== 瞄准求解 ====================

    /** 瞄准解 — 包含解算出的 yaw/pitch */
    public record AimSolution(float yaw, float pitch) {}

    /**
     * 瞄准求解器 — 从当前 (pre-physics) 状态计算旋转角度。
     * <p>
     * Rotations 在每 tick 的 PlayerTickMovementEvent 时调用 resolver:
     * 此时位置为 pre-physics (≈ 上 tick Flying 的位置), 与 GrimAC 的检查位置一致。
     * <p>
     * 对于固定角度 ({@link #rotate(double, double, int, Runnable)}): 返回常量。<br>
     * 对于固定坐标 ({@link #rotateToward(Vec3d, int, Runnable)}): 从 pre-physics 位置计算。<br>
     * 对于实体追踪 ({@link #rotateWith(AimResolver, int, Runnable)}): 读取实体当前位置计算。
     */
    @FunctionalInterface
    public interface AimResolver {
        AimSolution resolve();
    }

    // ==================== 内部数据结构 ====================

    private static class RotationRequest {
        float yaw, pitch;
        final int priority;
        final Runnable callback;
        final AimResolver resolver;
        boolean deferred;

        RotationRequest(float yaw, float pitch, int priority, Runnable callback, boolean deferred, AimResolver resolver) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.priority = priority;
            this.callback = callback;
            this.deferred = deferred;
            this.resolver = resolver;
        }
    }
}
