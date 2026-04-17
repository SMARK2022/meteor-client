/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
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
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
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

public class Surround extends Module implements PrinterTaskProvider {
    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sgBody     = settings.createGroup("Body");
    private final SettingGroup sgHead     = settings.createGroup("Head");
    private final SettingGroup sgFoot     = settings.createGroup("Feet");
    private final SettingGroup sgProtect  = settings.createGroup("Protect");
    private final SettingGroup sgLink     = settings.createGroup("Toggle");
    private final SettingGroup sgAutoOff  = settings.createGroup("Auto Disable");
    private final SettingGroup sgRender   = settings.createGroup("Render");

    // ── 通用 ──

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("What blocks to use for surround.")
        .defaultValue(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.NETHERITE_BLOCK)
        .filter(this::blockFilter)
        .build()
    );

    private final Setting<Center> center = sgGeneral.add(new EnumSetting.Builder<Center>()
        .name("center")
        .description("Teleports you to the center of the block. Note: may trigger GrimAC movement detection.")
        .defaultValue(Center.Never)
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
        .description("Allows Surround to place blocks in the air (skips support block calculation when enabled).")
        .defaultValue(false)
        .build()
    );

    // ── 身体 ──

    private final Setting<BodyMode> bodyMode = sgBody.add(new EnumSetting.Builder<BodyMode>()
        .name("body-mode")
        .description("Body protection mode. Lower=lower body y+0 four sides; Upper=upper body y+1 four sides; Full=all 8 sides.")
        .defaultValue(BodyMode.Full)
        .build()
    );

    // ── 头部 ──

    private final Setting<HeadMode> headMode = sgHead.add(new EnumSetting.Builder<HeadMode>()
        .name("head-mode")
        .description("Head protection mode. Single=y+2 single block; Full=y+2 cross-shaped 5 blocks.")
        .defaultValue(HeadMode.Full)
        .build()
    );

    // ── 脚部 ──

    private final Setting<FootMode> footMode = sgFoot.add(new EnumSetting.Builder<FootMode>()
        .name("feet-mode")
        .description("Feet protection mode. Single=y-1 single block; Full=y-1 cross-shaped 5 blocks.")
        .defaultValue(FootMode.Full)
        .build()
    );

    // ── 防护 ──

    private final Setting<Boolean> protect = sgProtect.add(new BoolSetting.Builder()
        .name("protect")
        .description("Attempts to break crystals around surround positions to prevent surround break.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> aggressiveProtect = sgProtect.add(new BoolSetting.Builder()
        .name("aggressive-protect")
        .description("Same tick Attack+Place (eliminates opponent crystal re-place window, but may trigger GrimAC MultiActionsF). When disabled, uses 2-tick safe mode.")
        .defaultValue(false)
        .visible(protect::get)
        .build()
    );

    private final Setting<Boolean> swing = sgProtect.add(new BoolSetting.Builder()
        .name("swing")
        .description("Render hand swing animation when attacking crystals.")
        .defaultValue(true)
        .visible(protect::get)
        .build()
    );

    // ── 联动 ──

    private final Setting<Boolean> toggleModules = sgLink.add(new BoolSetting.Builder()
        .name("toggle-modules")
        .description("Turn off other modules when surround is activated.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> toggleBack = sgLink.add(new BoolSetting.Builder()
        .name("toggle-back-on")
        .description("Turn the other modules back on when surround is deactivated.")
        .defaultValue(false)
        .visible(toggleModules::get)
        .build()
    );

    private final Setting<List<Module>> modules = sgLink.add(new ModuleListSetting.Builder()
        .name("modules")
        .description("Which modules to disable on activation.")
        .visible(toggleModules::get)
        .build()
    );

    // ── 自动关闭 ──

    private final Setting<Boolean> toggleOnYChange = sgAutoOff.add(new BoolSetting.Builder()
        .name("toggle-on-y-change")
        .description("Automatically disables when your y level changes (step, jumping, etc).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> toggleOnComplete = sgAutoOff.add(new BoolSetting.Builder()
        .name("toggle-on-complete")
        .description("Toggles off when all blocks are placed.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> toggleOnDeath = sgAutoOff.add(new BoolSetting.Builder()
        .name("toggle-on-death")
        .description("Toggles off when you die.")
        .defaultValue(true)
        .build()
    );

    // ── 渲染 ──

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders a block overlay where the blocks will be placed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("render-mode")
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

    /** 水晶防护旋转优先级（高于 Printer 的 50，确保抢占） */
    private static final int PROTECT_ROTATION_PRIORITY = 200;
    /** 攻击距离 */
    private static final double ATTACK_REACH = 3.0;

    public Surround() {
        super(Categories.Combat, "surround", "Surrounds you in blocks to prevent massive crystal damage.");
    }

    // ==================== 位置计算（统一来源） ====================

    /**
     * 计算所有 Surround 目标位置（不含 support 块，不过滤 blockState）。
     * 由 {@link #getPlacementPositions()} 和 {@link #onRender3D} 共用，消除重复遍历。
     */
    private List<BlockPos> computeAllTargetPositions() {
        List<BlockPos> positions = new ArrayList<>();
        BlockPos playerPos = mc.player.getBlockPos();

        // Body: 下肢 y+0
        boolean doLower = bodyMode.get() == BodyMode.Lower || bodyMode.get() == BodyMode.Full;
        if (doLower) {
            for (Direction dir : Direction.HORIZONTAL) positions.add(playerPos.offset(dir));
        }

        // Body: 上肢 y+1
        boolean doUpper = bodyMode.get() == BodyMode.Upper || bodyMode.get() == BodyMode.Full;
        if (doUpper) {
            for (Direction dir : Direction.HORIZONTAL) positions.add(playerPos.offset(dir).up());
        }

        // Head: 头顶 y+2
        if (headMode.get() == HeadMode.Single || headMode.get() == HeadMode.Full) {
            positions.add(playerPos.add(0, 2, 0));
        }

        // Head: 十字形 y+2（Full 模式额外四面）
        if (headMode.get() == HeadMode.Full) {
            for (Direction dir : Direction.HORIZONTAL) positions.add(playerPos.add(0, 2, 0).offset(dir));
        }

        // Foot: 脚下 y-1
        if (footMode.get() == FootMode.Single || footMode.get() == FootMode.Full) {
            positions.add(playerPos.down());
        }

        // Foot: 十字形 y-1（Full 模式额外四面）
        if (footMode.get() == FootMode.Full) {
            for (Direction dir : Direction.HORIZONTAL) positions.add(playerPos.down().offset(dir));
        }

        return positions;
    }

    // ==================== 渲染 ====================

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!render.get() || mc.player == null || mc.world == null) return;

        for (BlockPos pos : computeAllTargetPositions()) {
            Color sideColor = getSideColor(pos);
            Color lineColor = getLineColor(pos);
            event.renderer.box(pos, sideColor, lineColor, shapeMode.get(), 0);
        }
    }

    // Function

    @Override
    public void onActivate() {
        // 居中
        if (center.get() == Center.OnActivate) PlayerUtils.centerPlayer();

        // 联动关闭其他模块
        if (toggleModules.get() && !modules.get().isEmpty() && mc.world != null && mc.player != null) {
            for (Module module : modules.get()) {
                if (module.isActive()) {
                    module.toggle();
                    toActivate.add(module);
                }
            }
        }

        // 注册为 Printer 输入源（Printer 管线统一处理放置、旋转、slot 切换）
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
     * 返回当前需要放置的目标位置集合（所有仍为可替换状态的 Surround 位置 + body 下肢的 support 块）。
     * <p>Printer 模块调用此方法将 Surround 的任务注入其统一管线。</p>
     */
    @Override
    public Collection<BlockPos> getPlacementPositions() {
        if (mc.player == null || mc.world == null) return Collections.emptyList();

        int playerY = mc.player.getBlockPos().getY();
        List<BlockPos> needed = new ArrayList<>();

        for (BlockPos pos : computeAllTargetPositions()) {
            if (!mc.world.getBlockState(pos).isReplaceable()) continue;
            needed.add(pos);

            // Body 下肢（y+0）的 support 块：位置悬空时需要先垫一格
            if (pos.getY() == playerY && !airPlace.get() && isAirPlace(pos)) {
                BlockPos support = pos.down();
                if (mc.world.getBlockState(support).isReplaceable()) needed.add(support);
            }
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
        if (mc.player == null || mc.world == null) return;

        // Y 坐标变化 → 关闭（跳跃/踩高后 Surround 位置失效）
        if (toggleOnYChange.get() && mc.player.lastY != mc.player.getY()) {
            toggle();
            return;
        }

        // 仅地面工作
        if (onlyOnGround.get() && !mc.player.isOnGround()) return;

        // Printer 管线负责实际放置，Surround 只负责水晶防护 + 居中 + 状态检测
        List<BlockPos> needed = new ArrayList<>(getPlacementPositions());
        boolean complete = needed.isEmpty();

        // ── 水晶防护：检测并攻击阻挡 Surround 位置的 EndCrystal ──
        if (protect.get() && !complete) {
            tickCrystalProtection(needed);
        }

        // ── 居中控制 ──
        if (!complete && center.get() == Center.Incomplete) PlayerUtils.centerPlayer();
        if (complete && center.get() == Center.Always) PlayerUtils.centerPlayer();

        // ── 自动关闭 ──
        if (complete && toggleOnComplete.get()) {
            toggle();
        }
    }

    // ==================== 水晶防护 ====================

    /**
     * 检测 Surround 空位上阻挡放置的 EndCrystal，执行攻击（+ 可选同 tick 放置）。
     *
     * <p><b>默认模式（2-tick 安全）</b>：
     * Tick 1 攻击水晶（priority=200 抢占 Printer 旋转）→
     * Tick 2 Printer 自然填补（EndCrystal 不在 Printer.hasBlockingEntity 过滤中）。
     * 不触发任何 GrimAC 检测。</p>
     *
     * <p><b>激进模式（aggressiveProtect, 1-tick）</b>：
     * 同 callback 内先 Attack 后 Place → TCP 保序 → 服务端先清水晶后放方块。
     * 可能触发 GrimAC MultiActionsF（experimental, 大多数服务器未开启）。
     * 若被 flag，Place 被 resync 但 Attack 仍然生效 → 退化为 2-tick。</p>
     *
     * @param needed 当前需要填补的 Surround 位置列表
     * @return true 如果提交了旋转请求（本 tick Surround 接管旋转优先权）
     */
    private boolean tickCrystalProtection(List<BlockPos> needed) {
        Vec3d eye = mc.player.getEyePos();

        for (BlockPos pos : needed) {
            if (!mc.world.getBlockState(pos).isReplaceable()) continue;

            // 查找阻挡该位置的 EndCrystal
            List<EndCrystalEntity> crystals = mc.world.getEntitiesByClass(
                EndCrystalEntity.class, new Box(pos), Entity::isAlive);
            if (crystals.isEmpty()) continue;

            EndCrystalEntity crystal = crystals.getFirst();
            if (eye.distanceTo(crystal.getPos()) > ATTACK_REACH) continue;

            // 激进模式：尝试同 tick Attack + Place
            if (aggressiveProtect.get()) {
                return tickAggressiveProtect(pos, crystal);
            }

            // 安全模式：仅攻击，放置留给下 tick Printer
            Rotations.rotateToward(crystal.getPos(), PROTECT_ROTATION_PRIORITY, () -> {
                sendCrystalAttack(crystal);
            });
            return true;
        }

        return false;
    }

    /**
     * 激进防护：同 tick Attack + Place。
     * 旋转朝 placement hitVec（覆盖水晶碰撞检测范围），callback 内先发 Attack 再发 Place。
     * 服务端按序处理：水晶死 → 方块放。
     * 若客户端已无水晶（延迟确认），退化为纯 Place。
     */
    private boolean tickAggressiveProtect(BlockPos pos, EndCrystalEntity crystal) {
        // 准备放置参数
        FindItemResult item = InvUtils.findInHotbar(
            s -> blocks.get().contains(Block.getBlockFromItem(s.getItem())));
        if (!item.found()) {
            // 没有方块 → 只攻击
            Rotations.rotateToward(crystal.getPos(), PROTECT_ROTATION_PRIORITY, () -> {
                sendCrystalAttack(crystal);
            });
            return true;
        }

        BlockState desired = blocks.get().isEmpty()
            ? Blocks.OBSIDIAN.getDefaultState()
            : blocks.get().getFirst().getDefaultState();
        PlacementContext ctx = PlacementContext.of(mc.world, pos, desired, mc.player, true, false, 4.5);
        PlacementOption option = ResolverRegistry.resolve(ctx);

        if (option == null || option.hitVec() == null) {
            // 无合法面 → 只攻击
            Rotations.rotateToward(crystal.getPos(), PROTECT_ROTATION_PRIORITY, () -> {
                sendCrystalAttack(crystal);
            });
            return true;
        }

        // 构建 place 参数
        BlockPos interactPos = option.getInteractPos(pos);
        Direction clickedFace = option.getClickedFace();
        BlockHitResult hitResult = new BlockHitResult(option.hitVec(), clickedFace, interactPos, false);
        int prevSlot = mc.player.getInventory().getSelectedSlot();
        boolean needSwap = !item.isOffhand() && item.slot() != prevSlot;
        Hand hand = item.isOffhand() ? Hand.OFF_HAND : Hand.MAIN_HAND;

        // 旋转朝 placement hitVec（水晶在同方向，碰撞箱够大，一个旋转覆盖两个检测）
        Rotations.rotateToward(option.hitVec(), PROTECT_ROTATION_PRIORITY, () -> {
            // ① 攻击水晶（无需特定手持物，先发以最快清除实体）
            sendCrystalAttack(crystal);

            // ② 切换到方块 slot（仅 Place 需要）
            if (needSwap) InvUtils.swap(item.slot(), false);

            // ③ 放置方块（服务端紧接处理：水晶死 → 位置空 → 方块放）
            int seq = mc.world.getPendingUpdateManager().incrementSequence().getSequence();
            mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(hand, hitResult, seq));

            // ④ 恢复原 slot
            if (needSwap) InvUtils.swap(prevSlot, false);
        });

        return true;
    }

    /** 发送攻击水晶的数据包（含 swing 动画） */
    private void sendCrystalAttack(Entity crystal) {
        mc.player.networkHandler.sendPacket(
            PlayerInteractEntityC2SPacket.attack(crystal, mc.player.isSneaking()));
        if (swing.get()) {
            mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
        }
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

    public enum HeadMode {
        None,
        Single,
        Full
    }

    public enum BodyMode {
        None,
        Lower,
        Upper,
        Full
    }

    public enum FootMode {
        None,
        Single,
        Full
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
