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
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtilHelper;
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

import net.minecraft.util.math.Box;

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
        .build()
    );

    private final Setting<PlaceMode> placeMode = sgGeneral.add(new EnumSetting.Builder<PlaceMode>()
        .name("place-mode")
        .description("The method used to place blocks. STRICT uses NCP direction checks for anti-cheat bypass.")
        .defaultValue(PlaceMode.STRICT)
        .build()
    );

    private final Setting<Integer> placeNums = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("How many blocks to place per tick.")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Delay in ticks between placing blocks.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Integer> placeRange = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("The range within which to place blocks.")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 6)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates towards the block being placed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swingHand = sgGeneral.add(new BoolSetting.Builder()
        .name("swing-hand")
        .description("Swing hand when placing blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> checkLineOfSight = sgGeneral.add(new BoolSetting.Builder()
        .name("check-line-of-sight")
        .description("Only place blocks that are visible to the player (anti-cheat).")
        .defaultValue(true)
        .visible(() -> placeMode.get() == PlaceMode.STRICT)
        .build()
    );

    // Render Settings
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders blocks that are about to be placed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The side color of the rendering.")
        .defaultValue(new SettingColor(20, 200, 20, 50))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color of the rendering.")
        .defaultValue(new SettingColor(20, 200, 20, 255))
        .build()
    );

    // Internal state
    private final List<BlockPos> placePositions = new ArrayList<>();
    private final Map<BlockPos, Item> placeItems = new HashMap<>();
    private int tickDelay = 0;

    public Printer() {
        super(Categories.Player, "printer", "Automatically places blocks based on Litematica schematic.");
    }

    @Override
    public void onActivate() {
        tickDelay = 0;
        placePositions.clear();
        placeItems.clear();
    }

    @Override
    public void onDeactivate() {
        tickDelay = 0;
        placePositions.clear();
        placeItems.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // Critical: Stop immediately if module is disabled
        if (!isActive()) return;

        // Check if player is moving and moveStop is enabled
        if (moveStop.get() && isPlayerMoving()) {
            return;
        }

        if (mc.player == null || mc.world == null) return;

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

        // Update placeable positions
        updatePlacePositions(worldSchematic);

        if (placePositions.isEmpty()) return;

        // Calculate how many blocks to place this tick
        int blocksToPlace = Math.min(placeNums.get(), placePositions.size());

        for (int i = 0; i < blocksToPlace; i++) {
            if (i >= placePositions.size()) break;

            BlockPos pos = placePositions.get(i);
            Item targetItem = placeItems.get(pos);

            if (targetItem == null) continue;

            // 实时查找物品，优先查找快捷栏
            FindItemResult findResult = InvUtils.findInHotbar(targetItem);

            // 如果快捷栏没有，查找整个物品栏（包括offhand）
            if (!findResult.found()) {
                findResult = InvUtils.find(targetItem);
            }

            if (!findResult.found()) continue;

            // 获取该位置的目标方块状态（用于确定朝向）
            BlockState requiredState = worldSchematic.getBlockState(pos);

            // 根据模式选择放置方法
            if (placeMode.get() == PlaceMode.LEGIT) {
                // 普通模式：使用BlockUtils，自动处理物品栏、潜行等
                BlockUtils.place(pos, findResult, rotate.get(), 50, swingHand.get(), true);
            } else {
                // STRICT模式：手动处理所有细节（反作弊绕过）
                placeBlockStrict(pos, findResult, requiredState);
            }
        }
    }

    /**
     * 使用STRICT模式放置方块，包含反作弊绕过和方向检查
     * 关键：hitVec（点击位置）决定了方块的朝向，特别是对半砖至关重要
     *
     * @param pos 目标位置（要放置的方块位置）
     * @param findResult 物品栏查找结果
     * @param requiredState 目标方块状态（包含朝向属性）
     * @return 放置是否成功
     */
    private boolean placeBlockStrict(BlockPos pos, FindItemResult findResult, BlockState requiredState) {
        if (!findResult.isHotbar() && !findResult.isOffhand()) return false;

        // 获取可以放置的方向
        Direction direction = getInteractDirectionStrict(pos);
        if (direction == null) return false;

        BlockPos neighborPos = pos.offset(direction);

        // 线性视距检查（检查支撑方块的可见性）
        if (checkLineOfSight.get() && !canSeeBlock(neighborPos, direction.getOpposite())) {
            return false;
        }

        // ==================== 第一步：切换物品到主手 ====================
        boolean swappedItem = false;
        if (findResult.isHotbar()) {
            InvUtils.swap(findResult.slot(), true);
            swappedItem = true;
        }

        // ==================== 第二步：检查是否需要潜行 ====================
        BlockState neighborState = mc.world.getBlockState(neighborPos);
        boolean shouldSneak = BlockUtilHelper.SNEAK_BLOCKS.contains(neighborState.getBlock()) && !mc.player.isSneaking();

        // 点击的邻居方块的哪个面
        Direction clickedSide = direction.getOpposite();

        // 计算hitVec（点击位置）
        Vec3d hitVec;
        Block block = requiredState.getBlock();

        if (block instanceof SlabBlock && requiredState.contains(SlabBlock.TYPE)) {
            SlabType slabType = requiredState.get(SlabBlock.TYPE);
            hitVec = BlockUtilHelper.getHitVecForSlab(neighborPos, clickedSide, slabType);
        } else {
            hitVec = BlockUtilHelper.getHitVec(neighborPos, clickedSide);
        }

        // 计算旋转角度
        double yaw = Rotations.getYaw(hitVec);
        double pitch = Rotations.getPitch(hitVec);

        // ==================== 第三步：执行放置（通过rotation回调确保潜行状态同步） ====================
        if (rotate.get()) {
            // 使用rotate回调，在旋转完成后执行交互
            // 这个回调延迟确保潜行状态有足够的同步时间
            Rotations.rotate(yaw, pitch, 50, () -> {
                if (shouldSneak) {
                    mc.player.setSneaking(true);
                }
                placeBlockInternal(neighborPos, clickedSide, hitVec);
                if (shouldSneak) {
                    mc.player.setSneaking(false);
                }
            });
        } else {
            // 不旋转的情况下，直接设置潜行、交互、恢复潜行
            if (shouldSneak) {
                mc.player.setSneaking(true);
            }
            placeBlockInternal(neighborPos, clickedSide, hitVec);
            if (shouldSneak) {
                mc.player.setSneaking(false);
            }
        }

        // ==================== 第四步：恢复物品栏 ====================
        if (swappedItem) {
            InvUtils.swapBack();
        }

        return true;
    }

    /**
     * Internal block placement using BlockHitResult.
     *
     * @param neighborPos The block being interacted with.
     * @param side The face of the neighbor block being clicked.
     * @param hitVec The exact position of the click.
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
     * 获取NCP风格的交互方向（严格模式）
     */
    private Direction getInteractDirectionStrict(BlockPos blockPos) {
        return BlockUtilHelper.getInteractDirection(blockPos, mc.world, mc.player.getEyePos(), true);
    }

    /**
     * 获取半砖的NCP风格交互方向（严格模式）
     */
    private Direction getInteractDirectionSlabStrict(BlockPos blockPos) {
        return BlockUtilHelper.getInteractDirectionForSlab(blockPos, mc.world, mc.player.getEyePos(), true);
    }

    /**
     * 获取NCP有效方向集合
     */
    private Set<Direction> getPlaceDirectionsNCP(Vec3d eyePos, Vec3d blockPos) {
        return BlockUtilHelper.getPlaceDirectionsNCP(eyePos, blockPos);
    }

    /**
     * Checks if a block face is visible to the player (line of sight check).
     */
    private boolean canSeeBlock(BlockPos pos, Direction side) {
        return BlockUtilHelper.canSeeBlock(pos, side, mc.world, mc.player);
    }

    /**
     * 更新需要放置方块的位置列表
     * 从Litematica原理图和世界进行对比，找出所有需要放置的方块
     */
    private void updatePlacePositions(WorldSchematic worldSchematic) {
        placePositions.clear();
        placeItems.clear();

        if (mc.player == null || mc.world == null) return;

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
            // 关键修复：应该比较Block类型，而不是整个BlockState
            // 因为朝向等属性会在放置时自动设置
            if (requiredState.getBlock() == currentState.getBlock()) {
                continue;
            }

            // 跳过流体
            if (requiredState.isLiquid()) {
                continue;
            }

            // 检查当前位置是否可以被替换
            // 只有空气、流体和可替换方块才能被放置覆盖
            if (!currentState.isAir() && !currentState.isLiquid() && !currentState.isReplaceable()) {
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

            // 检查物品栏中是否有该物品
            if (!InvUtils.findInHotbar(item).found()) {
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
                // STRICT模式：反作弊绕过模式
                // 需要进行详细的方向检查和反作弊可放置性检查

                // 检查是否有有效的放置方向
                Direction direction = getInteractDirectionStrict(pos);
                if (direction == null) {
                    continue;
                }

                // 特殊处理：对于半砖，需要额外的可放置性检查
                if (requiredState.getBlock() instanceof SlabBlock &&
                    requiredState.contains(SlabBlock.TYPE)) {
                    SlabType slabType = requiredState.get(SlabBlock.TYPE);

                    // 根据半砖类型检查是否可以放置
                    if (slabType == SlabType.TOP) {
                        if (!BlockUtilHelper.canPlaceTopSlab(pos, mc.world)) {
                            continue;
                        }
                    } else if (slabType == SlabType.BOTTOM) {
                        if (!BlockUtilHelper.canPlaceBottomSlab(pos, mc.world)) {
                            continue;
                        }
                    } else {
                        // DOUBLE半砖，检查是否有任何支撑
                        if (!BlockUtilHelper.canPlaceTopSlab(pos, mc.world) &&
                            !BlockUtilHelper.canPlaceBottomSlab(pos, mc.world)) {
                            continue;
                        }
                    }
                }

                // 可选的线性视距检查
                if (checkLineOfSight.get()) {
                    BlockPos neighborPos = pos.offset(direction);
                    if (!canSeeBlock(neighborPos, direction.getOpposite())) {
                        continue;
                    }
                }
            }

            // 将该位置加入放置列表
            placePositions.add(pos);
            placeItems.put(pos, item);
        }

        // 按距离排序（最近的优先）
        placePositions.sort(Comparator.comparingDouble(pos ->
            mc.player.getEyePos().squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
        ));
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
        if (!isActive() || !render.get() || placePositions.isEmpty()) return;

        for (BlockPos pos : placePositions) {
            event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }

    @Override
    public String getInfoString() {
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
