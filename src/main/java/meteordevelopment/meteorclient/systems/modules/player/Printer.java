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

        // Get the interaction direction with NCP checks
        Direction direction = getInteractDirectionStrict(pos);
        if (direction == null) return false;

        // Check line of sight if enabled
        if (checkLineOfSight.get() && !canSeeBlock(pos, direction)) {
            return false;
        }

        // The neighbor is the block we're clicking on (that we're placing against)
        BlockPos neighbor = pos.offset(direction.getOpposite());

        // Swap to the correct item
        if (findResult.isHotbar()) {
            InvUtils.swap(findResult.slot(), true);
        }

        // Check if we need to sneak
        BlockState neighborState = mc.world.getBlockState(neighbor);
        boolean shouldSneak = BlockUtilHelper.SNEAK_BLOCKS.contains(neighborState.getBlock()) && !mc.player.isSneaking();

        if (shouldSneak) {
            mc.player.setSneaking(true);
        }

        // Calculate the hitVec based on the direction we're clicking from
        // This is critical: hitVec determines which face player is clicking,
        // which determines block orientation for directional blocks
        Vec3d hitVec = BlockUtilHelper.getHitVec(neighbor, direction);

        // Calculate rotation to the hit position (not the block center!)
        // This ensures proper rotation angle for directional blocks
        double yaw = Rotations.getYaw(hitVec);
        double pitch = Rotations.getPitch(hitVec);

        // Rotate and place - rotation will complete before placement
        if (rotate.get()) {
            Rotations.rotate(yaw, pitch, 50, () -> {
                placeBlockInternal(neighbor, direction, hitVec);
            });
        } else {
            placeBlockInternal(neighbor, direction, hitVec);
        }

        if (shouldSneak) {
            mc.player.setSneaking(false);
        }

        // Swap back
        InvUtils.swapBack();

        return true;
    }

    /**
     * Internal block placement using BlockHitResult.
     * The hitVec (click position) is critical for determining block orientation.
     */
    private void placeBlockInternal(BlockPos neighborPos, Direction direction, Vec3d hitVec) {
        // Create hit result with correct neighbor position
        // direction here is the direction we're clicking FROM (the block face we're clicking on)
        BlockHitResult hitResult = new BlockHitResult(hitVec, direction, neighborPos, false);
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

            // Skip if schematic wants air or block is already correct
            if (requiredState.isAir() || requiredState.getBlock() == currentState.getBlock()) {
                continue;
            }

            // Skip liquids
            if (requiredState.isLiquid()) {
                continue;
            }

            // Check if position is replaceable
            if (!currentState.isReplaceable()) {
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

                // Optional line of sight check
                if (checkLineOfSight.get() && !canSeeBlock(pos, direction)) {
                    continue;
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
        Box box = new Box(pos);
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
