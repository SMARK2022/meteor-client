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
            Item item = placeItems.get(pos);

            if (item == null) continue;

            // Find the item in hotbar
            FindItemResult findResult = InvUtils.findInHotbar(item);
            if (!findResult.found()) continue;

            // Get the required block state from schematic for orientation
            BlockState requiredState = worldSchematic.getBlockState(pos);

            // Place the block based on mode
            if (placeMode.get() == PlaceMode.LEGIT) {
                // Standard placement using BlockUtils
                BlockUtils.place(pos, findResult, rotate.get(), 50, swingHand.get(), true);
            } else {
                // STRICT mode - placement with NCP direction checks and orientation support
                placeBlockStrict(pos, findResult, requiredState);
            }
        }
    }

    /**
     * Places a block using STRICT mode with anti-cheat bypass.
     * Uses direction checking and proper block orientation based on hit position.
     *
     * Critical: The hitVec (click position) determines block orientation for directional blocks
     */
    private boolean placeBlockStrict(BlockPos pos, FindItemResult findResult, BlockState requiredState) {
        if (!findResult.isHotbar() && !findResult.isOffhand()) return false;

        Direction direction = getInteractDirectionStrict(pos);
        if (direction == null) return false;

        BlockPos neighborPos = pos.offset(direction);

        if (checkLineOfSight.get() && !canSeeBlock(neighborPos, direction.getOpposite())) {
            return false;
        }

        if (findResult.isHotbar()) {
            InvUtils.swap(findResult.slot(), true);
        }

        BlockState neighborState = mc.world.getBlockState(neighborPos);
        boolean shouldSneak = BlockUtilHelper.SNEAK_BLOCKS.contains(neighborState.getBlock()) && !mc.player.isSneaking();

        if (shouldSneak) {
            mc.player.setSneaking(true);
        }

        // The side of the neighbor block we are clicking on.
        Direction clickedSide = direction.getOpposite();

        // Calculate hitVec, which is critical for block orientation (e.g., slabs).
        Vec3d hitVec;
        Block block = requiredState.getBlock();

        // Special handling for slabs to place them as top or bottom slabs.
        if (block instanceof SlabBlock && requiredState.contains(SlabBlock.TYPE)) {
            SlabType slabType = requiredState.get(SlabBlock.TYPE);
            Vec3d neighborCenter = Vec3d.ofCenter(neighborPos);

            // If clicking a horizontal face, adjust the Y-position of the click.
            if (clickedSide.getAxis().isHorizontal()) {
                double yOffset = (slabType == SlabType.TOP) ? 0.9 : 0.1;
                hitVec = new Vec3d(
                    neighborPos.getX() + 0.5,
                    neighborPos.getY() + yOffset,
                    neighborPos.getZ() + 0.5
                ).add(Vec3d.of(clickedSide.getVector()).multiply(0.5));
            } else {
                 // Clicking top or bottom face.
                 // A top click (UP) places a BOTTOM slab. A bottom click (DOWN) places a TOP slab.
                 // If the required slab type is incompatible with the only available click direction, we can't place it.
                 if ((slabType == SlabType.TOP && clickedSide == Direction.UP) ||
                     (slabType == SlabType.BOTTOM && clickedSide == Direction.DOWN)) {
                      // We can't place this slab from this direction, so we should skip.
                      // In a more advanced implementation, updatePlacePositions should filter this out.
                      if (shouldSneak) mc.player.setSneaking(false);
                      InvUtils.swapBack();
                      return false;
                 }
                 // Use the center of the face for vertical clicks.
                 hitVec = neighborCenter.add(Vec3d.of(clickedSide.getVector()).multiply(0.5));
            }
        } else {
            // Default logic for other blocks: click the center of the face.
            hitVec = Vec3d.ofCenter(neighborPos).add(Vec3d.of(clickedSide.getVector()).multiply(0.5));
        }

        double yaw = Rotations.getYaw(hitVec);
        double pitch = Rotations.getPitch(hitVec);

        if (rotate.get()) {
            Rotations.rotate(yaw, pitch, 50, () -> placeBlockInternal(neighborPos, clickedSide, hitVec));
        } else {
            placeBlockInternal(neighborPos, clickedSide, hitVec);
        }

        if (shouldSneak) {
            mc.player.setSneaking(false);
        }

        InvUtils.swapBack();

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
     * Updates the list of positions that need blocks placed.
     */
    private void updatePlacePositions(WorldSchematic worldSchematic) {
        placePositions.clear();
        placeItems.clear();

        if (mc.player == null || mc.world == null) return;

        Vec3d playerPos = mc.player.getEyePos();
        int range = placeRange.get();

        // Get blocks in sphere around player
        List<BlockPos> sphere = getSphere(range, playerPos);

        for (BlockPos pos : sphere) {
            // Check if within render layer range
            if (!DataManager.getRenderLayerRange().isPositionWithinRange(pos)) {
                continue;
            }

            BlockState requiredState = worldSchematic.getBlockState(pos);
            BlockState currentState = mc.world.getBlockState(pos);

            // Skip if schematic wants air
            if (requiredState.isAir()) {
                continue;
            }

            // Skip if block is already correct (same type)
            // 关键修复：应该比较Block类型，而不是整个BlockState
            if (requiredState.getBlock() == currentState.getBlock()) {
                continue;
            }

            // Skip liquids
            if (requiredState.isLiquid()) {
                continue;
            }

            // 关键修复：对于能够替换的方块（空气、水、某些方块）才能放置
            // 检查是否可以替换：空气、replaceable的方块都可以
            if (!currentState.isAir() && !currentState.isLiquid() && !currentState.isReplaceable()) {
                continue;
            }

            // Check for entity collision
            if (hasBlockingEntity(pos)) {
                continue;
            }

            // Get the item for this block
            Item item = requiredState.getBlock().asItem();
            if (item == Items.AIR) {
                continue;
            }

            // Check if we have the item
            if (!InvUtils.findInHotbar(item).found()) {
                continue;
            }

            // Mode-specific placement checks
            if (placeMode.get() == PlaceMode.LEGIT) {
                // LEGIT mode: use standard canPlace check
                if (!BlockUtils.canPlace(pos)) {
                    continue;
                }
            } else {
                // STRICT mode: check for valid interaction direction
                Direction direction = getInteractDirectionStrict(pos);
                if (direction == null) {
                    continue;
                }

                // Optional line of sight check - 检查支撑方块位置的可见性
                // direction.getOpposite() 是从支撑方块指向目标的方向
                if (checkLineOfSight.get()) {
                    BlockPos neighborPos = pos.offset(direction);
                    if (!canSeeBlock(neighborPos, direction.getOpposite())) {
                        continue;
                    }
                }
            }

            placePositions.add(pos);
            placeItems.put(pos, item);
        }

        // Sort by distance (closest first)
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
