/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.BreakIndicators;
import meteordevelopment.meteorclient.utils.misc.Pool;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

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
    private final SettingGroup sgRender = settings.createGroup("Render");

    // ======================== General ========================

    private final Setting<Integer> delayBudgetTarget = sgGeneral.add(new IntSetting.Builder()
        .name("delay-budget-target")
        .description("Maximum allowed Grim-style delay balance (ms). Lower = safer, higher = faster burst. Stable ~700, Edge ~900.")
        .defaultValue(800)
        .min(0)
        .sliderMax(1000)
        .build()
    );

    private final Setting<Boolean> rotateOnStart = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate-on-start")
        .description("Send a silent rotation when issuing START_DESTROY.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotateOnStop = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate-on-stop")
        .description("Send a silent rotation when issuing STOP_DESTROY.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotateOnAbort = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate-on-abort")
        .description("Send a silent rotation when issuing ABORT_DESTROY.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Locally switch to the best tool before START and keep it until finished.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notOnUse = sgGeneral.add(new BoolSetting.Builder()
        .name("not-on-use")
        .description("Don't auto-switch while using an item.")
        .defaultValue(true)
        .visible(autoSwitch::get)
        .build()
    );

    private final Setting<Boolean> heartbeatSwing = sgGeneral.add(new BoolSetting.Builder()
        .name("heartbeat-swing")
        .description("Send HandSwing packets during mining to simulate continuous left-click.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> heartbeatInterval = sgGeneral.add(new IntSetting.Builder()
        .name("heartbeat-interval")
        .description("Ticks between heartbeat swing packets.")
        .defaultValue(1)
        .min(1)
        .sliderMax(5)
        .visible(heartbeatSwing::get)
        .build()
    );

    private final Setting<Boolean> strictMargin = sgGeneral.add(new BoolSetting.Builder()
        .name("strict-margin")
        .description("Wait one additional tick before STOP for extra safety.")
        .defaultValue(false)
        .build()
    );

    // ======================== Render ========================

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Whether or not to render the block being mined.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> readySideColor = sgRender.add(new ColorSetting.Builder()
        .name("ready-side-color")
        .description("The color of the sides of the blocks that can be broken.")
        .defaultValue(new SettingColor(0, 204, 0, 10))
        .build()
    );

    private final Setting<SettingColor> readyLineColor = sgRender.add(new ColorSetting.Builder()
        .name("ready-line-color")
        .description("The color of the lines of the blocks that can be broken.")
        .defaultValue(new SettingColor(0, 204, 0, 255))
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The color of the sides of the blocks being rendered.")
        .defaultValue(new SettingColor(204, 0, 0, 10))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The color of the lines of the blocks being rendered.")
        .defaultValue(new SettingColor(204, 0, 0, 255))
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

    public PacketMine() {
        super(Categories.World, "packet-mine", "Sends packets to mine blocks without the mining animation.");
    }

    // ======================== Lifecycle ========================

    @Override
    public void onActivate() {
        lastFinishMs = 0;
        localDelayBalance = 0;
        savedSlot = -1;
    }

    @Override
    public void onDeactivate() {
        // 恢复槽位
        restoreSlot();
        for (MyBlock block : blocks) blockPool.free(block);
        blocks.clear();
    }

    // ======================== Events ========================

    @EventHandler
    private void onStartBreakingBlock(StartBreakingBlockEvent event) {
        if (!BlockUtils.canBreak(event.blockPos)) return;

        event.cancel();

        if (!isMiningBlock(event.blockPos)) {
            blocks.add(blockPool.get().set(event));
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

        // 清理已完成的任务，归还对象池
        Iterator<MyBlock> it = blocks.iterator();
        while (it.hasNext()) {
            MyBlock b = it.next();
            if (b.phase == Phase.FINISHED) {
                blockPool.free(b);
                it.remove();
            }
        }

        // 如果没有活跃任务且有需要恢复的槽位，恢复之
        if (blocks.isEmpty() && savedSlot != -1) {
            restoreSlot();
        }

        // 每 tick 只驱动队列中第一个活跃任务
        if (!blocks.isEmpty()) blocks.getFirst().tick();
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
    }

    // ======================== Helpers ========================

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
     * 在 START 实际发出时，提交本地 delayBalance 更新。
     * 必须在 sendStartPacket 所在的 callback 中调用。
     */
    private void commitStartDelayBudget() {
        long now = System.currentTimeMillis();
        double breakDelay = now - lastFinishMs;
        localDelayBalance = breakDelay >= 275
            ? localDelayBalance * 0.9
            : localDelayBalance + (300 - breakDelay);
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

    /** 找到热栏中最快的工具槽位，-1 表示没找到更好的 */
    private int findBestToolSlot(BlockState state) {
        if (!autoSwitch.get()) return -1;
        if (notOnUse.get() && mc.player.isUsingItem()) return -1;

        FindItemResult result = InvUtils.findFastestTool(state);
        return result.found() ? result.slot() : -1;
    }

    /**
     * 统一的槽位维护方法：确保当前任务所需的工具槽位处于选中状态。
     *
     * <p>如果 auto-switch 未启用或任务没有锁定工具，则不做任何事。
     * 否则，幂等地保证本地和服务端的选中槽位都是任务工具。
     * 首次切槽时记录 {@code savedSlot}，以便任务结束后恢复。
     */
    private void ensureTaskToolSelected(MyBlock block) {
        if (!autoSwitch.get()) return;
        if (block.lockedToolSlot == -1) return;

        int selected = mc.player.getInventory().selectedSlot;

        if (savedSlot == -1) {
            savedSlot = selected;
        }

        if (selected != block.lockedToolSlot) {
            mc.player.getInventory().selectedSlot = block.lockedToolSlot;
            mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(block.lockedToolSlot));
        }
    }

    /** 恢复到 autoSwitch 之前的原始槽位 */
    private void restoreSlot() {
        if (savedSlot == -1) return;
        mc.player.getInventory().selectedSlot = savedSlot;
        mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(savedSlot));
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
        /** START 后已经过的 tick 数 */
        int elapsedTicks;
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

        /** 首次 progress >= 1.0 的 tick（-1 = 尚未到达），用于 strictMargin 判断 */
        int readyTick;

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
            this.elapsedTicks = 0;
            this.heartbeatTimer = 0;
            this.rotationQueued = false;
            this.rotationPhaseToken = null;
            this.readyTick = -1;
            return this;
        }

        /** 外部契约：是否可以立刻破坏（用于渲染颜色判断） */
        public boolean isReady() {
            return progress >= 1;
        }

        /** 清除 rotation 排队状态，防止陈旧 callback 卡死后续 phase */
        private void clearRotationState() {
            rotationQueued = false;
            rotationPhaseToken = null;
        }

        // -------------------- Main tick --------------------

        void tick() {
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
            // 本地镜像 Grim blockDelayBalance：只有投影值在预算内才允许开始
            if (projectedDelayBalance() > delayBudgetTarget.get()) return;

            // 在 START 前确定并锁定工具
            lockedToolSlot = findBestToolSlot(blockState);
            ensureTaskToolSelected(MyBlock.this);

            // 统一路径：所有方块（含 delta >= 1 的瞬破块）都走 START → MINING → STOP
            // 瞬破块在 MINING 第一 tick 就会 progress >= 1.0，下一拍进入 PENDING_STOP
            // 这比同 tick START+STOP 更安全（Grim blockBreakBalance 不会持续积累）
            Runnable send = () -> {
                if (phase != Phase.PENDING_START) { clearRotationState(); return; }
                ensureTaskToolSelected(MyBlock.this);
                sendSwing();
                sendStartPacket(blockPos, direction);
                commitStartDelayBudget();
                mining = true;
                progress = 0;
                elapsedTicks = 0;
                heartbeatTimer = 0;
                readyTick = -1;
                phase = Phase.MINING;
                clearRotationState();
            };

            if (rotateOnStart.get()) {
                if (rotationQueued) return;
                rotationQueued = true;
                rotationPhaseToken = Phase.PENDING_START;
                Vec3d anchor = getFaceAnchor(blockPos, direction);
                Rotations.rotate(Rotations.getYaw(anchor), Rotations.getPitch(anchor), 50, send);
            } else {
                send.run();
            }
        }

        // -------------------- MINING --------------------

        private void tickMining() {
            // autoSwitch 模式下：如果玩家手动切了槽（如切武器打怪），abort 本次挖掘
            if (lockedToolSlot != -1 && mc.player.getInventory().selectedSlot != lockedToolSlot) {
                phase = Phase.ABORTING;
                return;
            }

            // 按当前手持槽位计算本 tick 的 block damage
            int effectiveSlot = lockedToolSlot != -1
                ? lockedToolSlot
                : mc.player.getInventory().selectedSlot;
            double delta = BlockUtils.getBreakDelta(effectiveSlot, blockState);
            progress += delta;
            elapsedTicks++;

            // 记录首次达到 progress >= 1.0 的 tick
            if (readyTick == -1 && progress >= 1.0) {
                readyTick = elapsedTicks;
            }

            // 心跳挥手（仅安静 tick 时发，避免和 use/interact 冲突）
            if (heartbeatSwing.get() && isQuietTick()) {
                heartbeatTimer++;
                if (heartbeatTimer >= heartbeatInterval.get()) {
                    sendSwing();
                    heartbeatTimer = 0;
                }
            }

            // 在关键里程碑前更新 currentFace
            if (progress >= 1.0) {
                refreshCurrentFace();
            }

            // 判断是否可以发 STOP
            if (isReadyToStop()) {
                phase = Phase.PENDING_STOP;
                clearRotationState();
                tickPendingStop();
            }
        }

        private boolean isReadyToStop() {
            if (progress < 1.0) return false;
            // strictMargin: 首次达到 ready 后多等 1 tick
            if (strictMargin.get()) return elapsedTicks > readyTick;
            return true;
        }

        // -------------------- PENDING_STOP --------------------

        private void tickPendingStop() {
            if (!isQuietTick()) return;

            // 发 STOP 前再次校准 face 和工具
            refreshCurrentFace();
            ensureTaskToolSelected(MyBlock.this);

            Runnable send = () -> {
                if (phase != Phase.PENDING_STOP) { clearRotationState(); return; }
                ensureTaskToolSelected(MyBlock.this);
                sendSwing();
                sendStopPacket(blockPos, currentFace);
                lastFinishMs = System.currentTimeMillis();
                phase = Phase.FINISHED;
                clearRotationState();
            };

            if (rotateOnStop.get()) {
                if (rotationQueued) return;
                rotationQueued = true;
                rotationPhaseToken = Phase.PENDING_STOP;
                Vec3d anchor = getFaceAnchor(blockPos, currentFace);
                Rotations.rotate(Rotations.getYaw(anchor), Rotations.getPitch(anchor), 50, send);
            } else {
                send.run();
            }
        }

        // -------------------- ABORTING --------------------

        private void tickAborting() {
            if (!mining) {
                // 未发过 START，直接本地清理
                phase = Phase.FINISHED;
                return;
            }

            if (!isQuietTick()) return;

            // 中止前校准 face 和工具
            refreshCurrentFace();
            ensureTaskToolSelected(MyBlock.this);

            Runnable send = () -> {
                if (phase != Phase.ABORTING) { clearRotationState(); return; }
                ensureTaskToolSelected(MyBlock.this);
                sendAbortPacket(blockPos, currentFace);
                phase = Phase.FINISHED;
                clearRotationState();
            };

            if (rotateOnAbort.get()) {
                if (rotationQueued) return;
                rotationQueued = true;
                rotationPhaseToken = Phase.ABORTING;
                Vec3d anchor = getFaceAnchor(blockPos, currentFace);
                Rotations.rotate(Rotations.getYaw(anchor), Rotations.getPitch(anchor), 50, send);
            } else {
                send.run();
            }
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
