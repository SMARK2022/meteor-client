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
import meteordevelopment.meteorclient.renderer.GL;
import meteordevelopment.meteorclient.events.entity.player.SendMovementPacketsEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ItemSwitchHelper;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.printer.ActionPlan;
import meteordevelopment.meteorclient.utils.printer.ActionPlan.SneakPolicy;
import meteordevelopment.meteorclient.utils.printer.ActionPlan.HandPolicy;
import meteordevelopment.meteorclient.utils.printer.BlockPlacementBehavior;
import meteordevelopment.meteorclient.utils.printer.BlockUtilHelper;
import meteordevelopment.meteorclient.utils.printer.ComparatorModeBehavior;
import meteordevelopment.meteorclient.utils.printer.PrinterBehavior;
import meteordevelopment.meteorclient.utils.printer.PrinterTask;
import meteordevelopment.meteorclient.utils.printer.RedstoneDotCrossBehavior;
import meteordevelopment.meteorclient.utils.printer.RepeaterDelayBehavior;
import meteordevelopment.meteorclient.utils.printer.WaterBehavior;
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

    // Behavior Toggles
    private final Setting<Boolean> fixRedstoneState = sgGeneral.add(new BoolSetting.Builder()
            .name("fix-redstone-state")
            .description("Fix redstone component states: repeater delay, comparator mode, wire dot/cross.")
            .defaultValue(false)
            .build());

    private final Setting<Boolean> placeWater = sgGeneral.add(new BoolSetting.Builder()
            .name("place-water")
            .description("Place water/lava source blocks from buckets.")
            .defaultValue(false)
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

    // ==================== 动作计划快照 ====================

    /**
     * ArmedAction - 已就绪的动作（含计划和确认的手）
     * 在 TickEvent.Pre 中创建，在 SendMovementPacketsEvent.Post 中消费。
     */
    private record ArmedAction(ActionPlan plan, Hand hand) {}

    /**
     * 潜行准备结果
     */
    private enum SneakReadiness { READY, PREPARING, BLOCKED }

    // ==================== 内部状态 ====================

    /** 当前 tick 需要处理的任务列表 */
    private final List<PrinterTask> tasks = new ArrayList<>();
    private int tickDelay = 0;

    /** 当前 tick 的动作计划（逻辑态） */
    private ArmedAction armed = null;

    /** 记录当前潜行状态是否由打印机强制触发 */
    private boolean didPrinterForceSneak = false;

    // 渲染态（与逻辑态分离，确保 hit 点稳定显示 1~2 tick）
    private ActionPlan lastPlan = null;
    private Vec3d lastHitVec = null;
    private int lastHitVecTicks = 0;

    public Printer() {
        super(Categories.Player, "printer", "Automatically places blocks based on Litematica schematic.");
    }

    @Override
    public void onActivate() {
        tickDelay = 0;
        tasks.clear();
        clearArmed();
    }

    @Override
    public void onDeactivate() {
        tickDelay = 0;
        tasks.clear();
        clearArmed();
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
     * 判断行为是否被用户启用
     * BlockPlacementBehavior 始终启用，红石类行为受 fixRedstoneState 开关控制，
     * 流体行为受 placeWater 开关控制。
     */
    private boolean isBehaviorEnabled(PrinterBehavior behavior) {
        if (behavior instanceof BlockPlacementBehavior) return true;
        if (behavior instanceof RepeaterDelayBehavior) return fixRedstoneState.get();
        if (behavior instanceof ComparatorModeBehavior) return fixRedstoneState.get();
        if (behavior instanceof RedstoneDotCrossBehavior) return fixRedstoneState.get();
        if (behavior instanceof WaterBehavior) return placeWater.get();
        return false;
    }

    /**
     * 为任务查找第一个已启用且匹配的行为
     * 将行为匹配与启用判断合并，避免被禁用的特化行为挡住通用 fallback。
     */
    private PrinterBehavior findEnabledBehavior(PrinterTask task) {
        for (PrinterBehavior behavior : PrinterBehavior.REGISTRY) {
            if (!isBehaviorEnabled(behavior)) continue;
            if (behavior.supports(task)) return behavior;
        }
        return null;
    }

    /**
     * 清除当前动作计划和所有相关缓存
     */
    private void clearArmed() {
        armed = null;
        lastPlan = null;
        lastHitVec = null;
        lastHitVecTicks = 0;
    }

    /**
     * 发布渲染计划快照
     */
    private void publishRenderPlan(ActionPlan plan) {
        lastPlan = plan;
        lastHitVec = plan != null ? plan.interaction().hitVec() : null;
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

        // 如果已有未执行的 plan，跳过
        if (armed != null) return;

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

        // 更新任务列表
        updateTasks(worldSchematic);
        if (tasks.isEmpty()) {
            resetSneakState();
            return;
        }

        // 尝试为最佳任务生成动作计划
        armed = selectBestAction(worldSchematic);

        if (armed != null) {
            // 发布渲染快照
            publishRenderPlan(armed.plan());

            // 通过 Rotations 协调器提交旋转请求
            if (rotate.get()) {
                ActionPlan.Interaction inter = armed.plan().interaction();
                Rotations.requestPreMovement(inter.yaw(), inter.pitch(), 50, null);
            }
        }
    }

    /**
     * 选择最佳动作计划（两阶段优先级版）
     *
     * 改进点（相对旧版）：
     * 1. 扫描阶段只生成 Task，不做 resolve（避免双重 resolve）
     * 2. 由 Behavior 统一生成 ActionPlan（支持 PlaceBlock、UseBlock 等多种动作）
     * 3. 两阶段选择：先找"已就绪"的，再找"需要准备"的（不再因 sneak 切换浪费整 tick）
     * 4. SneakPolicy 三态：对 UseBlock 正确处理"必须不潜行"
     */
    private ArmedAction selectBestAction(WorldSchematic worldSchematic) {
        // 如果本 tick 刚做过背包→热栏转移，跳过
        if (ItemSwitchHelper.didInventoryTransferThisTick()) {
            ItemSwitchHelper.resetTransferFlag();
            return null;
        }

        boolean strict = placeMode.get() == PlaceMode.STRICT;
        boolean checkLos = strict && checkLineOfSight.get();
        double maxReach = placeRange.get();
        float renderYaw = mc.player.getYaw();
        Vec3d eyePos = mc.player.getEyePos();

        // Phase 1: 对任务调用 Behavior 生成计划并评分（无副作用）
        record ScoredCandidate(ActionPlan plan, double score) {}

        List<ScoredCandidate> scored = new ArrayList<>();
        int maxCandidates = 30; // 限制计划数量，控制性能
        int planned = 0;

        for (PrinterTask task : tasks) {
            if (planned >= maxCandidates) break;

            PrinterBehavior behavior = findEnabledBehavior(task);
            if (behavior == null) continue;

            ActionPlan plan = behavior.plan(task, mc, strict, checkLos, maxReach);
            if (plan == null) continue;
            planned++;

            ActionPlan.Interaction inter = plan.interaction();
            Vec3d hitVec = inter.hitVec();
            double dist2 = eyePos.squaredDistanceTo(hitVec);

            // 计算 yaw 差
            float yawDelta = Math.abs(MathHelper.wrapDegrees(inter.yaw() - renderYaw));

            // sneak / 物品切换成本
            double sneakCost = switch (plan.sneakPolicy()) {
                case KEEP_CURRENT -> 0.0;
                case REQUIRE_SNEAK -> 1.5;
                case REQUIRE_NOT_SNEAK -> 0.3;
            };

            boolean needsItemSwitch = plan.requiredItem() != null
                && plan.handPolicy() != HandPolicy.PREFER_MAIN_NO_SWITCH
                && mc.player.getMainHandStack().getItem() != plan.requiredItem()
                && mc.player.getOffHandStack().getItem() != plan.requiredItem();

            // reach 边界风险
            double reachDist = eyePos.distanceTo(hitVec);
            boolean nearReachEdge = reachDist > maxReach * 0.85;

            // 综合评分 (越低越好)
            double score = dist2 * 1.0
                + yawDelta * 0.08
                + sneakCost
                + (needsItemSwitch ? 1.0 : 0.0)
                + (nearReachEdge ? 1.2 : 0.0);

            scored.add(new ScoredCandidate(plan, score));
        }

        scored.sort(Comparator.comparingDouble(ScoredCandidate::score));

        // Phase 2: 先找"已就绪"的候选（无副作用，本 tick 立即执行）
        for (ScoredCandidate c : scored) {
            Hand hand = getReadyHand(c.plan);
            if (hand != null && isSneakStateOk(c.plan.sneakPolicy())) {
                return new ArmedAction(c.plan, hand);
            }
        }

        // Phase 3: 找"需要准备"的候选（有副作用：切物品/切潜行）
        for (ScoredCandidate c : scored) {
            Hand hand = ensureItemAndGetHand(c.plan);
            if (hand == null) continue;

            SneakReadiness sr = prepareSneakState(c.plan.sneakPolicy());
            if (sr == SneakReadiness.READY) return new ArmedAction(c.plan, hand);
            if (sr == SneakReadiness.PREPARING) return null; // 等下一 tick 同步
            // BLOCKED: 用户手动潜行，跳过这个候选，尝试下一个
        }

        return null;
    }

    // ==================== 手部与潜行就绪检查 ====================

    /**
     * 检查当前手是否已经持有目标物品（纯检查，无副作用）
     */
    private Hand getReadyHand(ActionPlan plan) {
        if (plan.handPolicy() == HandPolicy.PREFER_MAIN_NO_SWITCH) return Hand.MAIN_HAND;
        Item item = plan.requiredItem();
        if (item == null) return Hand.MAIN_HAND;
        return switch (plan.handPolicy()) {
            case PREFER_MAIN_NO_SWITCH -> Hand.MAIN_HAND;
            case ANY_HAND_WITH_ITEM -> {
                if (mc.player.getMainHandStack().getItem() == item) yield Hand.MAIN_HAND;
                if (mc.player.getOffHandStack().getItem() == item) yield Hand.OFF_HAND;
                yield null;
            }
            case REQUIRE_MAIN_HAND ->
                mc.player.getMainHandStack().getItem() == item ? Hand.MAIN_HAND : null;
            case REQUIRE_OFF_HAND ->
                mc.player.getOffHandStack().getItem() == item ? Hand.OFF_HAND : null;
        };
    }

    /**
     * 尝试确保物品在手中（可能有切换副作用）
     */
    private Hand ensureItemAndGetHand(ActionPlan plan) {
        Hand ready = getReadyHand(plan);
        if (ready != null) return ready;

        // 尝试切换
        Item item = plan.requiredItem();
        if (item == null) return null;
        if (plan.handPolicy() == HandPolicy.REQUIRE_OFF_HAND) return null;

        if (ItemSwitchHelper.switchToItem(item, true, false)
            && ItemSwitchHelper.isItemInMainHand(item)) {
            return Hand.MAIN_HAND;
        }
        return null;
    }

    /**
     * 纯检查当前潜行状态是否满足策略（无副作用）
     */
    private boolean isSneakStateOk(SneakPolicy policy) {
        return switch (policy) {
            case KEEP_CURRENT -> true;
            case REQUIRE_SNEAK -> mc.player.isSneaking();
            case REQUIRE_NOT_SNEAK -> !mc.player.isSneaking();
        };
    }

    /**
     * 准备潜行状态（可能有副作用）
     *
     * @return READY=已满足, PREPARING=刚切换需等待同步, BLOCKED=用户手动潜行无法覆盖
     */
    private SneakReadiness prepareSneakState(SneakPolicy policy) {
        return switch (policy) {
            case KEEP_CURRENT -> SneakReadiness.READY;
            case REQUIRE_SNEAK -> {
                if (mc.player.isSneaking()) yield SneakReadiness.READY;
                mc.options.sneakKey.setPressed(true);
                didPrinterForceSneak = true;
                yield SneakReadiness.PREPARING;
            }
            case REQUIRE_NOT_SNEAK -> {
                if (!mc.player.isSneaking()) yield SneakReadiness.READY;
                if (didPrinterForceSneak) {
                    mc.options.sneakKey.setPressed(false);
                    didPrinterForceSneak = false;
                    yield SneakReadiness.PREPARING;
                }
                // 用户手动潜行，不擅自改玩家输入
                yield SneakReadiness.BLOCKED;
            }
        };
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
        if (armed != null && mc.player != null) {
            if (isPlanStillValid(armed)) {
                executePlan(armed);
            }
            armed = null;
        }
    }

    /**
     * 验证动作计划在执行时是否仍然有效
     * 使用当前 eyePos 重新验证几何条件，防止 movement 后 plan 过期
     */
    private boolean isPlanStillValid(ArmedAction armed) {
        if (mc.player == null || mc.world == null) return false;

        ActionPlan plan = armed.plan();
        Hand hand = armed.hand();
        ActionPlan.Interaction inter = plan.interaction();
        Vec3d currentEye = mc.player.getEyePos();

        // 1. 通用几何验证：Reach
        if (currentEye.distanceTo(inter.hitVec()) > placeRange.get() + 0.1) return false;

        // 2. 通用几何验证：NCP + LOS (STRICT mode)
        if (placeMode.get() == PlaceMode.STRICT) {
            if (!BlockUtilHelper.getPlaceDirectionsNCP(currentEye, inter.hitVec())
                    .contains(inter.clickedFace())) {
                return false;
            }
            // LOS: PlaceBlock 和 UseItemOnBlock 需要 targetPos 豁免，UseBlock 不需要
            BlockPos losTarget = (plan instanceof ActionPlan.PlaceBlock || plan instanceof ActionPlan.UseItemOnBlock)
                ? plan.targetPos() : null;
            if (checkLineOfSight.get()
                && !BlockUtilHelper.canSeeFacePoint(
                    inter.interactPos(), inter.clickedFace(), inter.hitVec(),
                    mc.world, mc.player, losTarget)) {
                return false;
            }
        }

        // 3. 通用：潜行条件
        switch (plan.sneakPolicy()) {
            case REQUIRE_SNEAK -> { if (!mc.player.isSneaking()) return false; }
            case REQUIRE_NOT_SNEAK -> { if (mc.player.isSneaking()) return false; }
            case KEEP_CURRENT -> {}
        }

        // 4. 类型特定验证
        return switch (plan) {
            case ActionPlan.PlaceBlock pb -> isPlaceBlockStillValid(pb, hand);
            case ActionPlan.UseBlock ub -> isUseBlockStillValid(ub, hand);
            case ActionPlan.UseItemOnBlock ui -> isUseItemOnBlockStillValid(ui, hand);
        };
    }

    private boolean isPlaceBlockStillValid(ActionPlan.PlaceBlock plan, Hand hand) {
        ActionPlan.Interaction inter = plan.interaction();

        // 目标不再需要放置
        BlockState current = mc.world.getBlockState(plan.targetPos());
        if (current.getBlock() == plan.desiredState().getBlock()) {
            if (current.getBlock() instanceof SlabBlock
                && current.contains(SlabBlock.TYPE)
                && plan.desiredState().contains(SlabBlock.TYPE)) {
                SlabType currentType = current.get(SlabBlock.TYPE);
                SlabType requiredType = plan.desiredState().get(SlabBlock.TYPE);
                if (!(requiredType == SlabType.DOUBLE && currentType != SlabType.DOUBLE)) {
                    return false; // 已满足
                }
            } else {
                return false; // 已满足
            }
        }

        // 交互块仍然可用
        if (!inter.selfInteraction()) {
            if (!BlockUtilHelper.isClickable(mc.world.getBlockState(inter.interactPos()), mc.world, inter.interactPos()))
                return false;
        } else {
            BlockState cs = mc.world.getBlockState(plan.targetPos());
            if (!(cs.getBlock() instanceof SlabBlock)) return false;
            if (!cs.contains(SlabBlock.TYPE)) return false;
            if (cs.get(SlabBlock.TYPE) == SlabType.DOUBLE) return false;
        }

        // 手里还是目标物品
        Item inHand = (hand == Hand.MAIN_HAND)
            ? mc.player.getMainHandStack().getItem()
            : mc.player.getOffHandStack().getItem();
        return inHand == plan.requiredItem();
    }

    private boolean isUseBlockStillValid(ActionPlan.UseBlock plan, Hand hand) {
        BlockState current = mc.world.getBlockState(plan.targetPos());

        // stale-plan 检查：行为自定义的"仍需执行"谓词
        if (!plan.stillNeedsAction().test(current)) return false;

        // 方块还在且类型对
        if (current.getBlock() != plan.desiredState().getBlock()) return false;

        // 方块仍然可点击
        if (!BlockUtilHelper.isClickable(current, mc.world, plan.targetPos())) return false;

        // 如果需要特定物品，检查手中
        if (plan.requiredItem() != null) {
            Item inHand = (hand == Hand.MAIN_HAND)
                ? mc.player.getMainHandStack().getItem()
                : mc.player.getOffHandStack().getItem();
            if (inHand != plan.requiredItem()) return false;
        }

        return true;
    }

    private boolean isUseItemOnBlockStillValid(ActionPlan.UseItemOnBlock plan, Hand hand) {
        BlockState current = mc.world.getBlockState(plan.targetPos());

        // stale-plan 检查
        if (!plan.stillNeedsAction().test(current)) return false;

        // 交互块仍可用
        ActionPlan.Interaction inter = plan.interaction();
        if (!inter.selfInteraction()) {
            if (!BlockUtilHelper.isClickable(mc.world.getBlockState(inter.interactPos()), mc.world, inter.interactPos()))
                return false;
        }

        // 手里还是目标物品
        Item inHand = (hand == Hand.MAIN_HAND)
            ? mc.player.getMainHandStack().getItem()
            : mc.player.getOffHandStack().getItem();
        return inHand == plan.requiredItem();
    }

    /**
     * 执行动作计划
     * PlaceBlock、UseBlock、UseItemOnBlock 在 Minecraft 协议层面都走 interactBlock。
     */
    private void executePlan(ArmedAction armed) {
        ActionPlan.Interaction inter = armed.plan().interaction();
        BlockHitResult hitResult = new BlockHitResult(
            inter.hitVec(), inter.clickedFace(), inter.interactPos(), false);
        ActionResult result = mc.interactionManager.interactBlock(
            mc.player, armed.hand(), hitResult);

        if (result.isAccepted()) {
            if (swingHand.get()) {
                mc.player.swingHand(armed.hand());
            } else {
                mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(armed.hand()));
            }
        }
    }

    /**
     * 更新任务列表：扫描蓝图与世界差异
     *
     * 改进点（相对旧版 updatePlacePositions）：
     * 1. 不再维护 placePositions + placeItems 两个平行容器，改用 PrinterTask 列表
     * 2. 不再在扫描阶段做 ResolverRegistry.canPlace（避免双重 resolve）
     * 3. "已满足"的判断交给 PrinterBehavior.isSatisfied（支持状态修正类任务）
     * 4. 同 block 但状态不对的情况不再被跳过（如 repeater delay 不一致）
     */
    private void updateTasks(WorldSchematic worldSchematic) {
        tasks.clear();

        if (mc.player == null || mc.world == null) return;

        Vec3d playerPos = mc.player.getEyePos();
        double range = placeRange.get();
        double preSelectionRange = range + 1.0;

        List<BlockPos> sphere = getSphere(preSelectionRange, playerPos);

        for (BlockPos pos : sphere) {
            if (!DataManager.getRenderLayerRange().isPositionWithinRange(pos)) continue;

            BlockState requiredState = worldSchematic.getBlockState(pos);
            BlockState currentState = mc.world.getBlockState(pos);

            // 蓝图要求空气，跳过
            if (requiredState.isAir()) continue;

            PrinterTask task = new PrinterTask(pos, requiredState, currentState);

            // 查找匹配的已启用行为
            PrinterBehavior behavior = findEnabledBehavior(task);
            if (behavior == null) continue;

            // 已经满足？
            if (behavior.isSatisfied(task)) continue;

            // 实体阻挡检查（仅对放置类行为有意义）
            if (behavior instanceof BlockPlacementBehavior && hasBlockingEntity(pos)) continue;

            tasks.add(task);
        }

        // 按距离排序（最近的优先）
        tasks.sort(Comparator.comparingDouble(
            task -> mc.player.getEyePos().squaredDistanceTo(
                task.pos().getX() + 0.5, task.pos().getY() + 0.5, task.pos().getZ() + 0.5)));
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
        for (PrinterTask task : tasks) {
            if (lastPlan != null && task.pos().equals(lastPlan.targetPos())) continue;
            event.renderer.box(task.pos(), sideColor.get(), lineColor.get(), shapeMode.get(), 0);
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

        // 第二层：禁用深度测试，绘制30%不透明度的立方体（始终可见）
        GL.disableDepth();
        event.renderer.box(box, color30, color30, ShapeMode.Both, 0);
        GL.enableDepth();
    }

    @Override
    public String getInfoString() {
        if (armed != null) {
            return "ARMED (" + tasks.size() + ")";
        }
        return String.valueOf(tasks.size());
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
