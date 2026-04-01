/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 *
 * Printer module - Auto places blocks based on Litematica schematic.
 *
 * 两阶段架构 (旋转由 Rotations 协调器统一管理)：
 * 1. TickEvent.Pre: 规划（选块、切物品、sneak、生成 PlacementPlan、提交旋转请求）
 * 2. SendMovementPacketsEvent.Post: 执行放置（movement 包发出后立即 place）
 *
 * Rotations 协调器在 PlayerTickMovementEvent 中预应用角度，确保
 * movement 物理、movement packet、place packet 使用同一拍同一个角度。
 */

package meteordevelopment.meteorclient.systems.modules.player;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.ItemSwitchHelper;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.meteorclient.utils.printer.BlockUtilHelper;
import meteordevelopment.meteorclient.utils.printer.PlacementContext;
import meteordevelopment.meteorclient.utils.printer.PlacementOption;
import meteordevelopment.meteorclient.utils.printer.ResolverRegistry;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.block.enums.SlabType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;

import java.util.*;

/**
 * Printer Module - Automatically places blocks based on Litematica schematic.
 *
 * 事件执行顺序：
 * 1. TickEvent.Pre (MinecraftClient.tick HEAD) → 规划阶段 + 提交旋转请求
 * 2. Rotations.onPlayerTickMovement → 自动预应用 yaw/pitch (由协调器处理)
 * 3. sendMovementPackets → vanilla movement packet（带有正确的 yaw/pitch）
 * 4. SendMovementPacketsEvent.Post (sendMovementPackets TAIL) → 执行放置
 *    (Rotations 协调器在此之后自动恢复视角)
 *
 * 这样 movement 物理、movement packet、place packet 使用同一个角度。
 */
