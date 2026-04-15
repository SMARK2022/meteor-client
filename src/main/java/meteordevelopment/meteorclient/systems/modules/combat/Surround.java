/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.WorldRendererAccessor;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.Printer;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.printer.PlacementContext;
import meteordevelopment.meteorclient.utils.printer.PlacementOption;
import meteordevelopment.meteorclient.utils.printer.PrinterBehavior;
import meteordevelopment.meteorclient.utils.printer.PrinterTaskProvider;
import meteordevelopment.meteorclient.utils.printer.ResolverRegistry;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.Dir;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.BlockBreakingInfo;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.s2c.play.DeathMessageS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public class Surround extends Module implements PrinterTaskProvider {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgToggles = settings.createGroup("Toggles");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("What blocks to use for surround.")
        .defaultValue(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.NETHERITE_BLOCK)
        .filter(this::blockFilter)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay, in ticks, between block placements.")
        .min(0)
        .defaultValue(0)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("How many blocks to place in one tick.")
        .defaultValue(1)
        .min(1)
        .build()
    );

    private final Setting<Center> center = sgGeneral.add(new EnumSetting.Builder<Center>()
        .name("center")
        .description("Teleports you to the center of the block.")
        .defaultValue(Center.Incomplete)
        .build()
    );

    private final Setting<TopMode> headProtection = sgGeneral.add(new EnumSetting.Builder<TopMode>()
        .name("head-protection")
        .description("Head-level block protection. AntiFacePlace=4 side blocks at y+1; Top=1 block at y+2; Full=both; None=disabled.")
        .defaultValue(TopMode.None)
        .build()
    );

    private final Setting<BottomMode> belowProtection = sgGeneral.add(new EnumSetting.Builder<BottomMode>()
        .name("below-protection")
        .description("Place a block below your feet to prevent crystal placement under you.")
        .defaultValue(BottomMode.None)
        .build()
    );

    private final Setting<Boolean> onlyOnGround = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-ground")
        .description("Works only when you are standing on blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> airPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("air-place")
        .description("Allows Surround to place blocks in the air.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> toggleModules = sgGeneral.add(new BoolSetting.Builder()
        .name("toggle-modules")
        .description("Turn off other modules when surround is activated.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> toggleBack = sgGeneral.add(new BoolSetting.Builder()
        .name("toggle-back-on")
        .description("Turn the other modules back on when surround is deactivated.")
        .defaultValue(false)
        .visible(toggleModules::get)
        .build()
    );

    private final Setting<List<Module>> modules = sgGeneral.add(new ModuleListSetting.Builder()
        .name("modules")
        .description("Which modules to disable on activation.")
        .visible(toggleModules::get)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Automatically faces towards the obsidian being placed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> protect = sgGeneral.add(new BoolSetting.Builder()
        .name("protect")
        .description("Attempts to break crystals around surround positions to prevent surround break.")
        .defaultValue(true)
        .build()
    );

    // Toggles

    private final Setting<Boolean> toggleOnYChange = sgToggles.add(new BoolSetting.Builder()
        .name("toggle-on-y-change")
        .description("Automatically disables when your y level changes (step, jumping, etc).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> toggleOnComplete = sgToggles.add(new BoolSetting.Builder()
        .name("toggle-on-complete")
        .description("Toggles off when all blocks are placed.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> toggleOnDeath = sgToggles.add(new BoolSetting.Builder()
        .name("toggle-on-death")
        .description("Toggles off when you die.")
        .defaultValue(true)
        .build()
    );

    // Render

    private final Setting<Boolean> swing = sgRender.add(new BoolSetting.Builder()
        .name("swing")
        .description("Render your hand swinging when placing surround blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders a block overlay where the obsidian will be placed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> renderBelow = sgRender.add(new BoolSetting.Builder()
        .name("below")
        .description("Renders the block below you.")
        .defaultValue(false)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> safeSideColor = sgRender.add(new ColorSetting.Builder()
        .name("safe-side-color")
        .description("The side color for safe blocks.")
        .defaultValue(new SettingColor(13, 255, 0, 0))
        .visible(() -> render.get() && shapeMode.get() != ShapeMode.Lines)
        .build()
    );

    private final Setting<SettingColor> safeLineColor = sgRender.add(new ColorSetting.Builder()
        .name("safe-line-color")
        .description("The line color for safe blocks.")
        .defaultValue(new SettingColor(13, 255, 0, 0))
        .visible(() -> render.get() && shapeMode.get() != ShapeMode.Sides)
        .build()
    );

    private final Setting<SettingColor> normalSideColor = sgRender.add(new ColorSetting.Builder()
        .name("normal-side-color")
        .description("The side color for normal blocks.")
        .defaultValue(new SettingColor(0, 255, 238, 12))
        .visible(() -> render.get() && shapeMode.get() != ShapeMode.Lines)
        .build()
    );

    private final Setting<SettingColor> normalLineColor = sgRender.add(new ColorSetting.Builder()
        .name("normal-line-color")
        .description("The line color for normal blocks.")
        .defaultValue(new SettingColor(0, 255, 238, 100))
        .visible(() -> render.get() && shapeMode.get() != ShapeMode.Sides)
        .build()
    );

    private final Setting<SettingColor> unsafeSideColor = sgRender.add(new ColorSetting.Builder()
        .name("unsafe-side-color")
        .description("The side color for unsafe blocks.")
        .defaultValue(new SettingColor(204, 0, 0, 12))
        .visible(() -> render.get() && shapeMode.get() != ShapeMode.Lines)
        .build()
    );

    private final Setting<SettingColor> unsafeLineColor = sgRender.add(new ColorSetting.Builder()
        .name("unsafe-line-color")
        .description("The line color for unsafe blocks.")
        .defaultValue(new SettingColor(204, 0, 0, 100))
        .visible(() -> render.get() && shapeMode.get() != ShapeMode.Sides)
        .build()
    );

    public ArrayList<Module> toActivate = new ArrayList<>();
    private int timer;

    public Surround() {
        super(Categories.Combat, "surround", "Surrounds you in blocks to prevent massive crystal damage.");
    }

    // Render

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!render.get()) return;

        BlockPos playerPos = mc.player.getBlockPos();

        // Below
        if (renderBelow.get()) draw(playerPos.down(), event, 0);

        for (Direction direction : Direction.HORIZONTAL) {
            BlockPos renderPos = playerPos.offset(direction);

            // Regular surround positions
            boolean headSides = headProtection.get() == TopMode.AntiFacePlace || headProtection.get() == TopMode.Full;
            draw(renderPos, event, headSides ? Dir.UP : 0);

            // Head-level side blocks (AntiFacePlace / Full)
            if (headSides) draw(renderPos.up(), event, Dir.DOWN);
        }

        // Head top block (Top / Full)
        if (headProtection.get() == TopMode.Top || headProtection.get() == TopMode.Full) {
            draw(playerPos.add(0, 2, 0), event, 0);
        }

        // Below protection (Single)
        if (belowProtection.get() == BottomMode.Single) draw(playerPos.down(), event, 0);
    }

    private void draw(BlockPos renderPos, Render3DEvent event, int exclude) {
        Color sideColor = getSideColor(renderPos);
        Color lineColor = getLineColor(renderPos);
        event.renderer.box(renderPos, sideColor, lineColor, shapeMode.get(), exclude);
    }

    // Function

    @Override
    public void onActivate() {
        // Center on activate
        if (center.get() == Center.OnActivate) PlayerUtils.centerPlayer();

        // Reset delay
        timer = delay.get();

        if (toggleModules.get() && !modules.get().isEmpty() && mc.world != null && mc.player != null) {
            for (Module module : modules.get()) {
                if (module.isActive()) {
                    module.toggle();
                    toActivate.add(module);
                }
            }
        }

        // 注册为 Printer 输入源
        Printer.registerProvider(this);
    }

    @Override
    public void onDeactivate() {
        // 注销 Printer 输入源
        Printer.unregisterProvider(this);

        if (toggleBack.get() && !toActivate.isEmpty() && mc.world != null && mc.player != null) {
            for (Module module : toActivate) {
                if (!module.isActive()) {
                    module.toggle();
                }
            }
        }
    }

    // ==================== PrinterTaskProvider 接口实现 ====================

    /**
     * 返回当前需要防御放置的目标位置集合（所有仍为可替换状态的 Surround 位置）。
     * <p>Printer 模块调用此方法将 Surround 的任务注入其统一管线。</p>
     */
    @Override
    public Collection<BlockPos> getPlacementPositions() {
        if (mc.player == null || mc.world == null) return Collections.emptyList();

        List<BlockPos> needed = new ArrayList<>();
        BlockPos playerPos = mc.player.getBlockPos();

        // 四面脚部位置
        for (Direction dir : Direction.HORIZONTAL) {
            BlockPos pos = playerPos.offset(dir);
            if (mc.world.getBlockState(pos).isReplaceable()) needed.add(pos);

            // 空中放置时 support 格
            if (!airPlace.get() && isAirPlace(pos) && mc.world.getBlockState(pos).isReplaceable()) {
                BlockPos support = pos.down();
                if (mc.world.getBlockState(support).isReplaceable()) needed.add(support);
            }
        }

        // 头部侧面（AntiFacePlace / Full）
        boolean doHeadSides = headProtection.get() == TopMode.AntiFacePlace || headProtection.get() == TopMode.Full;
        if (doHeadSides) {
            for (Direction dir : Direction.HORIZONTAL) {
                BlockPos pos = playerPos.offset(dir).up();
                if (mc.world.getBlockState(pos).isReplaceable()) needed.add(pos);
            }
        }

        // 头顶（Top / Full）
        if (headProtection.get() == TopMode.Top || headProtection.get() == TopMode.Full) {
            BlockPos pos = playerPos.add(0, 2, 0);
            if (mc.world.getBlockState(pos).isReplaceable()) needed.add(pos);
        }

        // 脚下（Single）
        if (belowProtection.get() == BottomMode.Single) {
            BlockPos pos = playerPos.down();
            if (mc.world.getBlockState(pos).isReplaceable()) needed.add(pos);
        }

        return Collections.unmodifiableList(needed);
    }

    /**
     * 返回 Surround 期望放置的方块状态（使用首选方块的默认状态）。
     */
    @Override
    public BlockState getDesiredState(BlockPos pos) {
        Block b = blocks.get().isEmpty() ? Blocks.OBSIDIAN : blocks.get().getFirst();
        return b.getDefaultState();
    }

    /**
     * Surround 是防御模块，允许在无 Litematica 蓝图时运行。
     */
    @Override
    public boolean bypassSchematicCheck() {
        return true;
    }

    /**
     * Surround 只需要放置行为，不需要 BREAK/REDSTONE 等。
     */
    @Override
    public Set<PrinterBehavior.Group> allowedGroups() {
        return Set.of(PrinterBehavior.Group.PLACEMENT);
    }

    /**
     * 防御模块高优先级，确保在蓝图/SpawnProof 任务之前处理。
     */
    @Override
    public int priority() {
        return 100;
    }

    @Override
    public String name() {
        return "Surround";
    }

    // ==================== Tick 处理 ====================

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // Delay
        if (timer++ < delay.get()) return;

        // Toggle if Y level changed
        if (toggleOnYChange.get() && mc.player.lastY != mc.player.getY()) {
            toggle();
            return;
        }

        // Wait till player is on ground
        if (onlyOnGround.get() && !mc.player.isOnGround()) return;

        // 如果 Printer 已启用且自己已注册为输入源，让出控制权（仅保留 center/complete 逻辑）
        Printer printer = Modules.get().get(Printer.class);
        if (printer != null && printer.isActive() && Printer.isProviderRegistered(this)) {
            // Printer 管线负责实际 place，Surround 只负责 center
            boolean complete = getPlacementPositions().isEmpty();
            if (!complete && center.get() == Center.Incomplete) PlayerUtils.centerPlayer();
            if (complete && center.get() == Center.Always) PlayerUtils.centerPlayer();
            if (complete && toggleOnComplete.get()) { toggle(); return; }
            timer = 0;
            return;
        }

        // Wait until the player has a block available to place
        FindItemResult block = InvUtils.findInHotbar(itemStack -> blocks.get().contains(Block.getBlockFromItem(itemStack.getItem())));
        if (!block.found()) return;

        // Centering player
        if (center.get() == Center.Always) PlayerUtils.centerPlayer();

        int placedCount = 0;
        boolean complete = true;

        BlockPos playerPos = mc.player.getBlockPos();

        // Placing feet blocks
        for (Direction direction : Direction.HORIZONTAL) {
            BlockPos placePos = playerPos.offset(direction);

            // Place support blocks if air place is disabled
            if (!airPlace.get() && isAirPlace(placePos) && mc.world.getBlockState(placePos).isReplaceable()){
                if (placeSafe(placePos.down(), block) && ++placedCount >= blocksPerTick.get()) break;

                if (mc.world.getBlockState(placePos.down()).isReplaceable()) complete = false;
            }

            if (placeSafe(placePos, block) && ++placedCount >= blocksPerTick.get()) break;

            if (mc.world.getBlockState(placePos).isReplaceable()) complete = false;
        }

        // Placing head-level side blocks (AntiFacePlace / Full)
        boolean doHeadSides = headProtection.get() == TopMode.AntiFacePlace || headProtection.get() == TopMode.Full;
        boolean doHeadTop   = headProtection.get() == TopMode.Top            || headProtection.get() == TopMode.Full;

        if (doHeadSides && complete) {
            for (Direction direction : Direction.HORIZONTAL) {
                BlockPos placePos = playerPos.offset(direction).up();
                if (placeSafe(placePos, block) && ++placedCount >= blocksPerTick.get()) break;

                if (mc.world.getBlockState(placePos).isReplaceable()) complete = false;
            }
        }

        // Placing head top block (Top / Full)
        if (doHeadTop && complete) {
            BlockPos placePos = playerPos.add(0, 2, 0);
            if (mc.world.getBlockState(placePos).isReplaceable()) {
                if (placeSafe(placePos, block)) { placedCount++; complete = false; }
            }
        }

        // Placing below block (Single)
        if (belowProtection.get() == BottomMode.Single && complete) {
            BlockPos placePos = playerPos.down();
            if (mc.world.getBlockState(placePos).isReplaceable()) {
                if (placeSafe(placePos, block)) { placedCount++; complete = false; }
            }
        }

        timer = 0;

        // Disable if all the surround blocks are placed
        if (complete && toggleOnComplete.get()) {
            toggle();
            return;
        }

        // Keep the player centered until all the blocks are placed to avoid collision
        if (!complete && center.get() == Center.Incomplete) PlayerUtils.centerPlayer();
    }

    private boolean placeSafe(BlockPos placePos, FindItemResult item) {
        if (mc.player == null || mc.world == null) return false;

        boolean placed = false;

        // Try printer-resolver for NCP/GrimAC-safe face selection
        net.minecraft.item.ItemStack stack = mc.player.getInventory().getStack(item.isOffhand() ? 40 : item.slot());
        Block blockToPlace = Block.getBlockFromItem(stack.getItem());
        BlockState state = blockToPlace.getDefaultState();

        PlacementContext ctx = PlacementContext.of(mc.world, placePos, state, mc.player, true, false, 4.5);
        PlacementOption option = ResolverRegistry.resolve(ctx);

        if (option != null && option.hitVec() != null) {
            BlockPos interactPos = option.getInteractPos(placePos);
            Direction clickedFace = option.getClickedFace();
            BlockHitResult hitResult = new BlockHitResult(option.hitVec(), clickedFace, interactPos, false);
            Hand hand = item.isOffhand() ? Hand.OFF_HAND : Hand.MAIN_HAND;

            if (rotate.get()) {
                Vec3d hv = option.hitVec();
                double dx = hv.x - mc.player.getX();
                double dy = hv.y - mc.player.getEyeY();
                double dz = hv.z - mc.player.getZ();
                double yaw = Math.toDegrees(Math.atan2(-dx, dz));
                double pitch = Math.toDegrees(Math.atan2(-dy, Math.sqrt(dx * dx + dz * dz)));
                Hand fHand = hand;
                BlockHitResult fHit = hitResult;
                Rotations.rotate(yaw, pitch, () -> {
                    int seq = mc.world.getPendingUpdateManager().incrementSequence().getSequence();
                    mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(fHand, fHit, seq));
                });
            } else {
                int seq = mc.world.getPendingUpdateManager().incrementSequence().getSequence();
                mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(hand, hitResult, seq));
            }

            if (swing.get()) mc.player.swingHand(hand);
            placed = true;
        }
        // 无 fallback：ResolverRegistry 未找到有效面时静默跳过（避免 MultiPlace 风险）

        // Check if being mined
        boolean beingMined = false;
        for (BlockBreakingInfo value : ((WorldRendererAccessor) mc.worldRenderer).meteor$getBlockBreakingInfos().values()) {
            if (value.getPos().equals(placePos)) {
                beingMined = true;
                break;
            }
        }

        boolean isThreat = mc.world.getBlockState(placePos).isReplaceable() || beingMined;

        // If the block is air or is being mined, destroy nearby crystals to be safe
        if (protect.get() && !placed && isThreat) {
            Box box = new Box(
                placePos.getX() - 1, placePos.getY() - 1, placePos.getZ() - 1,
                placePos.getX() + 1, placePos.getY() + 1, placePos.getZ() + 1
            );

            Predicate<Entity> entityPredicate = entity -> entity instanceof EndCrystalEntity && DamageUtils.crystalDamage(mc.player, entity.getPos()) < PlayerUtils.getTotalHealth();

            for (Entity crystal : mc.world.getOtherEntities(null, box, entityPredicate)) {
                if (rotate.get()) {
                    Rotations.rotate(Rotations.getPitch(crystal), Rotations.getYaw(crystal), () -> {
                        mc.player.networkHandler.sendPacket(PlayerInteractEntityC2SPacket.attack(crystal, mc.player.isSneaking()));
                    });
                }
                else {
                    mc.player.networkHandler.sendPacket(PlayerInteractEntityC2SPacket.attack(crystal, mc.player.isSneaking()));
                }

                mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
            }
        }

        return placed;
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event)  {
        if (event.packet instanceof DeathMessageS2CPacket packet) {
            Entity entity = mc.world.getEntityById(packet.playerId());
            if (entity == mc.player && toggleOnDeath.get()) {
                toggle();
                info("Toggled off because you died.");
            }
        }
    }

    private BlockType getBlockType(BlockPos pos) {
        BlockState blockState = mc.world.getBlockState(pos);

        // Unbreakable eg. bedrock
        if (blockState.getBlock().getHardness() < 0) return BlockType.Safe;
        // Blast resistant eg. obsidian
        else if (blockState.getBlock().getBlastResistance() >= 600) return BlockType.Normal;
        // Anything else
        else return BlockType.Unsafe;
    }

    private Color getSideColor(BlockPos pos) {
        return switch (getBlockType(pos)) {
            case Safe -> safeSideColor.get();
            case Normal -> normalSideColor.get();
            case Unsafe -> unsafeSideColor.get();
        };
    }

    private Color getLineColor(BlockPos pos) {
        return switch (getBlockType(pos)) {
            case Safe -> safeLineColor.get();
            case Normal -> normalLineColor.get();
            case Unsafe -> unsafeLineColor.get();
        };
    }

    private boolean isAirPlace(BlockPos blockPos) {
        for (Direction direction : Direction.values()) {
            if (!mc.world.getBlockState(blockPos.offset(direction)).isReplaceable()) return false;
        }
        return true;
    }

    private boolean blockFilter(Block block) {
        return block.getBlastResistance() >= 600 && block.getHardness() >= 0 && block != Blocks.REINFORCED_DEEPSLATE;
    }

    public enum TopMode {
        None,
        AntiFacePlace,
        Top,
        Full
    }

    public enum BottomMode {
        None,
        Single
    }

    public enum Center {
        Never,
        OnActivate,
        Incomplete,
        Always
    }

    public enum BlockType {
        Safe,
        Normal,
        Unsafe
    }
}
