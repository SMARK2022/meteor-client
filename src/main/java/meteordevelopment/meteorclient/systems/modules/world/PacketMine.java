/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.meteor.MouseScrollEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.AutoTool;
import meteordevelopment.meteorclient.systems.modules.player.SpeedMine;
import meteordevelopment.meteorclient.systems.modules.render.BreakIndicators;
import meteordevelopment.meteorclient.utils.misc.Pool;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.meteorclient.mixininterface.IClientPlayerInteractionManager;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 发包挖掘模块 —— 合规版
 *
 * <p>核心语义：一次 START → 本地按 tick 积分 progress → 到点后单次 STOP。
 * <ul>
 *   <li>progress 仅在 START 发出后开始累计，不在 START 前预算</li>
 *   <li>START / STOP / ABORT 是唯一的 dig 里程碑包，中间不发 dig 包</li>
 *   <li>旋转仅在 START / STOP / ABORT 时做一次性 silent rotation</li>
 *   <li>心跳挥手（HandSwing）模拟玩家持续按住左键的动画信号</li>
 *   <li>本地镜像 Grim blockDelayBalance，自适应调度块间节奏（允许 burst 但自动恢复）</li>
 *   <li>动态 face 解析：挖掘过程中玩家移位后，自动切换到当前仍可合法击中的面</li>
 * </ul>
 */
