/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.util.math.BlockPos;

/**
 * Printer Module - Automatically places blocks based on a Litematica schematic.
 * Restored and modernized from original obfuscated ggboy code.
 */
public class Printer extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General Settings
    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("The delay between placing blocks in ticks.")
        .defaultValue(1)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> placeRange = sgGeneral.add(new IntSetting.Builder()
        .name("place-range")
        .description("The radius to place blocks in.")
        .defaultValue(4)
        .min(1)
        .sliderMax(6)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates towards the blocks being placed to ensure correct orientation.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Automatically switches to the correct block in your hotbar.")
        .defaultValue(true)
        .build()
    );

    // Render Settings
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders a highlight around the blocks to be placed.")
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
        .description("The side color for the rendering.")
        .defaultValue(new SettingColor(20, 200, 20, 45))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color for the rendering.")
        .defaultValue(new SettingColor(20, 200, 20, 125))
        .build()
    );


    private final List<BlockPos> placementQueue = new ArrayList<>();
    private int placeDelayLeft;

    public Printer() {
        super(Categories.Player, "printer", "Automatically builds Litematica schematics.");
    }

    @Override
    public void onActivate() {
        placeDelayLeft = 0;
        placementQueue.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // If Litematica world is not present, do nothing.
        WorldSchematic worldSchematic = SchematicWorldHandler.getSchematicWorld();
        if (mc.player == null || worldSchematic == null) {
            if (isActive()) {
                error("Litematica schematic not loaded.");
                toggle();
            }
            return;
        }

        // Handle placement delay
        if (placeDelayLeft > 0) {
            placeDelayLeft--;
            return;
        }

        // Re-scan for blocks to place
        updatePlacementQueue(worldSchematic);

        if (placementQueue.isEmpty()) {
            return;
        }

        // Get the closest block from the queue
        BlockPos blockPos = placementQueue.remove(0);

        // Get the required block state from the schematic
        BlockState requiredState = worldSchematic.getBlockState(blockPos);

        // Find the required item in the hotbar
        FindItemResult findItemResult = InvUtils.findInHotbar(itemStack -> isItemApplicable(itemStack, requiredState));

        if (!findItemResult.found()) {
            return; // No suitable item found, wait for next tick
        }

        // Place the block using Meteor's BlockUtils for "legit" placement
        if (BlockUtils.place(blockPos, findItemResult, rotate.get(), 100, autoSwitch.get())) {
            placeDelayLeft = placeDelay.get();
        }
    }

    /**
     * Scans the area around the player and populates the placement queue with valid targets.
     */
    private void updatePlacementQueue(WorldSchematic worldSchematic) {
        placementQueue.clear();
        BlockPos playerPos = mc.player.getBlockPos();
        int range = placeRange.get();

        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos pos = playerPos.add(x, y, z);

                    // Check if the position is inside the schematic and within the enabled render layers
                    if (!(pos.getY() >= DataManager.getRenderLayerRange().getLayerMin() &&
                          pos.getY() <= DataManager.getRenderLayerRange().getLayerMax())) {
                        continue;
                    }

                    BlockState requiredState = worldSchematic.getBlockState(pos);
                    BlockState currentState = mc.world.getBlockState(pos);

                    // If the block is already correct, or the schematic wants air, skip it
                    if (requiredState.isAir() || requiredState.equals(currentState)) {
                        continue;
                    }

                    // Check if we can place the block and if we have the item for it
                    if (BlockUtils.canPlace(pos) && InvUtils.findInHotbar(itemStack -> isItemApplicable(itemStack, requiredState)).found()) {
                        placementQueue.add(pos);
                    }
                }
            }
        }

        // Sort the queue by distance to the player to place nearest blocks first
        placementQueue.sort(Comparator.comparingDouble(pos -> mc.player.getPos().squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)));
    }

    /**
     * Checks if an ItemStack contains the block required by the schematic.
     */
    private boolean isItemApplicable(ItemStack itemStack, BlockState requiredState) {
        if (!(itemStack.getItem() instanceof BlockItem)) return false;
        BlockItem blockItem = (BlockItem) itemStack.getItem();
        return blockItem.getBlock() == requiredState.getBlock();
    }

    /**
     * Renders a highlight around the blocks in the placement queue.
     */
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || placementQueue.isEmpty()) return;

        for (BlockPos pos : placementQueue) {
            event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }

    @Override
    public String getInfoString() {
        return String.format("%d", placementQueue.size());
    }
}
