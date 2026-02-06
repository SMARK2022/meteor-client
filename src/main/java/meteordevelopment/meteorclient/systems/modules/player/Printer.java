/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 *
 * Printer module - Auto places blocks based on Litematica schematic.
 * Restored and enhanced from original obfuscated GGboy code.
 *
 * Features:
 * - Two placement modes: STRICT (anti-cheat bypass with NCP direction checks) and LEGIT (standard placement)
 * - Block direction/orientation support for proper placement
 * - Movement pause option
 * - Configurable delay, range, and blocks per tick
 * - Visual rendering of blocks to be placed
 * - Line of sight checking for anti-cheat bypass
 */

package meteordevelopment.meteorclient.systems.modules.player;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.player.Printer.PlaceMode;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.ItemSwitchHelper;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.meteorclient.utils.printer.BlockUtilHelper;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.block.enums.SlabType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;

import java.util.*;

/**
 * Printer Module - Automatically places blocks based on Litematica schematic.
 *
 * This module integrates with the Litematica mod to read schematic data and
 * automatically place blocks to match the schematic. It supports two placement
 * modes for different anti-cheat bypass requirements.
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

    private final Setting<Integer> placeNums = sgGeneral.add(new IntSetting.Builder()
            .name("blocks-per-tick")
            .description("How many blocks to place per tick.")
            .defaultValue(1)
            .min(1)
            .sliderRange(1, 6)
            .build());

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
            .name("place-delay")
            .description("Delay in ticks between placing blocks.")
            .defaultValue(0)
            .min(0)
            .sliderRange(0, 10)
            .build());

    private final Setting<Integer> placeRange = sgGeneral.add(new IntSetting.Builder()
            .name("range")
            .description("The range within which to place blocks.")
            .defaultValue(4)
            .min(1)
            .sliderRange(1, 6)
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

    // Internal state
    private final List<BlockPos> placePositions = new ArrayList<>();
    private final Map<BlockPos, Item> placeItems = new HashMap<>();
    private int tickDelay = 0;

    // 状态机：当前正在处理的方块及其状态
    private BlockPos currentTargetPos = null;
    private Item currentTargetItem = null;
    private BlockState currentTargetState = null;
    private PlacementState placementState = PlacementState.IDLE;
    private int stateTickCounter = 0;
    private boolean currentBlockNeedsSneak = false;
    private boolean currentPlayerIsSneaking = false;

    // 【新增】标记变量：记录当前潜行状态是否由打印机强制触发
    private boolean didPrinterForceSneak = false;

    /**
     * 方块放置状态机
     */
    private enum PlacementState {
        IDLE, // 空闲状态，等待选择下一个方块
        SWITCHING_ITEM, // 正在切换物品
        PRESSING_SNEAK, // 处理潜行状态（按下或抬起）
        PLACING_BLOCK // 正在放置方块
    }

    public Printer() {
        super(Categories.Player, "printer", "Automatically places blocks based on Litematica schematic.");
    }

    @Override
    public void onActivate() {
        tickDelay = 0;
        placePositions.clear();
        placeItems.clear();
        resetStateMachine();
    }

    @Override
    public void onDeactivate() {
        tickDelay = 0;
        placePositions.clear();
        placeItems.clear();
        resetStateMachine();
        resetSneakState();
    }

    /**
     * 安全重置潜行状态
     * 只有当潜行是由打印机强制开启时，才将其关闭。
     * 这样可以保护玩家手动按住 Shift 的情况不被干扰。
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
     * 重置状态机到空闲状态
     */
    private void resetStateMachine() {
        currentTargetPos = null;
        currentTargetItem = null;
        currentTargetState = null;
        currentBlockNeedsSneak = false;
        placementState = PlacementState.IDLE;
        stateTickCounter = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // Critical: Stop immediately if module is disabled
        if (!isActive())
            return;

        // Check if player is moving and moveStop is enabled
        if (moveStop.get() && isPlayerMoving()) {
            return;
        }

        if (mc.player == null || mc.world == null)
            return;

        // Check if Litematica schematic is loaded
        WorldSchematic worldSchematic = SchematicWorldHandler.getSchematicWorld();
        if (worldSchematic == null) {
            if (isActive()) {
                error("Litematica schematic not loaded.");
                toggle();
            }
            return;
        }

        // Handle delay
        if (tickDelay < placeDelay.get()) {
            tickDelay++;
            return;
        }
        tickDelay = 0;

        // ==================== 状态机驱动的方块放置流程 ====================
        // 核心优化：使用 while 循环 + continue 实现状态穿透
        // 如果某个状态不需要执行（例如不需要潜行），立即跳过并进入下一个状态
        // 只有在实际需要执行操作时才 break，等待服务端同步
        // 这样每个 tick 都会尽可能前进，避免空闲 tick

        while (true) {
            switch (placementState) {
                case IDLE:
                    // 空闲状态：更新可放置方块列表，选择下一个目标
                    updatePlacePositions(worldSchematic);

                    if (placePositions.isEmpty()) {
                        // 【修改】列表为空，任务结束
                        // 调用安全复位：如果是打印机蹲的，打印机站起来；如果是玩家蹲的，保持蹲着
                        resetSneakState();
                        resetStateMachine();
                        return;
                    }

                    // 选择第一个方块作为目标（已按距离排序）
                    currentTargetPos = placePositions.get(0);
                    currentTargetItem = placeItems.get(currentTargetPos);
                    currentTargetState = worldSchematic.getBlockState(currentTargetPos);

                    if (currentTargetItem == null || currentTargetState == null) {
                        resetStateMachine();
                        return;
                    }

                    // 检查是否需要潜行（预先计算，以便在 SWITCHING_ITEM 状态使用）
                    Direction direction = getPlacementDirection(currentTargetPos);
                    if (direction == null) {
                        resetStateMachine();
                        return;
                    }

                    BlockPos neighborPos = currentTargetPos.offset(direction);
                    BlockState neighborState = mc.world.getBlockState(neighborPos);
                    currentBlockNeedsSneak = BlockUtilHelper.SNEAK_BLOCKS.contains(neighborState.getBlock());
                    currentPlayerIsSneaking = mc.player.isSneaking();

                    // 进入物品切换状态
                    placementState = PlacementState.SWITCHING_ITEM;
                    stateTickCounter = 0;
                    // 不 break，继续执行下一个状态（状态穿透）
                    continue;

                case SWITCHING_ITEM:
                    // 物品切换状态：切换到目标物品
                    if (!ItemSwitchHelper.switchToItem(currentTargetItem, true, false)) {
                        // 物品不存在或切换失败，放弃当前方块，回到空闲状态
                        resetStateMachine();
                        return;
                    }

                    // 检查物品是否已经拿到（立即生效）
                    if (ItemSwitchHelper.isItemInMainHand(currentTargetItem)) {
                        // 物品已拿到，立即进入潜行处理状态（无需等待）
                        placementState = PlacementState.PRESSING_SNEAK;
                        stateTickCounter = 0;
                        // 继续执行下一个状态，不 break
                        continue;
                    } else {
                        // 物品未立即生效，需要等待此 tick
                        stateTickCounter++;
                        // 物品切换需要与服务端同步，此 tick 就此结束
                        break;
                    }

                case PRESSING_SNEAK:
                    // 潜行状态处理：根据需求按下或抬起潜行键
                    // 关键优化：判断是否真的需要执行潜行操作
                    if (currentBlockNeedsSneak && !mc.player.isSneaking()) {
                        // 需要潜行但玩家未潜行，按下潜行键
                        mc.options.sneakKey.setPressed(true);
                        currentPlayerIsSneaking = true;
                        didPrinterForceSneak = true; // 标记所有权
                        stateTickCounter++;
                        // 潜行操作需要与服务端同步，此 tick 结束
                        break;
                    } else if (!currentBlockNeedsSneak && mc.player.isSneaking()) {
                        // 不需要潜行但玩家正在潜行，抬起潜行键
                        mc.options.sneakKey.setPressed(false);
                        currentPlayerIsSneaking = false;
                        didPrinterForceSneak = false; // 释放所有权
                        stateTickCounter++;
                        // 潜行操作需要与服务端同步，此 tick 结束
                        break;
                    } else {
                        // 潜行状态已满足要求，无需执行潜行操作，立即进入放置状态
                        placementState = PlacementState.PLACING_BLOCK;
                        stateTickCounter = 0;
                        // 继续执行下一个状态，不 break
                        continue;
                    }

                case PLACING_BLOCK:
                    // 放置状态：执行实际的方块放置
                    boolean placed = executePlacement(currentTargetPos, currentTargetState);

                    // 无论放置成功与否，都标记为已执行
                    // 如果放置失败，可能是视线问题或其他临时问题，不阻塞后续方块
                    stateTickCounter++;

                    // 放置方块需要与服务端同步，回到空闲状态
                    resetStateMachine();
                    // 此 tick 结束，不立即继续（确保服务端有足够的时间同步）
                    break;
            }

            // 如果执行到此处，说明某个状态触发了 break
            // 跳出 while 循环，本 tick 结束
            break;
        }
    }

    /**
     * 获取当前目标方块的放置方向
     * 根据模式选择使用STRICT或LEGIT的方向检查
     *
     * @param pos 目标位置
     * @return 放置方向，如果无法放置则返回null
     */
    private Direction getPlacementDirection(BlockPos pos) {
        WorldSchematic worldSchematic = SchematicWorldHandler.getSchematicWorld();
        if (worldSchematic == null)
            return null;

        BlockState requiredState = worldSchematic.getBlockState(pos);
        boolean strict = placeMode.get() == PlaceMode.STRICT;
        boolean checkLos = strict && checkLineOfSight.get();

        return BlockUtilHelper.findBestInteractDirection(pos, requiredState, mc.world, mc.player, strict, checkLos);
    }

    /**
     * 执行实际的方块放置操作
     * 此方法在PLACING_BLOCK状态时调用，假设物品已切换，潜行已按下（如果需要）
     *
     * @param pos           目标位置
     * @param requiredState 目标方块状态
     * @return 放置是否成功
     */
    private boolean executePlacement(BlockPos pos, BlockState requiredState) {
        if (placeMode.get() == PlaceMode.LEGIT) {
            return placeBlockLegit(pos, requiredState);
        } else {
            return placeBlockStrict(pos, requiredState);
        }
    }

    /**
     * [新增] 辅助方法：统一计算点击坐标
     * 解决了楼梯和半砖需要特定点击偏移的问题
     */
    private Vec3d calculateHitVec(BlockPos neighborPos, Direction clickedSide, BlockState state) {
        Block block = state.getBlock();

        // 1. 半砖：根据 Top/Bottom 调整 Y
        if (block instanceof SlabBlock && state.contains(SlabBlock.TYPE)) {
            SlabType type = state.get(SlabBlock.TYPE);
            return BlockUtilHelper.getHitVecForSlab(neighborPos, clickedSide, type);
        }

        // 2. 楼梯：根据 Half (Top/Bottom) 调整 Y
        if (block instanceof StairsBlock && state.contains(StairsBlock.HALF)) {
            net.minecraft.block.enums.BlockHalf half = state.get(StairsBlock.HALF);
            return BlockUtilHelper.getHitVecForStairs(neighborPos, clickedSide, half);
        }

        // 3. 默认：点击中心
        return BlockUtilHelper.getHitVec(neighborPos, clickedSide);
    }

    /**
     * 普通模式放置方块（LEGIT模式）
     * 使用简化的逻辑，不进行严格的反作弊检查
     *
     * @param pos           目标位置
     * @param requiredState 目标方块状态
     * @return 放置是否成功
     */
    private boolean placeBlockLegit(BlockPos pos, BlockState requiredState) {
        // 使用智能搜索获取方向
        Direction direction = BlockUtilHelper.findBestInteractDirection(pos, requiredState, mc.world, mc.player, false,
                false);
        if (direction == null)
            return false;

        BlockPos neighborPos = pos.offset(direction);
        Direction clickedSide = direction.getOpposite();

        // [核心修复] 使用统一的 HitVec 计算逻辑，支持楼梯
        Vec3d hitVec = calculateHitVec(neighborPos, clickedSide, requiredState);

        // 执行放置
        if (rotate.get()) {
            double yaw = Rotations.getYaw(hitVec);
            double pitch = Rotations.getPitch(hitVec);
            Rotations.rotate(yaw, pitch, 50, () -> {
                placeBlockInternal(neighborPos, clickedSide, hitVec);
            });
        } else {
            placeBlockInternal(neighborPos, clickedSide, hitVec);
        }

        return true;
    }

    /**
     * 使用STRICT模式放置方块，包含反作弊绕过和方向检查
     * 关键：hitVec（点击位置）决定了方块的朝向，特别是对半砖至关重要
     *
     * @param pos           目标位置（要放置的方块位置）
     * @param requiredState 目标方块状态（包含朝向属性）
     * @return 放置是否成功
     */
    private boolean placeBlockStrict(BlockPos pos, BlockState requiredState) {
        // 使用新的智能搜索获取方向（包含视线检查）
        Direction direction = BlockUtilHelper.findBestInteractDirection(pos, requiredState, mc.world, mc.player, true,
                checkLineOfSight.get());

        if (direction == null)
            return false;

        BlockPos neighborPos = pos.offset(direction);
        Direction clickedSide = direction.getOpposite();

        // [核心修复] 使用统一的 HitVec 计算逻辑，支持楼梯
        Vec3d hitVec = calculateHitVec(neighborPos, clickedSide, requiredState);

        // 计算旋转角度并执行放置
        double yaw = Rotations.getYaw(hitVec);
        double pitch = Rotations.getPitch(hitVec);

        if (rotate.get()) {
            Rotations.rotate(yaw, pitch, 50, () -> {
                placeBlockInternal(neighborPos, clickedSide, hitVec);
            });
        } else {
            placeBlockInternal(neighborPos, clickedSide, hitVec);
        }

        return true;
    }

    /**
     * Internal block placement using BlockHitResult.
     * 直接执行方块放置，不处理潜行（由状态机负责）
     *
     * @param neighborPos The block being interacted with.
     * @param side        The face of the neighbor block being clicked.
     * @param hitVec      The exact position of the click.
     */
    private void placeBlockInternal(BlockPos neighborPos, Direction side, Vec3d hitVec) {
        BlockHitResult hitResult = new BlockHitResult(hitVec, side, neighborPos, false);
        ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);

        if (result.isAccepted()) {
            if (swingHand.get()) {
                mc.player.swingHand(Hand.MAIN_HAND);
            } else {
                mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
            }
        }
    }

    /**
     * 更新需要放置方块的位置列表
     * 从Litematica原理图和世界进行对比，找出所有需要放置的方块
     */
    private void updatePlacePositions(WorldSchematic worldSchematic) {
        placePositions.clear();
        placeItems.clear();

        if (mc.player == null || mc.world == null)
            return;

        Vec3d playerPos = mc.player.getEyePos();
        int range = placeRange.get();

        // 获取玩家周围球形范围内的所有方块位置
        List<BlockPos> sphere = getSphere(range, playerPos);

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
                        // Pass through to placement logic
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
            boolean isCurrentLiquid = currentState.getFluidState() != null && !currentState.getFluidState().isEmpty();
            if (!currentState.isAir() && !isCurrentLiquid && !currentState.isReplaceable()) {
                continue;
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
                // STRICT 模式：使用新的智能搜索方法
                // 这里不再手动检查 canPlaceTopSlab 等布尔值，而是直接看“有没有合法的放置方向”
                Direction bestDir = BlockUtilHelper.findBestInteractDirection(pos, requiredState, mc.world, mc.player,
                        true, checkLineOfSight.get());

                if (bestDir == null) {
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
     */
    private List<BlockPos> getSphere(int range, Vec3d center) {
        List<BlockPos> list = new ArrayList<>();

        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos pos = BlockPos.ofFloored(center.x + x, center.y + y, center.z + z);
                    if (Vec3d.ofCenter(pos).distanceTo(center) <= range) {
                        list.add(pos);
                    }
                }
            }
        }

        return list;
    }

    /**
     * Checks if there's an entity blocking placement at the given position.
     */
    private boolean hasBlockingEntity(BlockPos pos) {
        net.minecraft.util.math.Box box = new net.minecraft.util.math.Box(pos);
        for (Entity entity : mc.world.getEntitiesByClass(Entity.class, box, e -> true)) {
            if (entity.isAlive() &&
                    !(entity instanceof ItemFrameEntity) &&
                    !(entity instanceof ArmorStandEntity) &&
                    !(entity instanceof EndCrystalEntity)) {
                return true;
            }
        }
        return false;
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
        // Critical: Stop immediately if module is disabled or no blocks to render
        if (!isActive() || !render.get() || placePositions.isEmpty())
            return;

        for (BlockPos pos : placePositions) {
            event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }

    @Override
    public String getInfoString() {
        // 显示当前状态和待放置方块数量
        if (placementState != PlacementState.IDLE) {
            return placementState.name() + " (" + placePositions.size() + ")";
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