public class PacketMine extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgGrim    = settings.createGroup("Grim 合规");
    private final SettingGroup sgRender  = settings.createGroup("渲染");

    // ── 通用 ──

    private final Setting<Boolean> rotateOnStart = sgGeneral.add(new BoolSetting.Builder()
        .name("旋转-开始挖掘")
        .description("发送 START_DESTROY 时静默旋转朝向目标方块")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotateOnStop = sgGeneral.add(new BoolSetting.Builder()
        .name("旋转-完成挖掘")
        .description("发送 STOP_DESTROY 时静默旋转朝向目标方块")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotateOnAbort = sgGeneral.add(new BoolSetting.Builder()
        .name("旋转-中止挖掘")
        .description("中止挖掘时静默旋转朝向目标方块")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("自动切换工具")
        .description("挖掘前自动切到热栏中的最佳工具，完成后恢复")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notOnUse = sgGeneral.add(new BoolSetting.Builder()
        .name("　使用时跳过")
        .description("使用物品期间不自动切换工具")
        .defaultValue(true)
        .visible(autoSwitch::get)
        .build()
    );

    private final Setting<Boolean> heartbeatSwing = sgGeneral.add(new BoolSetting.Builder()
        .name("心跳挥手")
        .description("挖掘期间周期发送挥手包，模拟持续按住左键")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> heartbeatInterval = sgGeneral.add(new IntSetting.Builder()
        .name("　挥手间隔")
        .description("心跳挥手的 tick 间隔")
        .defaultValue(1)
        .min(1)
        .sliderMax(5)
        .visible(heartbeatSwing::get)
        .build()
    );

    private final Setting<Boolean> strictMargin = sgGeneral.add(new BoolSetting.Builder()
        .name("安全余量")
        .description("发送 STOP 前额外等待一个 tick 增加安全裕度")
        .defaultValue(false)
        .build()
    );

    // ── Grim 合规 ──

    private final Setting<Boolean> grimBypass = sgGrim.add(new BoolSetting.Builder()
        .name("启用 Grim 合规")
        .description("启用 Grim 感知的延迟预算调度。关闭时使用固定 275ms 块间延迟。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> delayBudgetTarget = sgGrim.add(new IntSetting.Builder()
        .name("　延迟预算上限")
        .description("允许的最大 blockDelayBalance（ms）。越低越安全，越高 burst 越快。稳定~700，激进~900。")
        .defaultValue(900)
        .min(0)
        .sliderMax(1000)
        .visible(grimBypass::get)
        .build()
    );

    private final Setting<Boolean> drainEnabled = sgGrim.add(new BoolSetting.Builder()
        .name("　主动衰减")
        .description("预算偏高时利用 START+ABORT 在周围方块上快速衰减 balance")
        .defaultValue(true)
        .visible(grimBypass::get)
        .build()
    );

    private final Setting<Integer> drainTarget = sgGrim.add(new IntSetting.Builder()
        .name("　　衰减目标")
        .description("衰减至此 balance 水平（ms）。越低衰减越积极。")
        .defaultValue(100)
        .min(0)
        .sliderMax(800)
        .visible(() -> grimBypass.get() && drainEnabled.get())
        .build()
    );

    private final Setting<Boolean> doubleMine = sgGrim.add(new BoolSetting.Builder()
        .name("Double Mine")
        .description("利用 Y=5480 exploit 污染 GrimAC FastBreak 后，通过服务端 failedToMine 机制实现双方块并行挖掘。会在 AirLiquidBreak 产生 alert（无 kick/ban）。")
        .defaultValue(false)
        .visible(grimBypass::get)
        .build()
    );

    private final Setting<Integer> doubleMineThreshold = sgGrim.add(new IntSetting.Builder()
        .name("　Double Mine 阈值")
        .description("只对挖掘需要 ≥ 此 tick 数的方块启用 Double Mine。低于此值的方块 drain 时间不足以回血，无收益。默认 10 tick (500ms)。")
        .defaultValue(10)
        .min(4)
        .sliderMax(200)
        .visible(() -> grimBypass.get() && doubleMine.get())
        .build()
    );

    // ── 渲染 ──

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("启用渲染")
        .description("渲染正在挖掘的方块")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("形状模式")
        .description("渲染方式")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> readySideColor = sgRender.add(new ColorSetting.Builder()
        .name("就绪-面颜色")
        .description("可立即破坏的方块面颜色")
        .defaultValue(new SettingColor(0, 204, 0, 10))
        .build()
    );

    private final Setting<SettingColor> readyLineColor = sgRender.add(new ColorSetting.Builder()
        .name("就绪-线颜色")
        .description("可立即破坏的方块线颜色")
        .defaultValue(new SettingColor(0, 204, 0, 255))
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("挖掘中-面颜色")
        .description("正在挖掘的方块面颜色")
        .defaultValue(new SettingColor(204, 0, 0, 10))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("挖掘中-线颜色")
        .description("正在挖掘的方块线颜色")
        .defaultValue(new SettingColor(204, 0, 0, 255))
        .build()
    );

    private final Setting<SettingColor> drainSideColor = sgRender.add(new ColorSetting.Builder()
        .name("衰减目标-面颜色")
        .description("衰减目标方块的面颜色")
        .defaultValue(new SettingColor(100, 50, 200, 10))
        .visible(() -> grimBypass.get() && drainEnabled.get())
        .build()
    );

    private final Setting<SettingColor> drainLineColor = sgRender.add(new ColorSetting.Builder()
        .name("衰减目标-线颜色")
        .description("衰减目标方块的线颜色")
        .defaultValue(new SettingColor(100, 50, 200, 255))
        .visible(() -> grimBypass.get() && drainEnabled.get())
        .build()
    );

    // ======================== State ========================

    private final Pool<MyBlock> blockPool = new Pool<>(MyBlock::new);
    public final List<MyBlock> blocks = new ArrayList<>();

    /** 上一块 STOP 发出时的系统时间戳（ms），用于本地镜像 Grim blockDelayBalance */
    private long lastFinishMs;
    /** 本地镜像：Grim blockDelayBalance 的估计值（ms） */
    private double localDelayBalance;

    /**
     * autoSwitch 生效时，记住任务开始前玩家实际握的原始槽位。
     * -1 表示当前没有需要恢复的槽位。
     */
    private int savedSlot = -1;

    /** 本 tick 是否检测到鼠标滚轮事件（用于判断用户主动切槽） */
    private boolean scrolledThisTick = false;
    /** 连续槽位冲突 tick 计数（用于模块干扰的渐进式响应） */
    private int slotConflictTicks = 0;

    /** 当前 drain 目标位置（渲染用），null = 无活跃 drain */
    private BlockPos drainRenderPos;
    /** drain 渲染过期时间（ms） */
    private long drainRenderExpiry;

    public PacketMine() {
        super(Categories.World, "packet-mine", "通过发包挖掘方块，无需播放挖掘动画。");
    }

    // ======================== Lifecycle ========================

    @Override
    public void onActivate() {
        // Grim 服务端不会因模块开关而重置 delay 状态，所以不重置 lastFinishMs / localDelayBalance
        savedSlot = -1;
        scrolledThisTick = false;
        slotConflictTicks = 0;
        drainRenderPos = null;
    }

    @Override
    public void onDeactivate() {
        // 恢复槽位
        restoreSlot();
        for (MyBlock block : blocks) blockPool.free(block);
        blocks.clear();
        scrolledThisTick = false;
        slotConflictTicks = 0;
        drainRenderPos = null;
    }

    @EventHandler
    private void onStartBreakingBlock(StartBreakingBlockEvent event) {
        if (!BlockUtils.canBreak(event.blockPos)) return;

        event.cancel();

        if (!isMiningBlock(event.blockPos)) {
            MyBlock b = blockPool.get().set(event);
            blocks.add(b);

            // 入队即驱动：若本块成为队首，标准 tick 流程立即处理
            if (blocks.getFirst() == b) b.tick();
        }
    }

    public boolean isMiningBlock(BlockPos pos) {
        for (MyBlock block : blocks) {
            if (block.blockPos.equals(pos)) return true;
        }
        return false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {

        // 清理已完成的任务 + secondary 追踪
        Iterator<MyBlock> it = blocks.iterator();
        while (it.hasNext()) {
            MyBlock b = it.next();
            if (b.secondary) {
                // secondary: 追踪 failedToMine 自动破坏
                b.tickSecondary();
            }
            if (b.phase == Phase.FINISHED) {
                blockPool.free(b);
                it.remove();
            }
        }

        // 如果没有活跃任务且有需要恢复的槽位，恢复之
        if (blocks.isEmpty() && savedSlot != -1) {
            restoreSlot();
        }

        // 找到队列中第一个非 secondary 的活跃任务
        MyBlock active = null;
        for (MyBlock b : blocks) {
            if (!b.secondary) { active = b; break; }
        }

        // 槽位冲突检测：渐进式响应，区分用户接管和模块干扰
        if (autoSwitch.get() && active != null) {
            if (active.lockedToolSlot != -1 && active.mining
                && mc.player.getInventory().getSelectedSlot() != active.lockedToolSlot) {

                if (isUserSlotInput()) {
                    active.lockedToolSlot = -1;
                    active.phase = Phase.ABORTING;
                    savedSlot = -1;
                    slotConflictTicks = 0;
                    scrolledThisTick = false;
                } else {
                    slotConflictTicks++;
                    if (slotConflictTicks >= 3) {
                        active.phase = Phase.ABORTING;
                        slotConflictTicks = 0;
                    }
                }
            } else {
                slotConflictTicks = 0;
            }
        }

        // 每 tick 只驱动第一个非 secondary 的活跃任务
        if (active != null) {
            boolean drained = active.phase == Phase.PENDING_START && shouldDrain() && executeDrainStep();
            if (!drained) active.tick();
        } else if (shouldDrain()) {
            // Idle drain: 无活跃任务时也主动衰减 balance
            executeDrainStep();
        }

        scrolledThisTick = false;
    }

    @EventHandler
    private void onMouseScroll(MouseScrollEvent event) {
        // 标记本 tick 有滚轮操作，供槽位冲突检测使用
        scrolledThisTick = true;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;

        for (MyBlock block : blocks) {
            if (Modules.get().get(BreakIndicators.class).isActive()
                && Modules.get().get(BreakIndicators.class).packetMine.get()
                && block.mining) {
                continue;
            }
            block.render(event);
        }

        // Drain 目标渲染：半透明紫色边框，500ms 后自动消失
        if (drainRenderPos != null && System.currentTimeMillis() < drainRenderExpiry) {
            BlockState drainState = mc.world.getBlockState(drainRenderPos);
            VoxelShape shape = drainState.getOutlineShape(mc.world, drainRenderPos);
            double x1, y1, z1, x2, y2, z2;
            if (shape.isEmpty()) {
                x1 = drainRenderPos.getX(); y1 = drainRenderPos.getY(); z1 = drainRenderPos.getZ();
                x2 = x1 + 1; y2 = y1 + 1; z2 = z1 + 1;
            } else {
                x1 = drainRenderPos.getX() + shape.getMin(Direction.Axis.X);
                y1 = drainRenderPos.getY() + shape.getMin(Direction.Axis.Y);
                z1 = drainRenderPos.getZ() + shape.getMin(Direction.Axis.Z);
                x2 = drainRenderPos.getX() + shape.getMax(Direction.Axis.X);
                y2 = drainRenderPos.getY() + shape.getMax(Direction.Axis.Y);
                z2 = drainRenderPos.getZ() + shape.getMax(Direction.Axis.Z);
            }
            event.renderer.box(x1, y1, z1, x2, y2, z2, drainSideColor.get(), drainLineColor.get(), shapeMode.get(), 0);
        } else {
            drainRenderPos = null;
        }
    }

    // ======================== Helpers ========================

    /**
     * 检测是否存在用户主动切换槽位的输入。
     * 涵盖数字键 1~9 和鼠标滚轮两种操作方式。
     */
    private boolean isUserSlotInput() {
        for (var key : mc.options.hotbarKeys) {
            if (key.isPressed()) return true;
        }
        return scrolledThisTick;
    }

    /** 判断当前 tick 是否"安静"——没有使用物品等会和 dig 包冲突的行为 */
    private boolean isQuietTick() {
        if (mc.player == null) return false;
        if (mc.player.isUsingItem()) return false;
        if (mc.options.useKey.isPressed()) return false;
        return true;
    }

    /**
     * 预测当前时刻开始挖掘后，Grim blockDelayBalance 的投影值。
     *
     * <p>镜像 Grim FastBreak 的块间检查逻辑：
     * <ul>
     *   <li>breakDelay >= 275ms → balance *= 0.9（衰减）</li>
     *   <li>breakDelay &lt; 275ms → balance += (300 - breakDelay)（累积）</li>
     * </ul>
     */
    private double projectedDelayBalance() {
        long now = System.currentTimeMillis();
        double breakDelay = now - lastFinishMs;
        return breakDelay >= 275
            ? localDelayBalance * 0.9
            : localDelayBalance + (300 - breakDelay);
    }

    /**
     * 判断当前时刻是否可以发出 START。
     *
     * <p>双路径设计，避免 ref 中描述的 "永远等不下来" 死锁：
     * <ol>
     *   <li>软预算路径：projected <= delayBudgetTarget</li>
     *   <li>恢复性路径：已等过 275ms 衰减点，且 projected <= 900（clamp=1000 后单次 0.9 的硬上限）</li>
     * </ol>
     * 恢复路径让系统在高 buffer 区间仍能执行合法的恢复性 START，
     * 每次 START 都会通过 commitStartDelayBudget() 真正降低 localDelayBalance。
     */
    private boolean canIssueStartNow() {
        // Grim Bypass 关闭时：简化为固定 275ms 块间延迟
        if (!grimBypass.get()) return System.currentTimeMillis() - lastFinishMs >= 275;

        double projected = projectedDelayBalance();

        // 1. 正常软预算路径
        if (projected <= delayBudgetTarget.get()) return true;

        // 2. 恢复性路径：距上次 finish 已过 275ms 并且 projected 在 Grim 可恢复区内
        long now = System.currentTimeMillis();
        double breakDelay = now - lastFinishMs;
        if (breakDelay >= 275 && projected <= 900.0) return true;

        return false;
    }

    /**
     * 联动 SpeedMine：查询当前可用的 blockBreakBalance 余量（ms）。
     *
     * <p>当 SpeedMine 的 Grim 状态机处于加速阶段时，返回可用余量；
     * 否则返回 0（不加速）。PacketMine 在 isReadyToStop() 中
     * 据此计算可提前发送 STOP 的时间量。
     */
    private double getBreakBudgetHeadroom() {
        SpeedMine speedMine = Modules.get().get(SpeedMine.class);
        if (speedMine != null && speedMine.isGrimAware() && !speedMine.isGrimCoolingDown()) {
            return speedMine.getBreakBudgetHeadroom();
        }
        return 0;
    }

    /**
     * 在 START 实际发出时，提交本地 delayBalance 更新。
     * 必须在 sendStartPacket 所在的 callback 中调用。
     */
    private void commitStartDelayBudget() {
        if (!grimBypass.get()) return;
        long now = System.currentTimeMillis();
        double breakDelay = now - lastFinishMs;
        localDelayBalance = breakDelay >= 275
            ? localDelayBalance * 0.9
            : localDelayBalance + (300 - breakDelay);
        clampDelayBalance();
    }

    private void clampDelayBalance() {
        double cap = 1000.0;
        localDelayBalance = Math.max(-cap, Math.min(cap, localDelayBalance));
    }

    // -------------------- Balance Drain --------------------

    /**
     * 是否应在本 tick 执行 balance 主动消耗。
     *
     * <p>触发条件：breakDelay >= 275ms（Grim 走衰减路径）且衰减后 balance 仍高于 drainTarget。
     * 这样 drain 后的 START 以极低 balance 起步，后续多个块可连续即时 START。
     */
    private boolean shouldDrain() {
        if (!grimBypass.get() || !drainEnabled.get()) return false;
        long breakDelay = System.currentTimeMillis() - lastFinishMs;
        if (breakDelay < 275) return false;
        return localDelayBalance * 0.9 > drainTarget.get();
    }

    /**
     * 在交互范围内搜索最佳 drain 目标。
     * 评分综合：视角偏转（权重 3）+ 距离。排除瞬破方块和正在挖的方块。
     * doubleMine 启用时允许空气方块作为 drain 目标（AirLiquidBreak flag 已被接受）。
     */
    private DrainTarget findDrainTarget() {
        Vec3d eye = mc.player.getEyePos();
        Vec3d look = mc.player.getRotationVec(1.0f);
        double range = mc.player.getBlockInteractionRange();
        int slot = mc.player.getInventory().getSelectedSlot();
        BlockPos center = mc.player.getBlockPos();
        int r = (int) Math.ceil(range);
        boolean allowAir = doubleMine.get(); // doubleMine 已接受 AirLiquidBreak flag

        DrainTarget best = null;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    BlockState state = mc.world.getBlockState(pos);

                    if (state.isAir()) {
                        if (!allowAir) continue;
                        Vec3d anchor = Vec3d.ofCenter(pos);
                        if (eye.squaredDistanceTo(anchor) > range * range) continue;
                        // 选择玩家眼睛所在侧的面，避免 PositionBreakA flag
                        Direction airFace = resolveAirFace(eye, anchor);
                        Vec3d toBlock = anchor.subtract(eye).normalize();
                        double anglePenalty = 1.0 - look.dotProduct(toBlock);
                        double score = anglePenalty * 3.0 + eye.distanceTo(anchor);
                        if (best == null || score < best.score) {
                            best = new DrainTarget(pos, airFace, score);
                        }
                        continue;
                    }

                    if (!BlockUtils.canBreak(pos)) continue;
                    if (BlockUtils.getBreakDelta(slot, state) >= 1.0) continue;
                    if (isMiningBlock(pos)) continue;

                    Direction face = resolveBestFace(pos);
                    if (face == null) continue;

                    Vec3d anchor = getFaceAnchor(pos, face);

                    // LOS 检查：raycast 从眼睛到面锚点，确保没有其他方块遮挡
                    BlockHitResult hit = mc.world.raycast(new RaycastContext(
                        eye, anchor, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
                    if (!hit.getBlockPos().equals(pos)) continue;
                    Vec3d toBlock = anchor.subtract(eye).normalize();
                    double anglePenalty = 1.0 - look.dotProduct(toBlock);
                    double score = anglePenalty * 3.0 + eye.distanceTo(anchor);
                    if (best == null || score < best.score) best = new DrainTarget(pos, face, score);
                }
            }
        }
        return best;
    }

    /**
     * 对目标方块快速发送 START+ABORT 序列以消耗 delay balance。
     * 每组 START 在 Grim 侧触发 balance *= 0.9（breakDelay >= 275ms 保证衰减路径），
     * CANCELLED_DIGGING 不影响任何 Grim 检查。
     * pairs 数量由 log 公式精确计算到 drainTarget，上限 30。
     *
     * @return true 如果注册了旋转（占用本 tick），false 如果无可用目标
     */
    private boolean executeDrainStep() {
        DrainTarget target = findDrainTarget();
        if (target == null) return false;

        drainRenderPos = target.pos;
        drainRenderExpiry = System.currentTimeMillis() + 500;

        // 计算最优 pairs: balance * 0.9^n <= drainTarget → n = ceil(log(target/balance) / log(0.9))
        int optimalPairs;
        if (localDelayBalance <= drainTarget.get()) {
            optimalPairs = 0;
        } else {
            optimalPairs = (int) Math.ceil(
                Math.log((double) drainTarget.get() / localDelayBalance) / Math.log(0.9));
        }
        int maxPairs = Math.min(Math.max(optimalPairs, 1), 30);

        Vec3d anchor = getFaceAnchor(target.pos, target.face);
        final int pairsToSend = maxPairs;
        Rotations.rotate(Rotations.getYaw(anchor), Rotations.getPitch(anchor), 50, () -> {
            for (int i = 0; i < pairsToSend && localDelayBalance > drainTarget.get(); i++) {
                sendStartPacket(target.pos, target.face);
                sendAbortPacket(target.pos, target.face);
                localDelayBalance *= 0.9;
            }
            clampDelayBalance();
        });
        return true;
    }

    private record DrainTarget(BlockPos pos, Direction face, double score) {}

    // -------------------- Y=5480 Exploit --------------------

    /** Y=5480: 超出世界高度的 AIR 坐标，用于污染 GrimAC FastBreak.maximumBlockDamage */
    private static final BlockPos EXPLOIT_POS = new BlockPos(0, 5480, 0);

    /**
     * 发送 Y=5480 exploit START 包。
     *
     * <p>效果链：
     * <ol>
     *   <li>AirLiquidBreak: flag + cancel（包不转发到 MC 服务端）</li>
     *   <li>FastBreak: targetBlockPosition=Y5480, maximumBlockDamage=∞（cancel 前已执行）</li>
     *   <li>后续所有 STOP 的 predictedTime=ceil(1/∞)*50=0, diff 永远为负</li>
     * </ol>
     *
     * <p>同时更新本地 delay balance 镜像。注意 GrimAC FastBreak 中 lastFinishBreak
     * 不在 START_DIGGING 时更新，所以同 tick 内多个 START 的 breakDelay 都相同。
     */
    private void sendExploitFlood() {
        // 在合法 START 之前发送 exploit START，确保 lastBlock 被后续合法 START 覆盖
        // WrongBreak 规则: START → 设 lastBlock。所以 Y5480 START → lastBlock=Y5480，
        // 然后 real START → lastBlock=realPos → STOP(realPos) 匹配 ✓
        // 使用 DOWN 面：PositionBreakA 检查 minY > combined.minY，
        // 玩家 minY≈65 < 5480 → false → 不 flag
        sendStartPacket(EXPLOIT_POS, Direction.DOWN);
        commitStartDelayBudget();
    }

    /**
     * 判断指定方块是否满足 double mine 的自动阈值条件。
     *
     * <p>计算方块的预估挖掘 tick 数（ceil(1/delta)），只有 ≥ doubleMineThreshold 才启用。
     * 这确保只对 drain 时间足以回血的慢速方块使用 double mine。
     *
     * @param state 目标方块状态
     * @param toolSlot 使用的工具槽位
     * @return true 如果方块足够慢，值得做 double mine
     */
    private boolean meetsDoubleMineThreshold(BlockState state, int toolSlot) {
        double delta = BlockUtils.getBreakDelta(toolSlot, state);
        if (delta <= 0 || delta >= 1.0) return false;
        int ticks = (int) Math.ceil(1.0 / delta);
        return ticks >= doubleMineThreshold.get();
    }

    /**
     * 判断当前是否应该启用 double mine 路径。
     *
     * @return true 如果所有前置条件满足（设置开启、exploit 开启、队列有第二个方块、方块满足阈值）
     */
    private boolean shouldDoubleMine() {
        if (!grimBypass.get() || !doubleMine.get()) return false;
        // 已有 secondary 在等待 failedToMine
        for (MyBlock b : blocks) if (b.secondary) return false;
        // 队列中至少需要 2 个非 secondary 方块（当前 + 下一个）
        int active = 0;
        for (MyBlock b : blocks) if (!b.secondary) active++;
        return active >= 2;
    }

    /** 获取 face 中心点的微偏移锚点（用于旋转/距离判断） */
    private static Vec3d getFaceAnchor(BlockPos pos, Direction face) {
        return Vec3d.ofCenter(pos).add(
            face.getOffsetX() * 0.49,
            face.getOffsetY() * 0.49,
            face.getOffsetZ() * 0.49
        );
    }

    /**
     * 为空气方块选择不会触发 PositionBreakA 的面。
     * PositionBreakA 检查眼睛是否在方块面的正面方向侧：
     * UP → maxY < combined.maxY 为 flag, DOWN → minY > combined.minY 为 flag, etc.
     * 选择眼睛相对方块中心偏移最大的轴对应的面（即眼睛一定在该面的正确侧）。
     */
    private static Direction resolveAirFace(Vec3d eye, Vec3d blockCenter) {
        double dx = eye.x - blockCenter.x;
        double dy = eye.y - blockCenter.y;
        double dz = eye.z - blockCenter.z;
        double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ay >= ax && ay >= az) return dy > 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /**
     * 从六个方向中选出当前最佳可用的击打面。
     *
     * <p>评估标准：在 reach 范围内 + face 朝向与眼睛所在侧一致（即眼睛在该面的正面方向一侧）。
     * 在候选面中取与眼睛距离最近者。
     *
     * @return 最佳面，如果全部不可用则返回 null
     */
    private Direction resolveBestFace(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        double rangeSq = mc.player.getBlockInteractionRange() * mc.player.getBlockInteractionRange();
        Vec3d center = Vec3d.ofCenter(pos);

        Direction best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (Direction face : Direction.values()) {
            // 眼睛必须在该面的正面方向一侧
            double dot = (eye.x - center.x) * face.getOffsetX()
                       + (eye.y - center.y) * face.getOffsetY()
                       + (eye.z - center.z) * face.getOffsetZ();
            if (dot <= 0) continue;

            Vec3d anchor = getFaceAnchor(pos, face);
            double distSq = eye.squaredDistanceTo(anchor);
            if (distSq > rangeSq) continue;

            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = face;
            }
        }

        return best;
    }

    /**
     * 外部查询：PacketMine 是否拥有工具槽位的控制权。
     *
     * <p>只要模块开启且 autoSwitch 打开，就表示"本次挖掘系统由 PacketMine 接管工具槽位"。
     * 不再依赖 blocks 是否非空，避免第一块加入前 AutoTool 抢先切槽的竞态。
     */
    public boolean shouldOwnToolSelection() {
        return isActive() && autoSwitch.get();
    }

    /** Expose local delay balance estimate for cross-module coordination. */
    public double getLocalDelayBalance() {
        return localDelayBalance;
    }

    /**
     * 外部模块（如 Printer）调用：将指定方块加入挖掘队列。
     * 自动解析最佳面，跳过不可破坏或已在队列中的方块。
     */
    public void addBreakTarget(BlockPos pos) {
        if (mc.world == null || mc.player == null) return;
        if (!BlockUtils.canBreak(pos)) return;
        if (isMiningBlock(pos)) return;

        Direction face = resolveBestFace(pos);
        if (face == null) face = Direction.UP;

        MyBlock b = blockPool.get();
        b.blockPos = pos;
        b.direction = face;
        b.currentFace = face;
        b.blockState = mc.world.getBlockState(pos);
        b.block = b.blockState.getBlock();
        b.phase = Phase.PENDING_START;
        b.mining = false;
        b.progress = 0;
        b.lockedToolSlot = -1;
        b.heartbeatTimer = 0;
        b.rotationQueued = false;
        b.rotationPhaseToken = null;
        b.startMs = 0;
        b.readyMs = 0;
        b.maxDelta = 0;
        b.pendingSinceMs = System.currentTimeMillis();
        b.activated = false;
        b.secondary = false;
        b.expectedFinishMs = 0;
        blocks.add(b);
    }

    /**
     * 为指定方块状态找到热栏中的最佳工具槽位。
     *
     * <p>当 AutoTool 模块启用时，委托其评分系统（附魔偏好、精准/时运、
     * 耐久保护、黑白名单全部生效）；否则回退到纯速度评估。
     *
     * @return 最佳槽位（0~8），或 -1 表示不切换
     */
    private int findBestToolSlot(BlockState state) {
        if (!autoSwitch.get()) return -1;
        if (notOnUse.get() && mc.player.isUsingItem()) return -1;

        AutoTool autoTool = Modules.get().get(AutoTool.class);
        if (autoTool.isActive()) {
            return autoTool.findBestSlot(state);
        }

        FindItemResult result = InvUtils.findFastestTool(state);
        return result.found() ? result.slot() : -1;
    }

    /**
     * 统一的槽位维护方法：确保当前任务所需的工具槽位处于选中状态。
     *
     * <p>如果 auto-switch 未启用或任务没有锁定工具，则不做任何事。
     * 否则，幂等地保证本地和服务端的选中槽位都是任务工具。
     * 首次切槽时记录 {@code savedSlot}，以便任务结束后恢复。
     *
     * <p>使用 vanilla 的 syncSelectedSlot 而非直接发包，避免绕过 lastSelectedSlot 跟踪导致重复发包。
     */
    private void ensureTaskToolSelected(MyBlock block) {
        if (!autoSwitch.get()) return;
        if (block.lockedToolSlot == -1) return;

        int selected = mc.player.getInventory().getSelectedSlot();

        if (savedSlot == -1) {
            savedSlot = selected;
        }

        if (selected != block.lockedToolSlot) {
            mc.player.getInventory().setSelectedSlot(block.lockedToolSlot);
            ((IClientPlayerInteractionManager) mc.interactionManager).meteor$syncSelected();
        }
    }

    /**
     * 恢复到 autoSwitch 之前的原始槽位。
     * 同样使用 vanilla syncSelectedSlot 避免重复发包。
     */
    private void restoreSlot() {
        if (savedSlot == -1) return;
        mc.player.getInventory().setSelectedSlot(savedSlot);
        ((IClientPlayerInteractionManager) mc.interactionManager).meteor$syncSelected();
        savedSlot = -1;
    }

    private void sendSwing() {
        mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
    }

    private void sendStartPacket(BlockPos pos, Direction face) {
        int seq = mc.world.getPendingUpdateManager().incrementSequence().getSequence();
        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, face, seq));
    }

    private void sendStopPacket(BlockPos pos, Direction face) {
        int seq = mc.world.getPendingUpdateManager().incrementSequence().getSequence();
        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, face, seq));
    }

    private void sendAbortPacket(BlockPos pos, Direction face) {
        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, pos, face, 0));
    }

    // ======================== Phase ========================

    public enum Phase {
        /** 等待一个干净 tick 来发 START */
        PENDING_START,
        /** 已发 START，正在积分 progress */
        MINING,
        /** progress 到位，等待一个干净 tick 来发 STOP */
        PENDING_STOP,
        /** 需要中止（目标失效），等待发 ABORT */
        ABORTING,
        /** 已完成，等待下次清理 */
        FINISHED
    }

    // ======================== MyBlock ========================

    /**
     * 单个挖掘任务的状态机。
     *
     * <p>公共字段 {@code blockPos}, {@code blockState}, {@code mining}, {@code progress}
     * 是 {@link BreakIndicators} 渲染所需的外部契约，不可改名。
     */
    public class MyBlock {
        public BlockPos blockPos;
        public BlockState blockState;
        public Block block;
        /** START 时记录的面（仅用于 START 包的 face） */
        public Direction direction;

        /** 外部契约：是否已经发过 START（BreakIndicators 用此判断是否渲染） */
        public boolean mining;
        /** 外部契约：当前挖掘进度 0.0~1.0+（BreakIndicators 用此插值渲染） */
        public double progress;

        /** 当前阶段 */
        Phase phase;
        /** START 时锁定的工具槽位（-1 = 当前手持） */
        int lockedToolSlot;
        /** 心跳计数器 */
        int heartbeatTimer;

        /**
         * 当前用于 STOP/ABORT/validity 的动态面。
         * 初始等于 {@code direction}，挖掘过程中可能因玩家移位而被更新。
         */
        Direction currentFace;

        /** rotation 请求是否已排队（防止重复提交导致陈旧 callback 累积） */
        boolean rotationQueued;
        /** 提交 rotation callback 时的阶段快照，callback 执行时需校验 */
        Phase rotationPhaseToken;

        /**
         * START 实际发出时的 wall-clock 时间戳（ms）。
         * 镜像 Grim 的 {@code startBreak = System.currentTimeMillis()}，
         * 用于 canStopNow() 计算 realTime，确保无论 rotation 相位如何都准确。
         */
        long startMs;

        /**
         * 首次满足 canStopNow() 条件的 wall-clock 时间戳（ms），0 = 尚未到达。
         * 用于 strictMargin：首次 ready 后需再等 >= 50ms（约一个 tick）才允许 STOP。
         */
        long readyMs;

        /** 本块挖掘期间观察到的最大单 tick delta（镜像 Grim maximumBlockDamage） */
        double maxDelta;

        /** PENDING_START 超时保护的基准时间戳（ms），在首次 tick() 时重置以排除排队等候耗时 */
        long pendingSinceMs;

        /** 是否已被 tick() 首次驱动（用于延迟初始化 pendingSinceMs） */
        boolean activated;

        /** 是否为 double mine 的 secondary block（已 STOP，等待 failedToMine 自动完成） */
        boolean secondary;

        /** secondary 预期自动完成的 wall-clock 时间戳（ms），0 = 未设置 */
        long expectedFinishMs;

        public MyBlock set(StartBreakingBlockEvent event) {
            this.blockPos = event.blockPos;
            this.direction = event.direction;
            this.currentFace = event.direction;
            this.blockState = mc.world.getBlockState(blockPos);
            this.block = blockState.getBlock();
            this.phase = Phase.PENDING_START;
            this.mining = false;
            this.progress = 0;
            this.lockedToolSlot = -1;
            this.heartbeatTimer = 0;
            this.rotationQueued = false;
            this.rotationPhaseToken = null;
            this.startMs = 0;
            this.readyMs = 0;
            this.maxDelta = 0;
            this.pendingSinceMs = System.currentTimeMillis();
            this.activated = false;
            this.secondary = false;
            this.expectedFinishMs = 0;
            return this;
        }

        /** 外部契约：是否可以立刻破坏（用于渲染颜色判断） */
        public boolean isReady() {
            if (secondary) return false; // secondary 还在等 failedToMine，不算 ready
            return canStopNow();
        }

        /**
         * Secondary block tick：追踪 failedToMine 自动破坏，估算进度。
         * 由 onTick 清理循环调用，不参与主驱动。
         */
        void tickSecondary() {
            // 方块已被服务端破坏（failedToMine 完成）
            if (mc.world.getBlockState(blockPos).getBlock() != block) {
                phase = Phase.FINISHED;
                return;
            }

            // 估算进度（基于 startMs 到现在的时间 vs 预期总 tick）
            if (expectedFinishMs > 0 && startMs > 0) {
                long elapsed = System.currentTimeMillis() - startMs;
                long total = expectedFinishMs - startMs;
                progress = total > 0 ? Math.min(1.0, (double) elapsed / total) : 1.0;
            }

            // 超时（预期 + 2s）：放弃追踪
            if (expectedFinishMs > 0 && System.currentTimeMillis() > expectedFinishMs + 2000) {
                phase = Phase.FINISHED;
            }
        }

        /** 清除 rotation 排队状态，防止陈旧 callback 卡死后续 phase */
        private void clearRotationState() {
            rotationQueued = false;
            rotationPhaseToken = null;
        }

        /**
         * 统一旋转分发。准星已对准目标方块时用准星命中面 + 直接执行；
         * 否则按 shouldRotate 参数决定是否排队 silent rotation。
         * callback 会校验 phase 一致性，防止陈旧回调。
         */
        private void dispatchWithRotation(boolean shouldRotate, Phase expectedPhase,
                                          BlockPos pos, Direction face, Runnable action) {
            // 准星已对准目标 → 用准星命中面，直接执行（跳过不必要的旋转）
            if (mc.crosshairTarget instanceof BlockHitResult bhr && bhr.getBlockPos().equals(pos)) {
                currentFace = bhr.getSide();
                action.run();
                return;
            }
            if (!shouldRotate) { action.run(); return; }
            if (rotationQueued) return;
            rotationQueued = true;
            rotationPhaseToken = expectedPhase;
            Vec3d anchor = getFaceAnchor(pos, face);
            Rotations.rotate(Rotations.getYaw(anchor), Rotations.getPitch(anchor), 50, () -> {
                if (phase != expectedPhase) { clearRotationState(); return; }
                action.run();
            });
        }

        // -------------------- Main tick --------------------

        void tick() {
            // 首次被驱动时刷新 pendingSinceMs，避免在队列中排队等候期间老化
            // （入队时 pendingSinceMs 已设，但只有成为队首后才真正开始尝试 START）
            if (!activated) {
                activated = true;
                pendingSinceMs = System.currentTimeMillis();
            }

            // 先检查目标是否仍然有效
            if (!isStillValid()) {
                if (mining) {
                    phase = Phase.ABORTING;
                } else {
                    phase = Phase.FINISHED;
                    return;
                }
            }

            switch (phase) {
                case PENDING_START -> tickPendingStart();
                case MINING -> tickMining();
                case PENDING_STOP -> tickPendingStop();
                case ABORTING -> tickAborting();
                default -> {}
            }
        }

        // -------------------- PENDING_START --------------------

        private void tickPendingStart() {
            if (!isQuietTick()) return;

            // 硬超时保护 2000ms + 双路径 delay 调度（软预算 + 恢复路径，避免死锁）
            boolean timedOut = System.currentTimeMillis() - pendingSinceMs > 2000;
            if (!timedOut && !canIssueStartNow()) return;

            // 锁定工具并预判瞬破
            lockedToolSlot = findBestToolSlot(blockState);
            ensureTaskToolSelected(MyBlock.this);
            int effectiveSlot = mc.player.getInventory().getSelectedSlot();
            boolean instaMine = BlockUtils.getBreakDelta(effectiveSlot, blockState) >= 1.0;

            // 在真正发 START 前刷新面：入队后玩家可能已移动，旧 direction 可能不是最优/最稳的面
            refreshCurrentFace();

            dispatchWithRotation(rotateOnStart.get(), Phase.PENDING_START, blockPos, currentFace, () -> {
                ensureTaskToolSelected(MyBlock.this);

                // Double Mine: exploit 包含在 doubleMine 中，在合法 START 之前发 Y=5480 START
                // 污染 GrimAC FastBreak 的 maximumBlockDamage，使后续 STOP 的 diff 永远为负
                if (doubleMine.get()) {
                    sendExploitFlood();
                }

                sendSwing();
                sendStartPacket(blockPos, currentFace);
                direction = currentFace; // 记录实际使用的 START face
                commitStartDelayBudget();
                mining = true;
                startMs = System.currentTimeMillis();

                if (instaMine) {
                    // 瞬破：只需 START，服务端在 delta >= 1.0 时直接破坏方块。
                    // 不更新 lastFinishMs：Grim 的 lastFinishBreak 也仅在 FINISHED_DIGGING 更新
                    // （FastBreak.java:108），instamine 不发 STOP 所以 Grim 侧同样不更新，模型一致。
                    phase = Phase.FINISHED;
                } else {
                    progress = 0; heartbeatTimer = 0; readyMs = 0;
                    phase = Phase.MINING;
                }
                clearRotationState();
            });
        }

        // -------------------- MINING --------------------

        private void tickMining() {
            // 每 tick 先重申工具，确保画面/服务端/progress 三者一致
            ensureTaskToolSelected(MyBlock.this);

            // 使用实际选中槽位计算 delta（ensureTaskToolSelected 已保证槽位正确）
            int effectiveSlot = mc.player.getInventory().getSelectedSlot();
            double delta = BlockUtils.getBreakDelta(effectiveSlot, blockState);
            progress += delta;

            // 追踪最大 delta（镜像 Grim 的 maximumBlockDamage）
            maxDelta = Math.max(maxDelta, delta);

            // Double Mine 提前 STOP 路径：exploit 保证 GrimAC 侧安全，立即进入 PENDING_STOP
            if (shouldDoubleMine() && meetsDoubleMineThreshold(blockState, effectiveSlot) && startMs > 0) {
                phase = Phase.PENDING_STOP;
                clearRotationState();
                tickPendingStop();
                return;
            }

            // 缓存 canStopNow 结果，避免同拍重复计算
            boolean canStop = canStopNow();

            // 记录首次满足 canStopNow 的 wall-clock 时刻
            if (readyMs == 0 && canStop) {
                readyMs = System.currentTimeMillis();
            }

            // 心跳挥手（仅安静 tick 时发，避免和 use/interact 冲突）
            if (heartbeatSwing.get() && isQuietTick()) {
                heartbeatTimer++;
                if (heartbeatTimer >= heartbeatInterval.get()) {
                    sendSwing();
                    heartbeatTimer = 0;
                }
            }

            // 判断是否可以发 STOP（refreshCurrentFace 在 tickPendingStop 里统一做）
            if (canStop && isReadyToStop()) {
                phase = Phase.PENDING_STOP;
                clearRotationState();
                tickPendingStop();
            }
        }

        /**
         * 判断当前 tick 是否可以提前发送 STOP。
         *
         * <p>联动 SpeedMine 的 break balance 余量：
         * <ul>
         *   <li>计算 Grim 视角下的 predictedTime 和当前 realTime（wall-clock，镜像 Grim 的 now - startBreak）</li>
         *   <li>diff = predictedTime - realTime 就是 Grim 会记入 blockBreakBalance 的值</li>
         *   <li>只有当 diff <= headroom（或 diff < 25 触发衰减）时才允许提前 STOP</li>
         *   <li>headroom == 0 时退化为 progress >= 1.0 的标准行为</li>
         * </ul>
         *
         * <p>使用 wall-clock 而非 elapsedTicks*50 可确保无论 START/STOP 分别在 Pre 还是 Post 相位
         * 发出，计时模型都与 Grim 一致，消除 ref 指出的相位失配问题。
         */
        private boolean canStopNow() {
            if (maxDelta <= 0 || startMs == 0) return false;

            double predictedMs = Math.ceil(1.0 / maxDelta) * 50;
            double realMs = System.currentTimeMillis() - startMs;
            double diff = predictedMs - realMs;

            // diff < 25 → Grim 会衰减而非累积，always safe
            if (diff < 25) return true;

            // diff >= 25 → 需要 break budget headroom 消化
            double headroom = getBreakBudgetHeadroom();
            return diff <= headroom;
        }

        private boolean isReadyToStop() {
            if (!canStopNow()) return false;
            // strictMargin: 首次 ready 后再等 >= 50ms（约一个 tick）才放行
            if (strictMargin.get() && readyMs > 0) {
                return System.currentTimeMillis() - readyMs >= 50;
            }
            return true;
        }

        // -------------------- PENDING_STOP --------------------

        private void tickPendingStop() {
            if (!isQuietTick()) return;
            refreshCurrentFace();
            ensureTaskToolSelected(MyBlock.this);

            boolean useDoubleMine = shouldDoubleMine()
                && meetsDoubleMineThreshold(blockState, mc.player.getInventory().getSelectedSlot());

            dispatchWithRotation(rotateOnStop.get(), Phase.PENDING_STOP, blockPos, currentFace, () -> {
                ensureTaskToolSelected(MyBlock.this);
                sendSwing();
                sendStopPacket(blockPos, currentFace);
                lastFinishMs = System.currentTimeMillis();

                if (useDoubleMine) {
                    // 转为 secondary：保留在 blocks 列表中，由 tickSecondary 追踪 failedToMine
                    secondary = true;
                    double delta = BlockUtils.getBreakDelta(mc.player.getInventory().getSelectedSlot(), blockState);
                    expectedFinishMs = delta > 0
                        ? startMs + (long) Math.ceil(1.0 / delta) * 50
                        : 0;
                    phase = Phase.MINING; // 保持 MINING 使渲染继续显示进度
                } else {
                    phase = Phase.FINISHED;
                }

                clearRotationState();
            });
        }

        // -------------------- ABORTING --------------------

        private void tickAborting() {
            if (!mining) { phase = Phase.FINISHED; return; } // 未发过 START，直接清理
            if (!isQuietTick()) return;
            refreshCurrentFace();
            ensureTaskToolSelected(MyBlock.this);

            dispatchWithRotation(rotateOnAbort.get(), Phase.ABORTING, blockPos, currentFace, () -> {
                ensureTaskToolSelected(MyBlock.this);
                sendAbortPacket(blockPos, currentFace);
                phase = Phase.FINISHED;
                clearRotationState();
            });
        }

        // -------------------- Face resolution --------------------

        /** 尝试将 currentFace 更新为当前最佳可用面 */
        private void refreshCurrentFace() {
            Direction resolved = resolveBestFace(blockPos);
            if (resolved != null) currentFace = resolved;
        }

        // -------------------- Validity --------------------

        /**
         * 检查目标是否仍然有效。
         *
         * <p>不再只看起始 face 锚点距离，而是检查是否存在任意可到达的面。
         */
        private boolean isStillValid() {
            if (mc.world == null || mc.player == null) return false;
            if (mc.world.getBlockState(blockPos).getBlock() != block) return false;
            if (!BlockUtils.canBreak(blockPos)) return false;

            return resolveBestFace(blockPos) != null;
        }

        // -------------------- Render --------------------

        public void render(Render3DEvent event) {
            VoxelShape shape = mc.world.getBlockState(blockPos).getOutlineShape(mc.world, blockPos);

            double x1 = blockPos.getX();
            double y1 = blockPos.getY();
            double z1 = blockPos.getZ();
            double x2 = blockPos.getX() + 1;
            double y2 = blockPos.getY() + 1;
            double z2 = blockPos.getZ() + 1;

            if (!shape.isEmpty()) {
                x1 = blockPos.getX() + shape.getMin(Direction.Axis.X);
                y1 = blockPos.getY() + shape.getMin(Direction.Axis.Y);
                z1 = blockPos.getZ() + shape.getMin(Direction.Axis.Z);
                x2 = blockPos.getX() + shape.getMax(Direction.Axis.X);
                y2 = blockPos.getY() + shape.getMax(Direction.Axis.Y);
                z2 = blockPos.getZ() + shape.getMax(Direction.Axis.Z);
            }

            if (isReady()) {
                event.renderer.box(x1, y1, z1, x2, y2, z2, readySideColor.get(), readyLineColor.get(), shapeMode.get(), 0);
            } else {
                event.renderer.box(x1, y1, z1, x2, y2, z2, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            }
        }

    }
}
