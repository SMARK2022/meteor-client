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
    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sgBody     = settings.createGroup("身体");
    private final SettingGroup sgHead     = settings.createGroup("头部");
    private final SettingGroup sgFoot     = settings.createGroup("脚部");
    private final SettingGroup sgProtect  = settings.createGroup("防护");
    private final SettingGroup sgLink     = settings.createGroup("联动");
    private final SettingGroup sgAutoOff  = settings.createGroup("自动关闭");
    private final SettingGroup sgRender   = settings.createGroup("渲染");

    // ── 通用 ──

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("方块列表")
        .description("包围使用的方块类型。")
        .defaultValue(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.NETHERITE_BLOCK)
        .filter(this::blockFilter)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("放置延迟")
        .description("每次放置之间的间隔（tick）。")
        .min(0)
        .defaultValue(0)
        .visible(() -> !Printer.isProviderRegistered(this))
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("每 tick 放置数")
        .description("单个 tick 内最多放置的方块数。")
        .defaultValue(1)
        .min(1)
        .visible(() -> !Printer.isProviderRegistered(this))
        .build()
    );

    private final Setting<Center> center = sgGeneral.add(new EnumSetting.Builder<Center>()
        .name("居中")
        .description("将玩家传送到方块中心。注意：可能触发 GrimAC 移动检测。")
        .defaultValue(Center.Never)
        .build()
    );

    private final Setting<Boolean> onlyOnGround = sgGeneral.add(new BoolSetting.Builder()
        .name("仅地面")
        .description("仅在站立在方块上时工作。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> airPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("空中放置")
        .description("允许在无邻面支撑时放置方块。")
        .defaultValue(false)        .visible(() -> !Printer.isProviderRegistered(this))        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("旋转")
        .description("放置时自动朝向目标方块。")
        .defaultValue(true)
        .visible(() -> !Printer.isProviderRegistered(this))
        .build()
    );

    // ── 身体 ──

    private final Setting<BodyMode> bodyMode = sgBody.add(new EnumSetting.Builder<BodyMode>()
        .name("身体模式")
        .description("身体保护。Lower=下肢 y+0 四面；Upper=上肢 y+1 四面；Full=全身 8 面。")
        .defaultValue(BodyMode.Full)
        .build()
    );

    // ── 头部 ──

    private final Setting<HeadMode> headMode = sgHead.add(new EnumSetting.Builder<HeadMode>()
        .name("头部模式")
        .description("头顶保护。Single=y+2 单方块；Full=y+2 十字形 5 方块。")
        .defaultValue(HeadMode.Full)
        .build()
    );

    // ── 脚部 ──

    private final Setting<FootMode> footMode = sgFoot.add(new EnumSetting.Builder<FootMode>()
        .name("脚部模式")
        .description("脚下保护。Single=y-1 单方块；Full=y-1 十字形 5 方块。")
        .defaultValue(FootMode.Full)
        .build()
    );

    // ── 防护 ──

    private final Setting<Boolean> protect = sgProtect.add(new BoolSetting.Builder()
        .name("防护")
        .description("在包围位置附近打碎水晶以防止被破围。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swing = sgProtect.add(new BoolSetting.Builder()
        .name("挥手")
        .description("放置/攻击时渲染挥手动画。")
        .defaultValue(true)
        .visible(() -> !Printer.isProviderRegistered(this))
        .build()
    );

    // ── 联动 ──

    private final Setting<Boolean> toggleModules = sgLink.add(new BoolSetting.Builder()
        .name("联动关闭")
        .description("激活时关闭其他模块。")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> toggleBack = sgLink.add(new BoolSetting.Builder()
        .name("联动恢复")
        .description("关闭时恢复被联动关闭的模块。")
        .defaultValue(false)
        .visible(toggleModules::get)
        .build()
    );

    private final Setting<List<Module>> modules = sgLink.add(new ModuleListSetting.Builder()
        .name("联动模块")
        .description("激活时需要关闭的模块列表。")
        .visible(toggleModules::get)
        .build()
    );

    // ── 自动关闭 ──

    private final Setting<Boolean> toggleOnYChange = sgAutoOff.add(new BoolSetting.Builder()
        .name("Y 变化关闭")
        .description("Y 坐标变化时自动关闭（跳跃、踩高等）。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> toggleOnComplete = sgAutoOff.add(new BoolSetting.Builder()
        .name("完成关闭")
        .description("所有方块放置完成后自动关闭。")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> toggleOnDeath = sgAutoOff.add(new BoolSetting.Builder()
        .name("死亡关闭")
        .description("死亡时自动关闭。")
        .defaultValue(true)
        .build()
    );

    // ── 渲染 ──

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("渲染")
        .description("渲染方块放置位置的叠加层。")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("渲染模式")
        .description("叠加层的渲染方式。")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> safeSideColor = sgRender.add(new ColorSetting.Builder()
        .name("安全-面颜色")
        .description("安全方块（基岩等）的面颜色。")
        .defaultValue(new SettingColor(13, 255, 0, 0))
        .visible(() -> render.get() && shapeMode.get() != ShapeMode.Lines)
        .build()
    );

    private final Setting<SettingColor> safeLineColor = sgRender.add(new ColorSetting.Builder()
        .name("安全-线颜色")
        .description("安全方块（基岩等）的线颜色。")
        .defaultValue(new SettingColor(13, 255, 0, 0))
        .visible(() -> render.get() && shapeMode.get() != ShapeMode.Sides)
        .build()
    );

    private final Setting<SettingColor> normalSideColor = sgRender.add(new ColorSetting.Builder()
        .name("普通-面颜色")
        .description("普通方块（黑曜石等）的面颜色。")
        .defaultValue(new SettingColor(0, 255, 238, 12))
        .visible(() -> render.get() && shapeMode.get() != ShapeMode.Lines)
        .build()
    );

    private final Setting<SettingColor> normalLineColor = sgRender.add(new ColorSetting.Builder()
        .name("普通-线颜色")
        .description("普通方块（黑曜石等）的线颜色。")
        .defaultValue(new SettingColor(0, 255, 238, 100))
        .visible(() -> render.get() && shapeMode.get() != ShapeMode.Sides)
        .build()
    );

    private final Setting<SettingColor> unsafeSideColor = sgRender.add(new ColorSetting.Builder()
        .name("危险-面颜色")
        .description("危险方块（可破坏方块）的面颜色。")
        .defaultValue(new SettingColor(204, 0, 0, 12))
        .visible(() -> render.get() && shapeMode.get() != ShapeMode.Lines)
        .build()
    );

    private final Setting<SettingColor> unsafeLineColor = sgRender.add(new ColorSetting.Builder()
        .name("危险-线颜色")
        .description("危险方块（可破坏方块）的线颜色。")
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

        // Body: 下肢 y+0
        boolean doLower = bodyMode.get() == BodyMode.Lower || bodyMode.get() == BodyMode.Full;
        if (doLower) {
            for (Direction direction : Direction.HORIZONTAL) {
                draw(playerPos.offset(direction), event, 0);
            }
        }

        // Body: 上肢 y+1
        boolean doUpper = bodyMode.get() == BodyMode.Upper || bodyMode.get() == BodyMode.Full;
        if (doUpper) {
            for (Direction direction : Direction.HORIZONTAL) {
                draw(playerPos.offset(direction).up(), event, 0);
            }
        }

        // Head: 头顶 y+2
        if (headMode.get() == HeadMode.Single || headMode.get() == HeadMode.Full) {
            draw(playerPos.add(0, 2, 0), event, 0);
        }

        // Head: 十字形 y+2
        if (headMode.get() == HeadMode.Full) {
            for (Direction direction : Direction.HORIZONTAL) {
                draw(playerPos.add(0, 2, 0).offset(direction), event, 0);
            }
        }

        // Foot: 脚下 y-1
        if (footMode.get() == FootMode.Single || footMode.get() == FootMode.Full) {
            draw(playerPos.down(), event, 0);
        }

        // Foot: 十字形 y-1
        if (footMode.get() == FootMode.Full) {
            for (Direction direction : Direction.HORIZONTAL) {
                draw(playerPos.down().offset(direction), event, 0);
            }
        }
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

        // Body: 下肢 y+0 四面
        boolean doLower = bodyMode.get() == BodyMode.Lower || bodyMode.get() == BodyMode.Full;
        if (doLower) {
            for (Direction dir : Direction.HORIZONTAL) {
                BlockPos pos = playerPos.offset(dir);
                if (mc.world.getBlockState(pos).isReplaceable()) needed.add(pos);

                // 空中放置时 support 格
                if (!airPlace.get() && isAirPlace(pos) && mc.world.getBlockState(pos).isReplaceable()) {
                    BlockPos support = pos.down();
                    if (mc.world.getBlockState(support).isReplaceable()) needed.add(support);
                }
            }
        }

        // Body: 上肢 y+1 四面
        boolean doUpper = bodyMode.get() == BodyMode.Upper || bodyMode.get() == BodyMode.Full;
        if (doUpper) {
            for (Direction dir : Direction.HORIZONTAL) {
                BlockPos pos = playerPos.offset(dir).up();
                if (mc.world.getBlockState(pos).isReplaceable()) needed.add(pos);
            }
        }

        // Head: 头顶 y+2
        if (headMode.get() == HeadMode.Single || headMode.get() == HeadMode.Full) {
            BlockPos pos = playerPos.add(0, 2, 0);
            if (mc.world.getBlockState(pos).isReplaceable()) needed.add(pos);
        }

        // Head: 十字形 y+2 周围四面（Full 模式）
        if (headMode.get() == HeadMode.Full) {
            for (Direction dir : Direction.HORIZONTAL) {
                BlockPos pos = playerPos.add(0, 2, 0).offset(dir);
                if (mc.world.getBlockState(pos).isReplaceable()) needed.add(pos);
            }
        }

        // Foot: 脚下 y-1
        if (footMode.get() == FootMode.Single || footMode.get() == FootMode.Full) {
            BlockPos pos = playerPos.down();
            if (mc.world.getBlockState(pos).isReplaceable()) needed.add(pos);
        }

        // Foot: 十字形 y-1 周围四面（Full 模式）
        if (footMode.get() == FootMode.Full) {
            for (Direction dir : Direction.HORIZONTAL) {
                BlockPos pos = playerPos.down().offset(dir);
                if (mc.world.getBlockState(pos).isReplaceable()) needed.add(pos);
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
        // Delay
        if (timer++ < delay.get()) return;

        // Toggle if Y level changed
        if (toggleOnYChange.get() && mc.player.lastY != mc.player.getY()) {
            toggle();
            return;
        }

        // Wait till player is on ground
        if (onlyOnGround.get() && !mc.player.isOnGround()) return;

        // 如果已注册为 Printer 输入源，让出控制权（Printer 管线自动处理，无论 Printer 模块是否启用）
        if (Printer.isProviderRegistered(this)) {
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

        // Body: 下肢 y+0
        boolean doLower = bodyMode.get() == BodyMode.Lower || bodyMode.get() == BodyMode.Full;
        if (doLower) {
            for (Direction direction : Direction.HORIZONTAL) {
                BlockPos placePos = playerPos.offset(direction);

                if (!airPlace.get() && isAirPlace(placePos) && mc.world.getBlockState(placePos).isReplaceable()) {
                    if (placeSafe(placePos.down(), block) && ++placedCount >= blocksPerTick.get()) break;
                    if (mc.world.getBlockState(placePos.down()).isReplaceable()) complete = false;
                }

                if (placeSafe(placePos, block) && ++placedCount >= blocksPerTick.get()) break;
                if (mc.world.getBlockState(placePos).isReplaceable()) complete = false;
            }
        }

        // Body: 上肢 y+1
        boolean doUpper = bodyMode.get() == BodyMode.Upper || bodyMode.get() == BodyMode.Full;
        if (doUpper && placedCount < blocksPerTick.get()) {
            for (Direction direction : Direction.HORIZONTAL) {
                BlockPos placePos = playerPos.offset(direction).up();
                if (placeSafe(placePos, block) && ++placedCount >= blocksPerTick.get()) break;
                if (mc.world.getBlockState(placePos).isReplaceable()) complete = false;
            }
        }

        // Head: 头顶 y+2
        if ((headMode.get() == HeadMode.Single || headMode.get() == HeadMode.Full) && placedCount < blocksPerTick.get()) {
            BlockPos placePos = playerPos.add(0, 2, 0);
            if (placeSafe(placePos, block)) placedCount++;
            if (mc.world.getBlockState(placePos).isReplaceable()) complete = false;
        }

        // Head: 十字形 y+2
        if (headMode.get() == HeadMode.Full && placedCount < blocksPerTick.get()) {
            for (Direction direction : Direction.HORIZONTAL) {
                BlockPos placePos = playerPos.add(0, 2, 0).offset(direction);
                if (placeSafe(placePos, block) && ++placedCount >= blocksPerTick.get()) break;
                if (mc.world.getBlockState(placePos).isReplaceable()) complete = false;
            }
        }

        // Foot: 脚下 y-1
        if ((footMode.get() == FootMode.Single || footMode.get() == FootMode.Full) && placedCount < blocksPerTick.get()) {
            BlockPos placePos = playerPos.down();
            if (placeSafe(placePos, block)) placedCount++;
            if (mc.world.getBlockState(placePos).isReplaceable()) complete = false;
        }

        // Foot: 十字形 y-1
        if (footMode.get() == FootMode.Full && placedCount < blocksPerTick.get()) {
            for (Direction direction : Direction.HORIZONTAL) {
                BlockPos placePos = playerPos.down().offset(direction);
                if (placeSafe(placePos, block) && ++placedCount >= blocksPerTick.get()) break;
                if (mc.world.getBlockState(placePos).isReplaceable()) complete = false;
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

        // 位置已有不可替换方块 → 无需放置
        if (!mc.world.getBlockState(placePos).isReplaceable()) return false;

        boolean placed = false;

        // 获取物品信息并构建放置上下文
        net.minecraft.item.ItemStack stack = mc.player.getInventory().getStack(item.isOffhand() ? 40 : item.slot());
        Block blockToPlace = Block.getBlockFromItem(stack.getItem());
        BlockState state = blockToPlace.getDefaultState();

        PlacementContext ctx = PlacementContext.of(mc.world, placePos, state, mc.player, true, false, 4.5);
        PlacementOption option = ResolverRegistry.resolve(ctx);

        if (option != null && option.hitVec() != null) {
            BlockPos interactPos = option.getInteractPos(placePos);
            Direction clickedFace = option.getClickedFace();
            BlockHitResult hitResult = new BlockHitResult(option.hitVec(), clickedFace, interactPos, false);

            // 切换到持有目标方块的槽位（非副手时），放置后恢复
            int prevSlot = mc.player.getInventory().getSelectedSlot();
            boolean needSwap = !item.isOffhand() && item.slot() != prevSlot;
            Hand hand = item.isOffhand() ? Hand.OFF_HAND : Hand.MAIN_HAND;

            if (needSwap) InvUtils.swap(item.slot(), false);

            if (rotate.get()) {
                Vec3d hv = option.hitVec();
                double dx = hv.x - mc.player.getX();
                double dy = hv.y - mc.player.getEyeY();
                double dz = hv.z - mc.player.getZ();
                double yaw = Math.toDegrees(Math.atan2(-dx, dz));
                double pitch = Math.toDegrees(Math.atan2(-dy, Math.sqrt(dx * dx + dz * dz)));
                Hand fHand = hand;
                BlockHitResult fHit = hitResult;
                int fPrevSlot = prevSlot;
                boolean fNeedSwap = needSwap;
                Rotations.rotate(yaw, pitch, () -> {
                    int seq = mc.world.getPendingUpdateManager().incrementSequence().getSequence();
                    mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(fHand, fHit, seq));
                    if (fNeedSwap) InvUtils.swap(fPrevSlot, false);
                });
            } else {
                int seq = mc.world.getPendingUpdateManager().incrementSequence().getSequence();
                mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(hand, hitResult, seq));
                if (needSwap) InvUtils.swap(prevSlot, false);
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