public class Printer extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General Settings
    private final Setting<Boolean> moveStop = sgGeneral.add(new BoolSetting.Builder()
            .name("move-stop")
            .description("Stop placing blocks while moving.")
            .defaultValue(false)
            .build());

    private final Setting<PlaceMode> placeMode = sgGeneral.add(new EnumSetting.Builder<PlaceMode>()
            .name("place-mode")
            .description("The method used to place blocks. STRICT uses NCP direction checks for anti-cheat bypass.")
            .defaultValue(PlaceMode.STRICT)
            .build());

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
            .name("place-delay")
            .description("Delay in ticks between placing blocks.")
            .defaultValue(0)
            .min(0)
            .sliderRange(0, 10)
            .build());

    private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
            .name("range")
            .description("The range within which to place blocks (float).")
            .defaultValue(4.5)
            .min(1.0)
            .max(15.0)
            .sliderRange(1.0, 8.0)
            .build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
            .name("rotate")
            .description("Rotates towards the block being placed.")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> swingHand = sgGeneral.add(new BoolSetting.Builder()
            .name("swing-hand")
            .description("Swing hand when placing blocks.")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> checkLineOfSight = sgGeneral.add(new BoolSetting.Builder()
            .name("check-line-of-sight")
            .description("Only place blocks that are visible to the player (anti-cheat).")
            .defaultValue(true)
            .visible(() -> placeMode.get() == PlaceMode.STRICT)
            .build());

    // Render Settings
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
            .name("render")
            .description("Renders blocks that are about to be placed.")
            .defaultValue(true)
            .build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
            .name("shape-mode")
            .description("How the shapes are rendered.")
            .defaultValue(ShapeMode.Both)
            .build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
            .name("side-color")
            .description("The side color of the rendering.")
            .defaultValue(new SettingColor(20, 200, 20, 50))
            .build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
            .name("line-color")
            .description("The line color of the rendering.")
            .defaultValue(new SettingColor(20, 200, 20, 255))
            .build());

    private final Setting<SettingColor> plannedSideColor = sgRender.add(new ColorSetting.Builder()
            .name("planned-side-color")
            .description("The side color of the currently planned placement target.")
            .defaultValue(new SettingColor(20, 200, 255, 80))
            .build());

    private final Setting<SettingColor> plannedLineColor = sgRender.add(new ColorSetting.Builder()
            .name("planned-line-color")
            .description("The line color of the currently planned placement target.")
            .defaultValue(new SettingColor(20, 200, 255, 255))
            .build());

    private final Setting<Boolean> renderHitVec = sgRender.add(new BoolSetting.Builder()
            .name("render-hit-vec")
            .description("Renders a cube at the block placement hit point for debugging.")
            .defaultValue(true)
            .build());

    private final Setting<SettingColor> hitVecColor = sgRender.add(new ColorSetting.Builder()
            .name("hit-vec-color")
            .description("The color of the hit point cube.")
            .defaultValue(new SettingColor(255, 100, 100, 255))
            .build());

    // ==================== 方案B: 不可变放置计划 ====================

    /**
     * PlacementPlan - 一次放置操作的完整不可变快照
     * 在 TickEvent.Pre 阶段创建后，整个 tick 内不再重新 resolve。
     * 确保 movement 物理、movement packet、place packet 使用完全一致的参数。
     */
    private record PlacementPlan(
        BlockPos targetPos,
        BlockPos interactPos,
        Direction clickedFace,
        Vec3d hitVec,
        float yaw,
        float pitch,
        Item item,
        Hand hand,
        BlockState requiredState,
        Vec3d plannedEyePos,
        boolean needsSneak,
        boolean selfPlacement
    ) {}

    // ==================== 内部状态 ====================

    private final List<BlockPos> placePositions = new ArrayList<>();
    private final Map<BlockPos, Item> placeItems = new HashMap<>();
    private int tickDelay = 0;

    // 方案B核心：当前 tick 的放置计划（逻辑态）
    private PlacementPlan armedPlan = null;

    // 标记变量：记录当前潜行状态是否由打印机强制触发
    private boolean didPrinterForceSneak = false;

    // 渲染态（与逻辑态分离，确保 hit 点稳定显示 1~2 tick）
    private PlacementPlan lastPlan = null;
    private Vec3d lastHitVec = null;
    private int lastHitVecTicks = 0;

    public Printer() {
        super(Categories.Player, "printer", "Automatically places blocks based on Litematica schematic.");
    }

    @Override
    public void onActivate() {
        tickDelay = 0;
        placePositions.clear();
        placeItems.clear();
        clearPlan();
    }

    @Override
    public void onDeactivate() {
        tickDelay = 0;
        placePositions.clear();
        placeItems.clear();
        clearPlan();
        resetSneakState();
    }

    /**
     * 安全重置潜行状态
     * 只有当潜行是由打印机强制开启时，才将其关闭。
     */
    private void resetSneakState() {
        if (didPrinterForceSneak) {
            if (mc.options != null) {
                mc.options.sneakKey.setPressed(false);
            }
            didPrinterForceSneak = false;
        }
    }

    /**
     * 清除当前放置计划和所有相关缓存
     */
    private void clearPlan() {
        armedPlan = null;
        lastPlan = null;
        lastHitVec = null;
        lastHitVecTicks = 0;
    }

    /**
     * 发布渲染计划快照
     * 将逻辑态的 plan 复制到渲染态，并设置显示倒计时。
     */
    private void publishRenderPlan(PlacementPlan plan) {
        lastPlan = plan;
        lastHitVec = plan != null ? plan.hitVec() : null;
        lastHitVecTicks = plan != null ? 2 : 0;
    }

    /**
     * 每 tick 衰减渲染态倒计时
     * 归零后清空渲染快照，避免残影。
     */
    private void tickRenderState() {
        if (lastHitVecTicks > 0) {
            lastHitVecTicks--;
            if (lastHitVecTicks <= 0) {
                lastPlan = null;
                lastHitVec = null;
            }
        }
    }

    // ==================== 阶段 1: TickEvent.Pre 规划 ====================

    /**
     * 【方案B - 阶段1】在 tick 最早期进行规划
     *
     * 执行顺序：MinecraftClient.tick() HEAD
     * 此时还没有执行 movement 物理，也没有发送 movement packet。
     *
     * 在此阶段：
     * 1. 更新可放置方块列表
     * 2. 选择最佳目标
     * 3. 切换物品
     * 4. 处理潜行
     * 5. Resolve 一次，生成不可变的 PlacementPlan
     *
     * 如果物品/潜行需要等待同步，本 tick 不生成 plan，下一 tick 再尝试。
     */
    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        if (!isActive() || mc.player == null || mc.world == null) return;

        // 每 tick 衰减渲染态倒计时
        tickRenderState();

        // 如果已有未执行的 plan，跳过（不应该发生，但防御性检查）
        if (armedPlan != null) return;

        // 移动中暂停
        if (moveStop.get() && isPlayerMoving()) return;

        // 检查 Litematica 原理图
        WorldSchematic worldSchematic = SchematicWorldHandler.getSchematicWorld();
        if (worldSchematic == null) {
            if (isActive()) {
                error("Litematica schematic not loaded.");
                toggle();
            }
            return;
        }

        // 处理延迟
        if (tickDelay < placeDelay.get()) {
            tickDelay++;
            return;
        }
        tickDelay = 0;

        // 更新候选列表
        updatePlacePositions(worldSchematic);
        if (placePositions.isEmpty()) {
            resetSneakState();
            return;
        }

        // 尝试为列表中最近的可行方块生成 plan
        armedPlan = selectBestPlan(worldSchematic);

        if (armedPlan != null) {
            // 发布渲染快照（与逻辑态分离，确保 hit 点稳定显示）
            publishRenderPlan(armedPlan);

            // 通过 Rotations 协调器提交旋转请求 (priority=50 for block placement)
            // Rotations 会在 PlayerTickMovementEvent 中预应用角度,
            // 并在 SendMovementPacketsEvent.Post 后自动恢复视角
            if (rotate.get()) {
                Rotations.requestPreMovement(armedPlan.yaw(), armedPlan.pitch(), 50, null);
            }
        }
    }

    /**
     * 选择最佳放置计划（舒适度评分版）
     *
     * 不再使用"第一个最近可用"策略，而是：
     * 1. 对所有候选 resolve，计算 hitVec / yaw
     * 2. 综合距离、yaw 差、sneak 成本、物品切换成本、reach 边界风险打分
     * 3. 选分数最低的候选尝试执行
     *
     * 这样打印机会优先选择"放起来最舒服"的块，而不是"最近的块"。
     */
    private PlacementPlan selectBestPlan(WorldSchematic worldSchematic) {
        // 如果本 tick 刚做过背包→热栏转移，跳过（等待 1 tick 同步）
        if (ItemSwitchHelper.didInventoryTransferThisTick()) {
            ItemSwitchHelper.resetTransferFlag();
            return null;
        }

        // Phase 1: 对所有候选做纯计算评分（无副作用）
        record ScoredCandidate(BlockPos pos, Item item, BlockState requiredState,
                               PlacementOption option, boolean needsSneak, double score) {}

        List<ScoredCandidate> scored = new ArrayList<>();
        float renderYaw = mc.player.getYaw();
        Vec3d eyePos = mc.player.getEyePos();
        double maxReach = placeRange.get();

        for (BlockPos pos : placePositions) {
            Item item = placeItems.get(pos);
            BlockState requiredState = worldSchematic.getBlockState(pos);
            if (item == null || requiredState == null) continue;

            // 解析放置方向（只 resolve 一次）
            PlacementOption option = resolvePlacement(pos, requiredState);
            if (option == null || option.hitVec() == null) continue;

            Vec3d hitVec = option.hitVec();
            double dist2 = eyePos.squaredDistanceTo(hitVec);

            // 计算 yaw 差
            float yaw = (float) Rotations.getYaw(hitVec);
            float yawDelta = Math.abs(MathHelper.wrapDegrees(yaw - renderYaw));

            // sneak / 物品切换成本
            BlockPos interactPos = option.getInteractPos(pos);
            BlockState interactState = mc.world.getBlockState(interactPos);
            boolean needsSneak = BlockUtilHelper.SNEAK_BLOCKS.contains(interactState.getBlock());
            boolean needsItemSwitch = mc.player.getMainHandStack().getItem() != item
                && mc.player.getOffHandStack().getItem() != item;

            // reach 边界风险
            double reachDist = eyePos.distanceTo(hitVec);
            boolean nearReachEdge = reachDist > maxReach * 0.85;

            // 综合评分 (越低越好)
            double score = dist2 * 1.0
                + yawDelta * 0.08
                + (needsSneak ? 1.5 : 0.0)
                + (needsItemSwitch ? 1.0 : 0.0)
                + (nearReachEdge ? 1.2 : 0.0);

            scored.add(new ScoredCandidate(pos, item, requiredState, option, needsSneak, score));
        }

        // Phase 2: 按评分排序，依次尝试执行（物品/潜行有副作用）
        scored.sort(Comparator.comparingDouble(ScoredCandidate::score));

        for (ScoredCandidate candidate : scored) {
            // 尝试确保物品就绪
            Hand hand = ensureItemReadyAndGetHand(candidate.item());
            if (hand == null) continue;

            // 尝试确保潜行状态就绪
            if (!ensureSneakReady(candidate.needsSneak())) {
                // 潜行状态刚切换，本 tick 不放（等服务端同步）
                return null;
            }

            // 物品和潜行都就绪，生成不可变的 plan
            Vec3d hitVec = candidate.option().hitVec();
            float yaw = (float) Rotations.getYaw(hitVec);
            float pitch = (float) Rotations.getPitch(hitVec);
            BlockPos interactPos = candidate.option().getInteractPos(candidate.pos());
            boolean selfPlacement = interactPos.equals(candidate.pos());

            return new PlacementPlan(
                candidate.pos(),
                interactPos,
                candidate.option().getClickedFace(),
                hitVec,
                yaw,
                pitch,
                candidate.item(),
                hand,
                candidate.requiredState(),
                mc.player.getEyePos(),
                candidate.needsSneak(),
                selfPlacement
            );
        }
        return null;
    }

    /**
     * 确保目标物品在手中，并返回使用哪只手
     * @return 可用的 Hand，如果无法就绪返回 null
     */
    private Hand ensureItemReadyAndGetHand(Item item) {
        if (mc.player.getMainHandStack().getItem() == item) return Hand.MAIN_HAND;
        if (mc.player.getOffHandStack().getItem() == item) return Hand.OFF_HAND;
        // 尝试切换到主手
        if (ItemSwitchHelper.switchToItem(item, true, false)
            && ItemSwitchHelper.isItemInMainHand(item)) {
            return Hand.MAIN_HAND;
        }
        return null;
    }

    /**
     * 确保潜行状态满足要求
     * @return true 如果潜行状态已经正确（无需等待同步）
     */
    private boolean ensureSneakReady(boolean needsSneak) {
        if (needsSneak && !mc.player.isSneaking()) {
            mc.options.sneakKey.setPressed(true);
            didPrinterForceSneak = true;
            return false; // 刚按下，等下一 tick 同步
        } else if (!needsSneak && didPrinterForceSneak && mc.player.isSneaking()) {
            mc.options.sneakKey.setPressed(false);
            didPrinterForceSneak = false;
            return false; // 刚松开，等下一 tick 同步
        }
        return true; // 状态已满足
    }

    // ==================== 阶段 2: SendMovementPacketsEvent.Post 执行放置 ====================

    /**
     * 【方案B - 阶段2】在 movement packet 发出后立即放置
     *
     * 执行顺序：ClientPlayerEntity.sendMovementPackets() TAIL
     * 此时本 tick 的 movement packet 已经发出（Rotations 已预应用正确的 yaw/pitch），
     * 立即发送 place packet，确保 place 与 movement 在同一 tick 内完成。
     *
     * 旋转的预应用和视角恢复现在由 Rotations 协调器统一管理。
     */
    @EventHandler
    private void onSendMovementPacketsPost(SendMovementPacketsEvent.Post event) {
        if (armedPlan != null && mc.player != null) {
            // 验证 plan 是否仍然有效
            if (isPlanStillValid(armedPlan)) {
                placeBlockInternal(armedPlan);
            }
            armedPlan = null;
            // 渲染态 (lastPlan/lastHitVec) 由 tickRenderState 管理，不在此清除
        }
    }

    /**
     * 验证 PlacementPlan 在执行时是否仍然有效
     * 使用当前 eyePos 重新验证几何条件，防止 movement 后 plan 过期
     */
    private boolean isPlanStillValid(PlacementPlan plan) {
        if (mc.player == null || mc.world == null) return false;

        // 1. 目标仍然需要放置（未被满足/占用）
        if (isTargetAlreadySatisfied(plan)) return false;

        // 2. 交互块仍然可用
        if (!isInteractStillValid(plan)) return false;

        // 3. 手里确实还是这件物品
        if (!isPlanHandStillHoldingItem(plan)) return false;

        // 4. sneak 条件满足
        if (plan.needsSneak() && !mc.player.isSneaking()) return false;

        // 5. 使用当前 eyePos 重新验证几何合法性
        Vec3d currentEye = mc.player.getEyePos();

        // Reach 检查
        if (currentEye.distanceTo(plan.hitVec()) > placeRange.get() + 0.1) return false;

        if (placeMode.get() == PlaceMode.STRICT) {
            // NCP 方向检查
            if (!BlockUtilHelper.getPlaceDirectionsNCP(currentEye, plan.hitVec())
                    .contains(plan.clickedFace())) {
                return false;
            }

            // LOS 检查
            if (checkLineOfSight.get()
                && !BlockUtilHelper.canSeeFacePoint(
                    plan.interactPos(),
                    plan.clickedFace(),
                    plan.hitVec(),
                    mc.world,
                    mc.player,
                    plan.targetPos())) {
                return false;
            }
        }

        return true;
    }

    /**
     * 检查目标位置是否已经被满足（不再需要放置）
     */
    private boolean isTargetAlreadySatisfied(PlacementPlan plan) {
        BlockState current = mc.world.getBlockState(plan.targetPos());
        BlockState required = plan.requiredState();

        // 如果目标已经就是所需状态，说明已经放好了
        if (current.getBlock() == required.getBlock()) {
            // 半砖升级的特殊处理
            if (current.getBlock() instanceof SlabBlock
                && current.contains(SlabBlock.TYPE)
                && required.contains(SlabBlock.TYPE)) {
                SlabType currentType = current.get(SlabBlock.TYPE);
                SlabType requiredType = required.get(SlabBlock.TYPE);
                if (requiredType == SlabType.DOUBLE && currentType != SlabType.DOUBLE) {
                    return false; // 仍然需要升级
                }
            }
            return true; // 已满足
        }
        return false;
    }

    /**
     * 检查交互方块是否仍然可用
     */
    private boolean isInteractStillValid(PlacementPlan plan) {
        BlockState interactState = mc.world.getBlockState(plan.interactPos());

        if (!plan.selfPlacement()) {
            return BlockUtilHelper.isClickable(interactState, mc.world, plan.interactPos());
        }

        // selfPlacement: 当前 target 仍然必须是允许 self 的状态（单层半砖）
        BlockState current = mc.world.getBlockState(plan.targetPos());
        if (!(current.getBlock() instanceof SlabBlock)) return false;
        if (!current.contains(SlabBlock.TYPE)) return false;
        return current.get(SlabBlock.TYPE) != SlabType.DOUBLE;
    }

    /**
     * 检查计划中的手是否仍然持有目标物品
     */
    private boolean isPlanHandStillHoldingItem(PlacementPlan plan) {
        if (plan.hand() == Hand.MAIN_HAND) {
            return mc.player.getMainHandStack().getItem() == plan.item();
        } else {
            return mc.player.getOffHandStack().getItem() == plan.item();
        }
    }

    /**
     * 使用规则引擎解析指定位置的放置选项
     * 只 resolve 一次，生成包含 hitVec 的完整 PlacementOption。
     */
    private PlacementOption resolvePlacement(BlockPos pos, BlockState requiredState) {
        boolean strict = placeMode.get() == PlaceMode.STRICT;
        boolean checkLos = strict && checkLineOfSight.get();
        PlacementContext ctx = PlacementContext.of(mc.world, pos, requiredState, mc.player, strict, checkLos, placeRange.get());
        return ResolverRegistry.resolve(ctx);
    }

    /**
     * 执行方块放置
     * 直接使用 plan 中预计算好的参数，不再重新 resolve。
     *
     * @param plan 完整的放置计划（包含手、位置、面、hitVec）
     */
    private void placeBlockInternal(PlacementPlan plan) {
        BlockHitResult hitResult = new BlockHitResult(plan.hitVec(), plan.clickedFace(), plan.interactPos(), false);
        ActionResult result = mc.interactionManager.interactBlock(mc.player, plan.hand(), hitResult);

        if (result.isAccepted()) {
            if (swingHand.get()) {
                mc.player.swingHand(plan.hand());
            } else {
                mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(plan.hand()));
            }
        }
    }

    /**
     * 更新需要放置方块的位置列表
     * 从Litematica原理图和世界进行对比，找出所有需要放置的方块
     *
     * [改进] 现在使用浮点数范围 + 预选距离+1的逻辑 + Reach 过滤的两层过滤：
     * 1. 初始球形范围：range + 1.0（预选阶段）
     * 2. Reach 过滤：在 Rules 中对 hitVec 进行精确检查
     */
    private void updatePlacePositions(WorldSchematic worldSchematic) {
        placePositions.clear();
        placeItems.clear();

        if (mc.player == null || mc.world == null)
            return;

        Vec3d playerPos = mc.player.getEyePos();
        double range = placeRange.get();

        // 【改进】预选范围 = 配置范围 + 1.0
        // 这样可以提前把接近边界但 reach 不足的方块排除
        double preSelectionRange = range + 1.0;

        // 获取玩家周围球形范围内的所有方块位置
        List<BlockPos> sphere = getSphere(preSelectionRange, playerPos);

        for (BlockPos pos : sphere) {
            // 检查是否在渲染层范围内
            if (!DataManager.getRenderLayerRange().isPositionWithinRange(pos)) {
                continue;
            }

            // 从原理图获取目标方块状态
            BlockState requiredState = worldSchematic.getBlockState(pos);
            // 从世界获取当前方块状态
            BlockState currentState = mc.world.getBlockState(pos);

            // 原理图要求是空气，跳过
            if (requiredState.isAir()) {
                continue;
            }

            // 定义一个标记，用于指示是否为半砖升级操作
            // 如果是升级操作，我们需要绕过后面的 isReplaceable 检查
            boolean isSlabUpgrade = false;

            // 方块已经正确放置（相同类型的方块），跳过
            // [关键修复] 之前的 `getBlock()` 比较无法处理半砖升级 (e.g. BOTTOM -> DOUBLE)
            // 现在，如果 Block 类型相同，我们额外检查半砖状态。
            if (requiredState.getBlock() == currentState.getBlock()) {
                // 如果是半砖，检查是否需要升级
                if (requiredState.getBlock() instanceof SlabBlock &&
                        requiredState.contains(SlabBlock.TYPE) &&
                        currentState.contains(SlabBlock.TYPE)) {

                    SlabType requiredType = requiredState.get(SlabBlock.TYPE);
                    SlabType currentType = currentState.get(SlabBlock.TYPE);

                    // 如果需要双层，但当前不是双层，则允许放置（让 BlockUtil 去处理）
                    if (requiredType == SlabType.DOUBLE && currentType != SlabType.DOUBLE) {
                        isSlabUpgrade = true; // [标记] 这是一个合法的升级操作
                    } else {
                        // 否则，我们认为它已经放置好了
                        continue;
                    }
                } else {
                    // 对于非半砖方块，如果 Block 类型相同，就认为已经放置
                    continue;
                }
            }

            // 只跳过纯水/岩浆，不跳过含水方块
            if (requiredState.getBlock() instanceof FluidBlock) {
                continue;
            }

            // 检查当前位置是否可以被替换
            // 只有空气、流体和可替换方块才能被放置覆盖
            // 如果不是半砖升级操作，则必须检查当前位置是否为空或可替换
            if (!isSlabUpgrade) {
                boolean isCurrentLiquid = currentState.getFluidState() != null
                        && !currentState.getFluidState().isEmpty();
                // 如果当前位置既不是空气，也不是流体，也不可替换（如石头），则跳过
                if (!currentState.isAir() && !isCurrentLiquid && !currentState.isReplaceable()) {
                    continue;
                }
            }

            // 检查是否有实体阻挡
            if (hasBlockingEntity(pos)) {
                continue;
            }

            // 获取该方块对应的物品
            Item item = requiredState.getBlock().asItem();
            if (item == Items.AIR) {
                continue;
            }

            // 检查物品栏中是否有该物品（包括背包）
            // 关键修复：使用 find() 而不是 findInHotbar()，允许背包物品
            if (!InvUtils.find(item).found()) {
                continue;
            }

            // ==================== 模式特定的检查 ====================

            if (placeMode.get() == PlaceMode.LEGIT) {
                // LEGIT模式：使用标准的BlockUtils.canPlace检查
                // 这个方法会检查邻接、碰撞、方块支撑等基础检查
                if (!BlockUtils.canPlace(pos)) {
                    continue;
                }
            } else {
                // STRICT 模式：使用规则引擎检查是否存在合法的放置方向
                // [改进] 规则引擎内部现在包含了 Reach 过滤器，会对 hitVec 进行精确检查
                PlacementContext ctx = PlacementContext.of(mc.world, pos, requiredState, mc.player,
                        true, checkLineOfSight.get(), placeRange.get());

                if (!ResolverRegistry.canPlace(ctx)) {
                    continue;
                }
            }

            // 将该位置加入放置列表
            placePositions.add(pos);
            placeItems.put(pos, item);
        }

        // 按距离排序（最近的优先）
        placePositions.sort(Comparator.comparingDouble(
                pos -> mc.player.getEyePos().squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)));
    }

    /**
     * Gets all block positions in a sphere around the given position.
     * [改进] 现在使用浮点数范围，并支持亚方块级精度
     *
     * @param range 球形搜索范围（浮点数，单位：方块）
     * @param center 球心坐标（眼部位置）
     * @return 范围内的所有方块位置列表
     */
    private List<BlockPos> getSphere(double range, Vec3d center) {
        List<BlockPos> list = new ArrayList<>();

        // 计算扫描范围：向上取整
        int scanRange = (int) Math.ceil(range) + 1;

        for (int x = -scanRange; x <= scanRange; x++) {
            for (int y = -scanRange; y <= scanRange; y++) {
                for (int z = -scanRange; z <= scanRange; z++) {
                    BlockPos pos = BlockPos.ofFloored(center.x + x, center.y + y, center.z + z);
                    double distance = Vec3d.ofCenter(pos).distanceTo(center);

                    // [改进] 使用浮点数范围判断
                    if (distance <= range) {
                        list.add(pos);
                    }
                }
            }
        }

        return list;
    }

    /**
     * Checks if there's an entity blocking placement at the given position.
     * [修复版] 解决了掉落物、经验球、旁观者导致无法放置的问题
     */
    private boolean hasBlockingEntity(BlockPos pos) {
        net.minecraft.util.math.Box box = new net.minecraft.util.math.Box(pos);

        // 性能优化：可以直接在这里传入 Predicate 进行初步过滤
        return !mc.world.getEntitiesByClass(Entity.class, box, entity -> {
            // 1. 基础存活检查
            if (!entity.isAlive())
                return false;

            // 2. 排除旁观者 (旁观者不有碰撞体积)
            if (entity.isSpectator())
                return false;

            // 3. 排除非阻挡性实体
            if (entity instanceof ItemEntity)
                return false; // 掉落物
            if (entity instanceof ExperienceOrbEntity)
                return false; // 经验球
            // if (entity instanceof AbstractMinecartEntity) return false; // (可选)
            // 矿车通常可以重叠放置

            // 4. 排除装饰性实体 (展示框、画等)
            // EndCrystal 和 ArmorStand 有时确实会阻挡，视具体需求而定，原代码排除了它们
            if (entity instanceof ItemFrameEntity)
                return false;
            if (entity instanceof ArmorStandEntity)
                return false;
            if (entity instanceof EndCrystalEntity)
                return false;

            // 5. 排除与方块无碰撞的实体 (如箭矢)
            // 这一步比较激进，通常 ProjectileEntity 也可以排除
            if (entity instanceof net.minecraft.entity.projectile.ProjectileEntity)
                return false;

            // 剩下的通常是：玩家(Player)、生物(Mobs)、船(Boats) -> 这些应该视为阻挡
            return true;
        }).isEmpty(); // 如果列表不为空，说明存在阻挡实体
    }

    /**
     * Checks if the player is currently moving.
     */
    private boolean isPlayerMoving() {
        return mc.options.forwardKey.isPressed() ||
                mc.options.backKey.isPressed() ||
                mc.options.leftKey.isPressed() ||
                mc.options.rightKey.isPressed();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!isActive() || !render.get()) return;

        // 候选方块（淡绿色，排除当前计划目标）
        for (BlockPos pos : placePositions) {
            if (lastPlan != null && pos.equals(lastPlan.targetPos())) continue;
            event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }

        // 当前计划目标（醒目青色高亮）
        if (lastPlan != null) {
            event.renderer.box(lastPlan.targetPos(), plannedSideColor.get(), plannedLineColor.get(), shapeMode.get(), 0);
        }

        // 交互点（红橙色小立方体）
        if (renderHitVec.get() && lastHitVec != null && lastHitVecTicks > 0) {
            renderHitVecCube(event);
        }
    }

    /**
     * 渲染交互点立方体
     * 使用两层立方体：
     * - 第一层：70%不透明度的立方体（正常深度测试，可被遮挡）
     * - 第二层：30%不透明度的立方体（禁用深度测试，始终可见）
     */
    private void renderHitVecCube(Render3DEvent event) {
        if (lastHitVec == null)
            return;

        // 立方体的半边长（总边长为0.1，所以每边0.05）
        double halfSize = 0.05;

        // 立方体的中心坐标
        double x = lastHitVec.x;
        double y = lastHitVec.y;
        double z = lastHitVec.z;

        // 获取颜色
        SettingColor color = hitVecColor.get();

        // 获取基础RGBA值
        int r = color.r;
        int g = color.g;
        int b = color.b;

        // 创建两种透明度的颜色
        // 70%不透明度 = 30%透明 ≈ Alpha值 179 (255 * 0.7)
        int alpha70 = Math.round(255 * 0.7f);
        SettingColor color70 = new SettingColor(r, g, b, alpha70);

        // 30%不透明度 = 70%透明 ≈ Alpha值 76 (255 * 0.3)
        int alpha30 = Math.round(255 * 0.3f);
        SettingColor color30 = new SettingColor(r, g, b, alpha30);

        // 创建立方体的碰撞箱
        net.minecraft.util.math.Box box = new net.minecraft.util.math.Box(
                x - halfSize, y - halfSize, z - halfSize,
                x + halfSize, y + halfSize, z + halfSize
        );

        // 第一层：70%不透明度的立方体（正常绘制，会被遮挡）
        event.renderer.box(box, color70, color70, ShapeMode.Both, 0);

        // 第二层：使用 depthRenderer (无深度测试)，绘制30%不透明度的立方体（始终可见）
        event.depthRenderer.box(box, color30, color30, ShapeMode.Both, 0);
    }

    @Override
    public String getInfoString() {
        if (armedPlan != null) {
            return "ARMED (" + placePositions.size() + ")";
        }
        return String.valueOf(placePositions.size());
    }

    /**
     * Placement mode enum
     */
    public enum PlaceMode {
        /**
         * Standard placement mode - uses BlockUtils with full validation
         */
        LEGIT,

        /**
         * Strict placement mode - uses NCP direction checks and line of sight
         * Better for bypassing certain anti-cheat systems
         */
        STRICT
    }
}
