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
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.packets.InventoryEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.ItemSwitchHelper;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.printer.ActionPlan;
import meteordevelopment.meteorclient.utils.printer.ActionPlan.SneakPolicy;
import meteordevelopment.meteorclient.utils.printer.ActionPlan.HandPolicy;
import meteordevelopment.meteorclient.utils.printer.BlockUtilHelper;
import meteordevelopment.meteorclient.utils.printer.ContainerFillManager;
import meteordevelopment.meteorclient.utils.printer.PrinterBehavior;
import meteordevelopment.meteorclient.utils.printer.PrinterTask;
import meteordevelopment.meteorclient.utils.printer.PrinterTaskProvider;
import meteordevelopment.meteorclient.utils.printer.behavior.*;
import meteordevelopment.meteorclient.utils.printer.ContainerFillScreen;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.SlabType;
import net.minecraft.fluid.Fluids;
import net.minecraft.state.property.Properties;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
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

    // ==================== 外挂输入源注册表 ====================

    private static final List<PrinterTaskProvider> providers = new ArrayList<>();

    /** 注册输入源（外挂模块在 onActivate 时调用） */
    public static void registerProvider(PrinterTaskProvider provider) {
        if (!providers.contains(provider)) {
            providers.add(provider);
            providers.sort(Comparator.comparingInt(p -> -p.priority()));
        }
        ensureSubscribed();
    }

    /** 注销输入源（外挂模块在 onDeactivate 时调用） */
    public static void unregisterProvider(PrinterTaskProvider provider) {
        providers.remove(provider);
        ensureSubscribed();
    }

    /**
     * 确保事件订阅状态与需求一致：
     * - 模块启用 或 有 provider → 订阅事件
     * - 都没有 → 取消订阅
     */
    private static void ensureSubscribed() {
        Printer printer = Modules.get().get(Printer.class);
        if (printer == null) return;
        boolean need = printer.isActive() || !providers.isEmpty();
        if (need && !printer.subscribedForProviders) {
            MeteorClient.EVENT_BUS.subscribe(printer);
            printer.subscribedForProviders = true;
        } else if (!need && printer.subscribedForProviders) {
            MeteorClient.EVENT_BUS.unsubscribe(printer);
            printer.subscribedForProviders = false;
        }
    }

    /** 查询指定输入源是否已注册 */
    public static boolean isProviderRegistered(PrinterTaskProvider provider) {
        return providers.contains(provider);
    }

    // ==================== 设置 ====================

    private final SettingGroup sgGeneral   = settings.getDefaultGroup();
    private final SettingGroup sgFixes     = settings.createGroup("Correction");
    private final SettingGroup sgContainer = settings.createGroup("Container Fill");
    private final SettingGroup sgRender    = settings.createGroup("Render");

    // ── 通用 ──

    private final Setting<Boolean> moveStop = sgGeneral.add(new BoolSetting.Builder()
            .name("pause-on-move")
            .description("Pauses placement while moving.")
            .defaultValue(false)
            .build());

    private final Setting<PlaceMode> placeMode = sgGeneral.add(new EnumSetting.Builder<PlaceMode>()
            .name("place-mode")
            .description("Placement mode. STRICT uses NCP direction validation to bypass anti-cheat.")
            .defaultValue(PlaceMode.STRICT)
            .build());

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
            .name("place-delay")
            .description("Delay in ticks between placements.")
            .defaultValue(0)
            .min(0)
            .sliderRange(0, 10)
            .build());

    private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
            .name("place-range")
            .description("Placement range in blocks.")
            .defaultValue(4.5)
            .min(1.0)
            .max(15.0)
            .sliderRange(1.0, 8.0)
            .build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
            .name("rotate")
            .description("Rotates towards the target block.")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> swingHand = sgGeneral.add(new BoolSetting.Builder()
            .name("swing")
            .description("Plays swing animation when interacting.")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> checkLineOfSight = sgGeneral.add(new BoolSetting.Builder()
            .name("line-of-sight")
            .description("Only places blocks visible to the player (anti-cheat).")
            .defaultValue(true)
            .visible(() -> placeMode.get() == PlaceMode.STRICT)
            .build());

    private final Setting<Integer> maxCandidates = sgGeneral.add(new IntSetting.Builder()
            .name("max-candidates")
            .description("Maximum tasks to evaluate per tick, 0=unlimited.")
            .defaultValue(0)
            .min(0)
            .sliderRange(0, 100)
            .build());

    // ── 行为修正 · 红石 ──

    private final Setting<Boolean> fixRedstone = sgFixes.add(new BoolSetting.Builder()
            .name("redstone-fix")
            .description("Enables redstone component state correction.")
            .defaultValue(false)
            .build());

    private final Setting<Boolean> fixRepeaterDelay = sgFixes.add(new BoolSetting.Builder()
            .name("repeater-delay")
            .description("Fixes repeater delay mismatch.")
            .defaultValue(true)
            .visible(fixRedstone::get)
            .build());

    private final Setting<Boolean> fixComparatorMode = sgFixes.add(new BoolSetting.Builder()
            .name("comparator-mode")
            .description("Fixes comparator mode mismatch.")
            .defaultValue(true)
            .visible(fixRedstone::get)
            .build());

    private final Setting<Boolean> fixRedstoneWire = sgFixes.add(new BoolSetting.Builder()
            .name("redstone-wire-shape")
            .description("Fixes redstone wire cross/dot shape.")
            .defaultValue(true)
            .visible(fixRedstone::get)
            .build());

    private final Setting<Boolean> fixNoteBlock = sgFixes.add(new BoolSetting.Builder()
            .name("note-block-pitch")
            .description("Fixes note block pitch.")
            .defaultValue(true)
            .visible(fixRedstone::get)
            .build());

    // ── 行为修正 · 交互方块 ──

    private final Setting<Boolean> fixInteractable = sgFixes.add(new BoolSetting.Builder()
            .name("interactable-fix")
            .description("Enables interactable block state correction.")
            .defaultValue(false)
            .build());

    private final Setting<Boolean> fixTrapdoor = sgFixes.add(new BoolSetting.Builder()
            .name("trapdoor")
            .description("Fixes trapdoor open/close state.")
            .defaultValue(true)
            .visible(fixInteractable::get)
            .build());

    private final Setting<Boolean> fixDoor = sgFixes.add(new BoolSetting.Builder()
            .name("door")
            .description("Fixes door open/close state.")
            .defaultValue(true)
            .visible(fixInteractable::get)
            .build());

    private final Setting<Boolean> fixFenceGate = sgFixes.add(new BoolSetting.Builder()
            .name("fence-gate")
            .description("Fixes fence gate open/close state.")
            .defaultValue(true)
            .visible(fixInteractable::get)
            .build());

    private final Setting<Boolean> fixDaylightDetector = sgFixes.add(new BoolSetting.Builder()
            .name("daylight-sensor")
            .description("Fixes daylight sensor inverted state.")
            .defaultValue(true)
            .visible(fixInteractable::get)
            .build());

    private final Setting<Boolean> fixLever = sgFixes.add(new BoolSetting.Builder()
            .name("lever")
            .description("Fixes lever toggle state.")
            .defaultValue(true)
            .visible(fixInteractable::get)
            .build());

    private final Setting<Boolean> fixCampfire = sgFixes.add(new BoolSetting.Builder()
            .name("campfire")
            .description("Fixes campfire lit state (shovel extinguish / flint ignite).")
            .defaultValue(true)
            .visible(fixInteractable::get)
            .build());

    // ── 行为修正 · 流体 ──

    private final Setting<Boolean> placeFluid = sgFixes.add(new BoolSetting.Builder()
            .name("fluid-fix")
            .description("Enables fluid placement and waterlogging correction.")
            .defaultValue(false)
            .build());

    private final Setting<Boolean> placeFluidSource = sgFixes.add(new BoolSetting.Builder()
            .name("water-source")
            .description("Places water/lava sources using buckets.")
            .defaultValue(true)
            .visible(placeFluid::get)
            .build());

    private final Setting<Boolean> fixWaterlog = sgFixes.add(new BoolSetting.Builder()
            .name("waterlogging")
            .description("Adds water to waterloggable blocks.")
            .defaultValue(true)
            .visible(placeFluid::get)
            .build());

    // ── 行为修正 · 方块容忍与破坏 ──

    private final Setting<Boolean> tolerateDirt = sgFixes.add(new BoolSetting.Builder()
            .name("dirt-equivalent")
            .description("Treats dirt and grass blocks as equivalent.")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> breakMismatched = sgFixes.add(new BoolSetting.Builder()
            .name("break-mismatch")
            .description("Breaks blocks that don't match the schematic (requires PacketMine).")
            .defaultValue(false)
            .build());

    private final Setting<Boolean> tolerateExtraDirt = sgFixes.add(new BoolSetting.Builder()
            .name("tolerate-extra-dirt")
            .description("Don't break dirt/grass blocks where schematic expects air.")
            .defaultValue(false)
            .visible(breakMismatched::get)
            .build());

    private final Setting<Boolean> tolerateScaffolding = sgFixes.add(new BoolSetting.Builder()
            .name("tolerate-scaffolding")
            .description("Don't break scaffolding where schematic expects air.")
            .defaultValue(true)
            .visible(breakMismatched::get)
            .build());

    // Key → sub-toggle mapping (populated in constructor)
    private final Map<PrinterBehavior.Key, Setting<Boolean>> subToggles = new EnumMap<>(PrinterBehavior.Key.class);

    // ── 容器填充 ──

    private final Setting<Boolean> fillContainers = sgContainer.add(new BoolSetting.Builder()
            .name("container-fill")
            .description("Auto-fills container contents (dispenser/dropper/hopper/barrel), Grim safe.")
            .defaultValue(false)
            .build());

    private final Setting<ContainerFillManager.OverfillPolicy> overfillPolicy = sgContainer.add(new EnumSetting.Builder<ContainerFillManager.OverfillPolicy>()
            .name("overfill-strategy")
            .description("FAST=move whole stacks (fastest but may overfill). PRECISE=place one by one.")
            .defaultValue(ContainerFillManager.OverfillPolicy.PRECISE)
            .visible(fillContainers::get)
            .build());

    private final Setting<Boolean> containerHighlight = sgContainer.add(new BoolSetting.Builder()
            .name("container-highlight")
            .description("Highlights unfulfilled schematic containers in the world.")
            .defaultValue(true)
            .visible(fillContainers::get)
            .build());

    private final Setting<SettingColor> containerSideColor = sgContainer.add(new ColorSetting.Builder()
            .name("container-side-color")
            .description("The side color for unfulfilled container highlight.")
            .defaultValue(new SettingColor(255, 100, 50, 40))
            .visible(() -> fillContainers.get() && containerHighlight.get())
            .build());

    private final Setting<SettingColor> containerLineColor = sgContainer.add(new ColorSetting.Builder()
            .name("container-line-color")
            .description("The line color for unfulfilled container highlight.")
            .defaultValue(new SettingColor(255, 100, 50, 200))
            .visible(() -> fillContainers.get() && containerHighlight.get())
            .build());

    private final Setting<Boolean> containerOverlay = sgContainer.add(new BoolSetting.Builder()
            .name("container-hud")
            .description("Screen HUD overlay showing container type stats and item requirements.")
            .defaultValue(true)
            .visible(fillContainers::get)
            .build());

    private final Setting<Integer> containerInfoRange = sgContainer.add(new IntSetting.Builder()
            .name("detail-range")
            .description("Query range for container detail view (blocks).")
            .defaultValue(64)
            .min(8)
            .sliderRange(8, 128)
            .visible(fillContainers::get)
            .build());

    private final Setting<Keybind> containerScreenKey = sgContainer.add(new KeybindSetting.Builder()
            .name("detail-key")
            .description("Press to open container fill detail view.")
            .defaultValue(Keybind.none())
            .visible(fillContainers::get)
            .action(this::openContainerFillScreen)
            .build());

    // ── 渲染 ──

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
            .name("render")
            .description("Renders blocks about to be placed or interacted with.")
            .defaultValue(true)
            .build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
            .name("shape-mode")
            .description("How the shapes are rendered.")
            .defaultValue(ShapeMode.Both)
            .build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
            .name("place-side-color")
            .description("The side color for block placement preview.")
            .defaultValue(new SettingColor(20, 200, 20, 50))
            .build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
            .name("place-line-color")
            .description("The line color for block placement preview.")
            .defaultValue(new SettingColor(20, 200, 20, 255))
            .build());

    private final Setting<SettingColor> behaviorSideColor = sgRender.add(new ColorSetting.Builder()
            .name("fix-side-color")
            .description("The side color for correction tasks.")
            .defaultValue(new SettingColor(200, 160, 20, 50))
            .build());

    private final Setting<SettingColor> behaviorLineColor = sgRender.add(new ColorSetting.Builder()
            .name("fix-line-color")
            .description("The line color for correction tasks.")
            .defaultValue(new SettingColor(200, 160, 20, 255))
            .build());

    private final Setting<SettingColor> plannedSideColor = sgRender.add(new ColorSetting.Builder()
            .name("plan-side-color")
            .description("The side color for planned placement target.")
            .defaultValue(new SettingColor(20, 200, 255, 80))
            .build());

    private final Setting<SettingColor> plannedLineColor = sgRender.add(new ColorSetting.Builder()
            .name("plan-line-color")
            .description("The line color for planned placement target.")
            .defaultValue(new SettingColor(20, 200, 255, 255))
            .build());

    private final Setting<Boolean> renderHitVec = sgRender.add(new BoolSetting.Builder()
            .name("hit-point")
            .description("Renders a small block at the block placement hit point.")
            .defaultValue(true)
            .build());

    private final Setting<SettingColor> hitVecColor = sgRender.add(new ColorSetting.Builder()
            .name("hit-point-color")
            .description("The color of the hit point block.")
            .defaultValue(new SettingColor(255, 100, 100, 255))
            .build());

    private final Setting<Boolean> renderUnsupported = sgRender.add(new BoolSetting.Builder()
            .name("unsupported-blocks")
            .description("Renders blocks that differ from schematic but have no matching action.")
            .defaultValue(false)
            .build());

    private final Setting<SettingColor> unsupportedSideColor = sgRender.add(new ColorSetting.Builder()
            .name("unsupported-side-color")
            .description("The side color for unsupported blocks.")
            .defaultValue(new SettingColor(255, 160, 0, 30))
            .visible(renderUnsupported::get)
            .build());

    private final Setting<SettingColor> unsupportedLineColor = sgRender.add(new ColorSetting.Builder()
            .name("unsupported-line-color")
            .description("The line color for unsupported blocks.")
            .defaultValue(new SettingColor(255, 160, 0, 180))
            .visible(renderUnsupported::get)
            .build());

    // ==================== 动作计划快照 ====================

    /**
     * ArmedAction - 已就绪的动作（含计划和确认的手）
     * 在 TickEvent.Pre 中创建，在 SendMovementPacketsEvent.Post 中消费。
     */
    private record ArmedAction(ActionPlan plan, Hand hand) {}

    /**
     * PreviewCandidate - 通过完整 behavior.plan() 过滤的候选（含评分）
     *
     * <p>只有成功生成 ActionPlan 的任务才会进入此层。
     * 渲染层和选拔层都基于此列表，而非原始的 {@code tasks}。
     */
    private record PreviewCandidate(PlannedTask plannedTask, ActionPlan plan, double score) {}

    /**
     * 潜行准备结果
     */
    private enum SneakReadiness { READY, PREPARING, BLOCKED }

    /**
     * 选拔结果：明确区分"已就绪"/"等待准备同步"/"无匹配"三态。
     * 替代原先 ArmedAction + preparingInputThisTick 布尔标记的隐式通信。
     */
    private sealed interface SelectionResult {
        /** 已就绪，plan 和 hand 可直接执行 */
        record Ready(ArmedAction armed) implements SelectionResult {}
        /** 本 tick 触发了 sneak/物品切换，等下一 tick 同步后再执行 */
        record AwaitingPrep() implements SelectionResult {}
        /** 无匹配候选或全部 BLOCKED */
        record None() implements SelectionResult {}
    }

    // ==================== 内部状态 ====================

    /**
     * PlannedTask - 携带匹配行为的任务
     * 扫描阶段即绑定行为，避免 selectBestAction 二次查找。
     */
    private record PlannedTask(PrinterTask task, PrinterBehavior behavior) {}

    /** 当前 tick 可修复的任务列表（已绑定行为） */
    private final List<PlannedTask> tasks = new ArrayList<>();

    /** 当前 tick 无任何行为能处理的不一致位置 */
    private final List<PrinterTask> unsupportedTasks = new ArrayList<>();

    /** 当前 tick 有行为能处理但被用户关闭的不一致位置 */
    private final List<PrinterTask> disabledTasks = new ArrayList<>();

    /** 当前 tick 已通过完整 plan 过滤的候选（score 升序，越小越优） */
    private final List<PreviewCandidate> previewCandidates = new ArrayList<>();

    private int tickDelay = 0;

    /** 当前 tick 的动作计划（逻辑态） */
    private ArmedAction armed = null;

    /** 记录当前潜行状态是否由打印机强制触发 */
    private boolean didPrinterForceSneak = false;

    // 渲染态（与逻辑态分离，确保 hit 点稳定显示 1~2 tick）
    private ActionPlan lastPlan = null;
    private Vec3d lastHitVec = null;
    private int lastHitVecTicks = 0;

    /** 全局 tick 计数器（用于 pendingUseBlocks 超时） */
    private int tickCounter;

    /** 是否已为 provider-only 模式手动订阅事件 */
    private boolean subscribedForProviders = false;

    /** 容器物品填充子系统（多 tick 有状态，独立于标准行为管线） */
    private final ContainerFillManager containerFillManager = new ContainerFillManager();

    /**
     * UseBlock 执行后等待服务端确认的方块集合。
     *
     * <p>交互类动作（拉杆/门/活板门/栅栏门等）不像放置方块那样有客户端状态预测——
     * {@code Block.onUse()} 在 {@code isClient} 分支中不会调用 {@code world.setBlockState()}。
     * 状态变更只在服务端发生，然后通过 {@code BlockUpdateS2CPacket} 回传。
     * 在该回传到达之前，Printer 的扫描仍然会看到旧状态并尝试重复交互，
     * 导致拉杆等被反复切换（"抽搐"现象）。
     *
     * <p>此集合记录已执行但尚未收到服务端确认的 UseBlock 位置。
     * 每次扫描前清理已确认（状态已改变）或超时（40 tick）的条目。
     */
    private record PendingUse(BlockState stateAtInteraction, int tickCreated) {}
    private final Map<BlockPos, PendingUse> pendingUseBlocks = new HashMap<>();

    public Printer() {
        super(Categories.Player, "printer", "Automatically places blocks based on Litematica schematic.");
        autoSubscribe = false;  // 手动管理事件订阅：providers 活跃时即使模块未启用也需要 tick

        // 填充 Key → sub-toggle 映射
        subToggles.put(PrinterBehavior.Key.REPEATER_DELAY, fixRepeaterDelay);
        subToggles.put(PrinterBehavior.Key.COMPARATOR_MODE, fixComparatorMode);
        subToggles.put(PrinterBehavior.Key.REDSTONE_DOT_CROSS, fixRedstoneWire);
        subToggles.put(PrinterBehavior.Key.TRAPDOOR_OPEN, fixTrapdoor);
        subToggles.put(PrinterBehavior.Key.DOOR_OPEN, fixDoor);
        subToggles.put(PrinterBehavior.Key.FENCE_GATE_OPEN, fixFenceGate);
        subToggles.put(PrinterBehavior.Key.DAYLIGHT_DETECTOR, fixDaylightDetector);
        subToggles.put(PrinterBehavior.Key.FLUID_SOURCE, placeFluidSource);
        subToggles.put(PrinterBehavior.Key.WATERLOG, fixWaterlog);
        subToggles.put(PrinterBehavior.Key.LEVER_POWERED, fixLever);
        subToggles.put(PrinterBehavior.Key.CAMPFIRE_LIT, fixCampfire);
        subToggles.put(PrinterBehavior.Key.NOTE_BLOCK_NOTE, fixNoteBlock);
        subToggles.put(PrinterBehavior.Key.CONTAINER_FILL, fillContainers);
    }

    @Override
    public void onActivate() {
        resetState();
        ensureSubscribed();
    }

    @Override
    public void onDeactivate() {
        resetState();
        resetSneakState();
        ensureSubscribed();  // 有 providers 时保持订阅
    }

    /** 初始化/重置所有内部状态（activate/deactivate 共用） */
    private void resetState() {
        tickDelay = 0;
        tickCounter = 0;
        tasks.clear();
        unsupportedTasks.clear();
        disabledTasks.clear();
        previewCandidates.clear();
        pendingUseBlocks.clear();
        clearArmed();
        containerFillManager.reset();
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
     * PLACEMENT 组始终启用；其他组需组开关 + 细项开关同时启用。
     */
    private boolean isBehaviorEnabled(PrinterBehavior behavior) {
        // 组级开关
        boolean groupEnabled = switch (behavior.group()) {
            case PLACEMENT    -> true;
            case REDSTONE     -> fixRedstone.get();
            case INTERACTABLE -> fixInteractable.get();
            case FLUID        -> placeFluid.get();
            case BREAK        -> breakMismatched.get();
            case CONTAINER    -> fillContainers.get();
        };
        if (!groupEnabled) return false;

        // 细项开关（组内按 key 查找）
        Setting<Boolean> sub = subToggles.get(behavior.key());
        return sub == null || sub.get();
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

    /** 发布渲染计划快照 */
    private void publishRenderPlan(ActionPlan plan) {
        lastPlan = plan;
        lastHitVec = plan != null ? plan.interaction().hitVec() : null;
        lastHitVecTicks = plan != null ? 2 : 0;
    }

    /** 每 tick 衰减渲染态倒计时，归零后清空，避免残影 */
    private void tickRenderState() {
        if (lastHitVecTicks > 0) {
            if (--lastHitVecTicks == 0) {
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
        if (mc.player == null || mc.world == null) return;

        // 模块未启用：仅驱动 provider-only 管线
        if (!isActive()) {
            if (!hasActiveProviders()) return;
            tickProviderOnly();
            return;
        }

        tickCounter++;
        tickRenderState();

        // ── 容器填充 busy：状态机处于 PREPARING / OPENING / COOLDOWN，
        //    必须无条件推进，标准打印管线让路 ──
        if (fillContainers.get() && containerFillManager.isBusy()) {
            resetSneakState();
            tickContainerFill();
            return;
        }

        // ── 前置守卫 ──
        if (armed != null) return;                          // plan 待 Post 执行
        if (moveStop.get() && isPlayerMoving()) return;     // 移动暂停

        WorldSchematic worldSchematic = SchematicWorldHandler.getSchematicWorld();
        boolean hasProviders = hasActiveProviders();
        if (worldSchematic == null && !hasProviders) {
            error("Litematica schematic not loaded.");
            toggle();
            return;
        }

        if (tickDelay < placeDelay.get()) { tickDelay++; return; }
        tickDelay = 0;

        // ── 更新任务列表 ──
        if (worldSchematic != null) {
            updateTasks(worldSchematic);
        } else {
            // 无蓝图但有外挂输入源：清空旧蓝图任务
            tasks.clear();
            previewCandidates.clear();
        }

        // ── 外挂输入源统一注入 ──
        appendProviderTasks(worldSchematic);

        if (!tasks.isEmpty()) {
            // 标准打印：构建候选 → 评分 → 准备
            buildPreviewCandidates();
            SelectionResult result = selectAndPrepare();
            switch (result) {
                case SelectionResult.Ready r -> armed = r.armed();
                case SelectionResult.AwaitingPrep ignored -> {} // 等下一 tick 同步
                case SelectionResult.None ignored -> resetSneakState();
            }
            if (armed != null) {
                publishRenderPlan(armed.plan());
                if (rotate.get()) {
                    ActionPlan.Interaction inter = armed.plan().interaction();
                    Rotations.requestPreMovementToward(inter.hitVec(), 50, null);
                }
                return; // plan 已就绪，本 tick 不再启动容器
            }
            // armed == null：本轮无可用候选或等待切换，容器 IDLE 可趁隙启动
        } else {
            previewCandidates.clear();
            resetSneakState();
        }

        // ── 容器填充 IDLE：普通任务空或本轮无 armed 时，统一给容器子系统启动机会 ──
        tickContainerFill();
    }

    /**
     * 推进容器填充状态机（统一入口）。
     * <p>将原先分散在 onTickPre 三处的 containerFillManager.tick() 调用
     * 收敛为此单一方法，消除"某条路径提前 return 导致状态机被饿死"的风险。</p>
     */
    private void tickContainerFill() {
        if (!fillContainers.get()) return;
        boolean strict = placeMode.get() == PlaceMode.STRICT;
        containerFillManager.tick(tickCounter, placeRange.get(), strict,
            rotate.get(), didPrinterForceSneak, this::resetSneakState,
            overfillPolicy.get());
    }

    /**
     * Provider-only 管线：Printer 模块未启用时，仅驱动外挂输入源的放置。
     * 跳过蓝图扫描、容器填充、移动暂停等仅蓝图相关的逻辑。
     */
    private void tickProviderOnly() {
        tickCounter++;

        if (armed != null) return;  // 已有 plan 待 Post 执行

        tasks.clear();
        previewCandidates.clear();
        appendProviderTasks(null);

        if (tasks.isEmpty()) return;

        buildPreviewCandidates();
        SelectionResult result = selectAndPrepare();
        switch (result) {
            case SelectionResult.Ready r -> armed = r.armed();
            case SelectionResult.AwaitingPrep ignored -> {}
            case SelectionResult.None ignored -> resetSneakState();
        }
        if (armed != null && rotate.get()) {
            ActionPlan.Interaction inter = armed.plan().interaction();
            Rotations.requestPreMovementToward(inter.hitVec(), 50, null);
        }
    }

    // ==================== 候选评分权重 ====================
    // 综合评分 = dist² × W_DIST + yawDelta × W_YAW + sneakCost + itemSwitchCost + reachEdgeCost
    // 越低越优先。调参时只需修改此处常量。

    private static final double W_DISTANCE        = 1.0;   // 距离（平方）权重
    private static final double W_YAW_DELTA       = 0.08;  // 视角偏转惩罚
    private static final double COST_SNEAK        = 1.5;   // 需要潜行的额外成本
    private static final double COST_UNSNEAK      = 0.3;   // 需要取消潜行的微小成本
    private static final double COST_ITEM_SWITCH  = 1.0;   // 需要切换物品的额外成本
    private static final double COST_REACH_EDGE   = 1.2;   // 接近 reach 上限的风险成本
    private static final double REACH_EDGE_RATIO  = 0.85;  // 超过 maxReach 此比例视为边界

    /**
     * 构建预览候选列表（纯函数式，无任何副作用）
     *
     * <p>遍历 tasks，对每个调用 behavior.plan()。
     * 只有成功生成 ActionPlan 的才进入 {@code previewCandidates}，
     * 按综合评分升序排列（越小越优）。
     *
     * <p>此层即为渲染和选拔的统一数据源，
     * 解决了旧代码中"渲染层画 tasks（过宽）"与"执行层才做 plan（过窄）"的层次错位。
     */
    private void buildPreviewCandidates() {
        previewCandidates.clear();

        // 背包→热栏转移标记：vanilla clickSlot 乐观本地更新 + GrimAC doClick 同步模拟，
        // 同 tick 内 ClickSlot + HeldItemChange + PlayerMove + InteractBlock 序列完全合法。
        // 仅重置标记，不跳过。
        if (ItemSwitchHelper.didInventoryTransferThisTick()) {
            ItemSwitchHelper.resetTransferFlag();
        }

        boolean strict = placeMode.get() == PlaceMode.STRICT;
        boolean checkLos = strict && checkLineOfSight.get();
        double maxReach = placeRange.get();
        float renderYaw = mc.player.getYaw();
        Vec3d eyePos = mc.player.getEyePos();

        // Phase 1: 对任务调用 Behavior 生成计划并评分（无副作用）

        int candidateLimit = maxCandidates.get(); // 0 = 不限制
        int planned = 0;

        for (PlannedTask pt : tasks) {
            if (candidateLimit > 0 && planned >= candidateLimit) break;

            ActionPlan plan = pt.behavior().plan(pt.task(), mc, strict, checkLos, maxReach);
            if (plan == null) continue;

            // 放置目标位置实体阻挡检查
            if (plan instanceof ActionPlan.PlaceBlock pb && hasBlockingEntity(pb.targetPos())) continue;

            planned++;

            ActionPlan.Interaction inter = plan.interaction();
            Vec3d hitVec = inter.hitVec();
            double dist2 = eyePos.squaredDistanceTo(hitVec);

            // 计算 yaw 差
            float yawDelta = Math.abs(MathHelper.wrapDegrees(inter.yaw() - renderYaw));

            // sneak / 物品切换成本
            double sneakCost = switch (plan.sneakPolicy()) {
                case KEEP_CURRENT -> 0.0;
                case REQUIRE_SNEAK -> COST_SNEAK;
                case REQUIRE_NOT_SNEAK -> COST_UNSNEAK;
            };

            boolean needsItemSwitch = plan.requiredItem() != null
                && plan.handPolicy() != HandPolicy.PREFER_MAIN_NO_SWITCH
                && mc.player.getMainHandStack().getItem() != plan.requiredItem()
                && mc.player.getOffHandStack().getItem() != plan.requiredItem();

            // reach 边界风险
            double reachDist = eyePos.distanceTo(hitVec);
            boolean nearReachEdge = reachDist > maxReach * REACH_EDGE_RATIO;

            // 综合评分 (越低越好)
            double score = dist2 * W_DISTANCE
                + yawDelta * W_YAW_DELTA
                + sneakCost
                + (needsItemSwitch ? COST_ITEM_SWITCH : 0.0)
                + (nearReachEdge ? COST_REACH_EDGE : 0.0);

            previewCandidates.add(new PreviewCandidate(pt, plan, score));
        }

        previewCandidates.sort(Comparator.comparingDouble(PreviewCandidate::score));
    }

    /**
     * 从 previewCandidates 中选择最优候选并执行必要的准备动作
     *
     * <p>两阶段选择：
     * <ol>
     *   <li>纯检查：找已经就绪（物品在手 + sneak 状态匹配）的候选，无副作用</li>
     *   <li>单候选准备：对排名最高的"需要准备"的候选执行物品切换 / sneak</li>
     * </ol>
     *
     * <p>副作用（切物品、切 sneak）仅作用于最终选中的那一个候选，
     * 不再在遍历途中对多个候选产生泄漏。
     *
     * @return Ready / AwaitingPrep / None 三态结果
     */
    private SelectionResult selectAndPrepare() {
        // Phase 1: 找已就绪的候选（无副作用）
        for (PreviewCandidate c : previewCandidates) {
            Hand hand = getReadyHand(c.plan);
            if (hand != null && isSneakStateOk(c.plan.sneakPolicy())) {
                return new SelectionResult.Ready(new ArmedAction(c.plan, hand));
            }
        }

        // Phase 2: 对排名最高的需准备候选执行副作用
        for (PreviewCandidate c : previewCandidates) {
            Hand hand = ensureItemAndGetHand(c.plan);
            if (hand == null) continue;

            SneakReadiness sr = prepareSneakState(c.plan.sneakPolicy());
            if (sr == SneakReadiness.READY) {
                return new SelectionResult.Ready(new ArmedAction(c.plan, hand));
            }
            if (sr == SneakReadiness.PREPARING) {
                return new SelectionResult.AwaitingPrep();
            }
            // BLOCKED: 用户手动潜行，跳过这个候选，尝试下一个
        }

        return new SelectionResult.None();
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

        // 容器打开：ARMED_OPEN 态时，movement packet（含旋转）已发出，执行 interactBlock
        if (fillContainers.get() && containerFillManager.executeOpen()) {
            // interactBlock 已发送，无需额外操作
        }
    }

    /**
     * 容器内容同步事件：服务端发送 WINDOW_ITEMS 时，转发给 ContainerFillManager 执行填充操作。
     */
    @EventHandler
    private void onInventorySync(InventoryEvent event) {
        if (fillContainers.get() && containerFillManager.isBusy()) {
            containerFillManager.onInventorySync(event);
        }
    }

    /**
     * 容器屏幕抑制：ContainerFillManager 打开容器期间，阻止容器 GUI 弹出。
     * <p>ScreenHandler 在 setScreen 之前已由 ClientPlayNetworkHandler 设置完毕，
     * 取消 setScreen 不影响后续 clickSlot 操作。
     */
    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (fillContainers.get() && containerFillManager.shouldSuppressScreen()) {
            event.cancel();
            return;
        }
        // 玩家手动打开容器 → 使对应快照失效，下次自动流程会重新同步
        if (fillContainers.get() && event.screen != null
            && mc.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult bhr) {
            containerFillManager.invalidateSnapshot(bhr.getBlockPos());
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

        // 1. 通用几何验证：Reach (对齐 GrimAC FarPlace: 眼→AABB 最近点距离)
        BlockPos ip = inter.interactPos();
        double dx = Math.max(ip.getX() - currentEye.x, Math.max(0, currentEye.x - (ip.getX() + 1)));
        double dy = Math.max(ip.getY() - currentEye.y, Math.max(0, currentEye.y - (ip.getY() + 1)));
        double dz = Math.max(ip.getZ() - currentEye.z, Math.max(0, currentEye.z - (ip.getZ() + 1)));
        if (dx * dx + dy * dy + dz * dz > placeRange.get() * placeRange.get()) return false;

        // 2. 通用几何验证：NCP + LOS (STRICT mode)
        if (placeMode.get() == PlaceMode.STRICT) {
            if (!BlockUtilHelper.getPlaceDirectionsNCP(currentEye, inter.interactPos())
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
            case ActionPlan.UseItemInAir ua -> isUseItemInAirStillValid(ua, hand);
        };
    }

    private boolean isPlaceBlockStillValid(ActionPlan.PlaceBlock plan, Hand hand) {
        // 实体阻挡复查（执行前最后防线）
        if (hasBlockingEntity(plan.targetPos())) return false;

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
        return isHeldItem(hand, plan.requiredItem());
    }

    private boolean isUseBlockStillValid(ActionPlan.UseBlock plan, Hand hand) {
        BlockState current = mc.world.getBlockState(plan.targetPos());
        if (!plan.stillNeedsAction().test(current)) return false;
        if (current.getBlock() != plan.desiredState().getBlock()) return false;
        if (!BlockUtilHelper.isClickable(current, mc.world, plan.targetPos())) return false;
        return isHeldItem(hand, plan.requiredItem());
    }

    private boolean isUseItemOnBlockStillValid(ActionPlan.UseItemOnBlock plan, Hand hand) {
        if (!plan.stillNeedsAction().test(mc.world.getBlockState(plan.targetPos()))) return false;
        if (!plan.interaction().selfInteraction()
            && !BlockUtilHelper.isClickable(mc.world.getBlockState(plan.interaction().interactPos()), mc.world, plan.interaction().interactPos()))
            return false;
        return isHeldItem(hand, plan.requiredItem());
    }

    private boolean isUseItemInAirStillValid(ActionPlan.UseItemInAir plan, Hand hand) {
        if (!plan.stillNeedsAction().test(mc.world.getBlockState(plan.targetPos()))) return false;
        return isHeldItem(hand, plan.requiredItem());
    }

    /** 检查指定手是否持有必需物品（null 表示不限） */
    private boolean isHeldItem(Hand hand, Item required) {
        if (required == null) return true;
        return (hand == Hand.MAIN_HAND ? mc.player.getMainHandStack() : mc.player.getOffHandStack()).getItem() == required;
    }

    /**
     * 执行动作计划 — 复刻 vanilla {@code doItemUse()} 右键链。
     * <ul>
     *   <li>PlaceBlock / UseBlock: interactBlock → swing</li>
     *   <li>UseItemOnBlock: interactBlock → (PASS 时) → interactItem → swing
     *       <br>水桶等物品的 useOnBlock 返回 PASS，实际放置由 item.use() 完成</li>
     *   <li>UseItemInAir: interactItem → swing</li>
     * </ul>
     */
    private void executePlan(ArmedAction armed) {
        ActionPlan plan = armed.plan();

        if (plan instanceof ActionPlan.UseItemInAir) {
            if (mc.interactionManager.interactItem(mc.player, armed.hand()).isAccepted()) {
                swingOrPacket(armed.hand());
            }
            return;
        }

        ActionPlan.Interaction inter = plan.interaction();
        BlockHitResult hitResult = new BlockHitResult(
            inter.hitVec(), inter.clickedFace(), inter.interactPos(), false);

        var blockResult = mc.interactionManager.interactBlock(mc.player, armed.hand(), hitResult);

        if (blockResult.isAccepted()) {
            swingOrPacket(armed.hand());

            if (plan instanceof ActionPlan.UseBlock ub) {
                pendingUseBlocks.put(ub.targetPos().toImmutable(),
                    new PendingUse(mc.world.getBlockState(ub.targetPos()), tickCounter));
            }
            return;
        }

        // UseItemOnBlock: interactBlock 返回 PASS → fallthrough 到 interactItem（vanilla 右键链）
        if (plan instanceof ActionPlan.UseItemOnBlock) {
            if (mc.interactionManager.interactItem(mc.player, armed.hand()).isAccepted()) {
                swingOrPacket(armed.hand());
            }
        }
    }

    /** 挥手或发包（统一放置/交互后的手臂动画） */
    private void swingOrPacket(Hand hand) {
        if (swingHand.get()) mc.player.swingHand(hand);
        else mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(hand));
    }

    /**
     * 更新任务列表：扫描蓝图与世界差异
     *
     * 改进点：
     * 1. 使用 PlannedTask 绑定行为，selectBestAction 不再重复查找
     * 2. 无行为匹配的不一致收集到 unsupportedTasks；有行为但被关闭的收集到 disabledTasks
     * 3. 不在扫描阶段做 resolve，"已满足"判断交给 Behavior.isSatisfied
     */
    private void updateTasks(WorldSchematic worldSchematic) {
        tasks.clear();
        unsupportedTasks.clear();
        disabledTasks.clear();

        if (mc.player == null || mc.world == null) return;

        // 清理已确认或超时的 UseBlock 等待条目
        // 状态已改变 = 服务端确认了交互；超过 40 tick ≈ 2s = 视为服务端拒绝，允许重试
        pendingUseBlocks.entrySet().removeIf(e -> {
            if (mc.world.getBlockState(e.getKey()) != e.getValue().stateAtInteraction()) return true;
            return tickCounter - e.getValue().tickCreated() > 40;
        });

        Vec3d playerPos = mc.player.getEyePos();
        double range = placeRange.get();
        double preSelectionRange = range + 1.0;

        List<BlockPos> sphere = getSphere(preSelectionRange, playerPos);

        // 容器填充扫描：标记本 tick 开始
        if (fillContainers.get()) {
            containerFillManager.beginScan();
        }

        for (BlockPos pos : sphere) {
            if (!DataManager.getRenderLayerRange().isPositionWithinRange(pos)) continue;

            BlockState requiredState = worldSchematic.getBlockState(pos);
            BlockState currentState = mc.world.getBlockState(pos);

            // 流体归一化：蓝图要求水 + 当前方块可含水 → 归一化为含水版本走 WaterlogBehavior
            requiredState = normalizeFluidDesired(requiredState, currentState, pos);

            // ── 蓝图要求空气 ──
            if (requiredState.isAir()) {
                if (currentState.isAir() || currentState.isReplaceable()) continue;
                // 破坏未启用 → 无法处理多余方块，跳过
                if (!breakMismatched.get()) continue;
                // 投影边界检查：位置不在任何已启用 Placement 的边界内 → 非蓝图管辖区域，跳过
                // WorldSchematic 对未覆盖的位置也返回 AIR，必须通过 Placement 边界区分
                if (!isWithinAnyPlacement(pos)) continue;
                // 空气位容忍：多余的 dirt/grass 或脚手架允许保留
                if (isToleratedInAir(currentState)) continue;
                // 走 behavior pipeline（BlockBreakBehavior 捕获）
            }

            // 完全一致，跳过（但仍需检查容器内容）
            if (requiredState == currentState) {
                // 容器内容填充：方块状态一致时，检查蓝图容器是否有物品需要填入
                if (fillContainers.get()) {
                    scanSchematicContainer(worldSchematic, pos);
                }
                continue;
            }

            // 泥土混淆等价：dirt ↔ grass_block 视为相同，无需修正
            if (tolerateDirt.get() && isDirtGrassEquivalent(requiredState, currentState)) continue;

            // UseBlock 等待服务端确认中，跳过（防止 toggle 类方块被反复交互）
            if (pendingUseBlocks.containsKey(pos)) continue;

            // 泥土混淆放置：当背包缺少蓝图指定的 dirt/grass 时，用对方替代
            BlockState effectiveDesired = resolveDirtSubstitute(requiredState);

            PrinterTask task = new PrinterTask(pos, effectiveDesired, currentState);

            // 多格对象锚点规范化：
            // 床/门/双箱子等多格对象，只从主格（放置锚点）规划，另一半由 onPlaced 联动。
            // 这避免了两个 task 争抢同一个放置动作。
            if (requiredState.getBlock() instanceof BedBlock
                && requiredState.contains(BedBlock.PART)
                && requiredState.get(BedBlock.PART) != net.minecraft.block.enums.BedPart.FOOT) {
                continue; // 只从 foot 半规划
            }
            if (requiredState.getBlock() instanceof DoorBlock
                && requiredState.contains(DoorBlock.HALF)
                && requiredState.get(DoorBlock.HALF) != net.minecraft.block.enums.DoubleBlockHalf.LOWER) {
                continue; // 只从 lower 半规划
            }

            // 查找匹配的已启用行为
            PrinterBehavior behavior = findEnabledBehavior(task);
            if (behavior == null) {
                // 区分"真不支持"和"有行为但被关闭"
                if (PrinterBehavior.find(task) != null) {
                    disabledTasks.add(task);
                } else {
                    unsupportedTasks.add(task);
                }
                continue;
            }

            // 已经满足？
            if (behavior.isSatisfied(task)) continue;

            // 实体阻挡检查（仅对放置类行为有意义）
            if (behavior instanceof BlockPlacementBehavior && hasBlockingEntity(pos)) continue;

            tasks.add(new PlannedTask(task, behavior));
        }

        // 按距离排序（最近的优先）
        Vec3d eyePos = mc.player.getEyePos();
        tasks.sort(Comparator.comparingDouble(
            pt -> eyePos.squaredDistanceTo(
                pt.task().pos().getX() + 0.5, pt.task().pos().getY() + 0.5, pt.task().pos().getZ() + 0.5)));

        // 容器填充扫描：清理不再存在于蓝图扫描范围内的过期条目
        if (fillContainers.get()) {
            containerFillManager.pruneStaleEntries();
        }
    }

    /**
     * 扫描蓝图容器的物品内容并注册到 ContainerFillManager。
     * <p>在 updateTasks 循环中，对每个方块状态一致的位置调用。
     * 仅处理 MVP 支持的简单容器（发射器/投掷器/漏斗/木桶）。
     */
    private void scanSchematicContainer(WorldSchematic worldSchematic, BlockPos pos) {
        BlockEntity be = worldSchematic.getBlockEntity(pos);
        if (!ContainerFillManager.isSupportedContainer(be)) return;

        Inventory inv = (Inventory) be;
        List<ItemStack> items = new ArrayList<>();
        boolean hasContent = false;

        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            items.add(stack.copy());
            if (!stack.isEmpty()) hasContent = true;
        }

        if (hasContent) {
            containerFillManager.registerSchematicContainer(pos.toImmutable(), items);
        }
    }

    // ==================== 外挂输入源统一集成 ====================

    /**
     * 查询是否有任意已注册的输入源当前有待放置位置。
     */
    private boolean hasActiveProviders() {
        for (PrinterTaskProvider p : providers) {
            if (!p.getPlacementPositions().isEmpty()) return true;
        }
        return false;
    }

    /**
     * 遍历所有已注册输入源，将其任务注入 tasks 列表。
     *
     * <p>每个输入源通过 {@link PrinterTaskProvider#allowedGroups()} 声明允许的 Behavior Group，
     * Printer 只会匹配在允许集合内的 behavior，防止非蓝图输入源触发 BREAK 等行为。</p>
     *
     * <p>对于非 bypass 输入源（如 SpawnProof），蓝图边界内的位置由蓝图管辖，跳过。</p>
     */
    private void appendProviderTasks(@org.jetbrains.annotations.Nullable WorldSchematic worldSchematic) {
        double range = placeRange.get();
        double maxDist2 = (range + 1.0) * (range + 1.0);
        Vec3d eye = mc.player.getEyePos();

        for (PrinterTaskProvider provider : providers) {
            Collection<BlockPos> positions = provider.getPlacementPositions();
            if (positions.isEmpty()) continue;

            Set<PrinterBehavior.Group> allowed = provider.allowedGroups();
            boolean bypass = provider.bypassSchematicCheck();

            for (BlockPos pos : positions) {
                double dist2 = eye.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (dist2 > maxDist2) continue;

                // 非 bypass 输入源：蓝图边界内由蓝图管辖
                if (!bypass && worldSchematic != null) {
                    BlockState schState = worldSchematic.getBlockState(pos);
                    if (!schState.isAir() || isWithinAnyPlacement(pos)) continue;
                }

                BlockState desired = provider.getDesiredState(pos);
                BlockState current = mc.world.getBlockState(pos);

                PrinterTask task = new PrinterTask(pos, desired, current);

                PrinterBehavior behavior = findEnabledBehaviorForProvider(task, allowed);
                if (behavior == null || behavior.isSatisfied(task)) continue;
                if (behavior instanceof BlockPlacementBehavior && hasBlockingEntity(pos)) continue;

                tasks.add(new PlannedTask(task, behavior));
            }
        }
    }

    /**
     * 为输入源的任务查找第一个已启用且在允许 Group 集合内的匹配 behavior。
     */
    private PrinterBehavior findEnabledBehaviorForProvider(PrinterTask task, Set<PrinterBehavior.Group> allowed) {
        for (PrinterBehavior behavior : PrinterBehavior.REGISTRY) {
            if (!allowed.contains(behavior.group())) continue;
            if (!isBehaviorEnabled(behavior)) continue;
            if (behavior.supports(task)) return behavior;
        }
        return null;
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

    // ==================== 泥土混淆 & 容忍辅助 ====================

    /**
     * 检查两个方块状态是否属于 dirt ↔ grass_block 等价对。
     * 仅限泥土和草方块之间的互换，灰化土/菌丝/泥巴等不参与混淆。
     */
    private boolean isDirtGrassEquivalent(BlockState a, BlockState b) {
        Block ba = a.getBlock(), bb = b.getBlock();
        return (ba == Blocks.DIRT && bb == Blocks.GRASS_BLOCK)
            || (ba == Blocks.GRASS_BLOCK && bb == Blocks.DIRT);
    }

    /** dirt 或 grass_block（用于空气位容忍判定） */
    private static boolean isDirtOrGrass(BlockState state) {
        Block b = state.getBlock();
        return b == Blocks.DIRT || b == Blocks.GRASS_BLOCK;
    }

    /**
     * 蓝图要求空气时，判断世界中的方块是否可以容忍保留。
     * 仅在 breakMismatched 启用后有意义。
     *
     * @see #tolerateExtraDirt 容忍空气位多余的 dirt/grass
     * @see #tolerateScaffolding 容忍空气位多余的脚手架
     */
    private boolean isToleratedInAir(BlockState state) {
        if (tolerateExtraDirt.get() && isDirtOrGrass(state)) return true;
        if (tolerateScaffolding.get() && state.getBlock() == Blocks.SCAFFOLDING) return true;
        return false;
    }

    /**
     * 流体目标归一化：当蓝图要求水源且当前方块可含水（waterloggable）时，
     * 将 {@code Blocks.WATER} 目标归一化为"当前方块 + WATERLOGGED=true"。
     *
     * <p>这解决了"蓝图水落在树叶/半砖/楼梯等位置"时被 {@code WaterBehavior} 错误认领的问题。
     * 归一化后任务自然匹配 {@code WaterlogBehavior}，一次 waterlog 即完成。
     *
     * @param required 蓝图要求的方块状态
     * @param current  世界当前方块状态
     * @param pos      方块位置
     * @return 归一化后的目标状态（如果不适用则原样返回）
     */
    private BlockState normalizeFluidDesired(BlockState required, BlockState current, BlockPos pos) {
        if (required.getBlock() != Blocks.WATER) return required;
        if (!current.contains(Properties.WATERLOGGED)) return required;

        // 已经含水 → 目标就是当前状态
        if (current.get(Properties.WATERLOGGED)) return current;

        // 当前方块能接受水 → 归一化为含水版本
        if (current.getBlock() instanceof FluidFillable fillable
            && fillable.canFillWithFluid(mc.player, mc.world, pos, current, Fluids.WATER)) {
            return current.with(Properties.WATERLOGGED, true);
        }

        return required;
    }

    /**
     * 对 dirt ↔ grass_block 进行放置替代：当背包缺少蓝图指定方块时，
     * 尝试用 dirt/grass 对方替代。仅在 tolerateDirt 开启时生效。
     *
     * @return 替代后的 desiredState（如果无需或无法替代，返回原始值）
     */
    private BlockState resolveDirtSubstitute(BlockState desired) {
        if (!tolerateDirt.get()) return desired;
        Block b = desired.getBlock();
        if (b == Blocks.GRASS_BLOCK
            && !InvUtils.find(b.asItem()).found()
            && InvUtils.find(Blocks.DIRT.asItem()).found()) {
            return Blocks.DIRT.getDefaultState();
        }
        if (b == Blocks.DIRT
            && !InvUtils.find(b.asItem()).found()
            && InvUtils.find(Blocks.GRASS_BLOCK.asItem()).found()) {
            return Blocks.GRASS_BLOCK.getDefaultState();
        }
        return desired;
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
     * 检查给定位置是否在任何已启用的 Litematica SchematicPlacement 的子区域边界内。
     *
     * <p>使用 {@code getSubRegionBoxes(ENABLED)} 而非 {@code getEclosingBox()} 做精确判断：
     * enclosingBox 是所有子区域的外接矩形（可能含间隙或为 null），
     * 逐子区域 box 检查避免了 enclosingBox 为空或含间隙时的误判。
     *
     * @param pos 待检查的世界坐标
     * @return true 表示该位置在至少一个已启用子区域的边界内
     */
    static boolean isWithinAnyPlacement(BlockPos pos) {
        var manager = DataManager.getSchematicPlacementManager();
        for (var placement : manager.getAllSchematicsPlacements()) {
            if (!placement.isEnabled()) continue;
            var boxes = placement.getSubRegionBoxes(
                fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED);
            for (var box : boxes.values()) {
                BlockPos p1 = box.getPos1();
                BlockPos p2 = box.getPos2();
                if (p1 == null || p2 == null) continue;
                int minX = Math.min(p1.getX(), p2.getX());
                int maxX = Math.max(p1.getX(), p2.getX());
                int minY = Math.min(p1.getY(), p2.getY());
                int maxY = Math.max(p1.getY(), p2.getY());
                int minZ = Math.min(p1.getZ(), p2.getZ());
                int maxZ = Math.max(p1.getZ(), p2.getZ());
                if (pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ) {
                    return true;
                }
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
        if (!isActive() || !render.get()) return;

        // 已通过完整 plan 过滤的候选（按行为分组着色，排除当前计划目标）
        for (PreviewCandidate c : previewCandidates) {
            BlockPos pos = c.plannedTask().task().pos();
            if (lastPlan != null && pos.equals(lastPlan.targetPos())) continue;
            if (c.plannedTask().behavior().group() == PrinterBehavior.Group.PLACEMENT) {
                event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            } else {
                event.renderer.box(pos, behaviorSideColor.get(), behaviorLineColor.get(), shapeMode.get(), 0);
            }
        }

        // 当前计划目标（醒目青色高亮）
        if (lastPlan != null) {
            event.renderer.box(lastPlan.targetPos(), plannedSideColor.get(), plannedLineColor.get(), shapeMode.get(), 0);
        }

        // 无行为匹配的不一致方块（橙色）
        if (renderUnsupported.get()) {
            for (PrinterTask task : unsupportedTasks) {
                event.renderer.box(task.pos(), unsupportedSideColor.get(), unsupportedLineColor.get(), shapeMode.get(), 0);
            }
        }

        // 交互点（红橙色小立方体）
        if (renderHitVec.get() && lastHitVec != null && lastHitVecTicks > 0) {
            renderHitVecCube(event);
        }

        // 容器高亮：未满足的蓝图容器（橙红色半透明高亮）
        if (fillContainers.get() && containerHighlight.get()) {
            for (BlockPos pos : containerFillManager.getUnsatisfiedPositions()) {
                event.renderer.box(pos, containerSideColor.get(), containerLineColor.get(), shapeMode.get(), 0);
            }
        }
    }

    /**
     * 渲染交互点：双层立方体（正常深度 70% + 穿透 30%）
     */
    private void renderHitVecCube(Render3DEvent event) {
        if (lastHitVec == null) return;

        double s = 0.05;
        net.minecraft.util.math.Box box = new net.minecraft.util.math.Box(
            lastHitVec.x - s, lastHitVec.y - s, lastHitVec.z - s,
            lastHitVec.x + s, lastHitVec.y + s, lastHitVec.z + s);

        SettingColor c = hitVecColor.get();
        SettingColor solid = new SettingColor(c.r, c.g, c.b, 179);
        SettingColor ghost = new SettingColor(c.r, c.g, c.b, 76);

        event.renderer.box(box, solid, solid, ShapeMode.Both, 0);
        event.depthRenderer.box(box, ghost, ghost, ShapeMode.Both, 0);
    }

    // ==================== 容器填充 HUD 覆盖层 ====================

    /**
     * 在屏幕左上角渲染容器填充状态覆盖层。
     * <p>布局：状态行 → 容器类型行 → 分隔 → 物品需求行
     */
    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!isActive() || !fillContainers.get() || !containerOverlay.get()) return;
        if (containerFillManager.getSchematicContainerCount() == 0) return;

        net.minecraft.client.gui.DrawContext ctx = event.drawContext;
        net.minecraft.client.font.TextRenderer tr = mc.textRenderer;

        int x = 6, y = 6, rowH = 18, iconW = 16;

        // 预收集数据
        Map<Item, int[]> types = containerFillManager.getTypeSummaries();
        Map<Item, Integer> itemNeeds = containerFillManager.getAllUnsatisfiedItemNeeds();
        int totalC = containerFillManager.getSchematicContainerCount();
        int doneC = containerFillManager.getSatisfiedCount();

        // 背景
        int rows = 1 + types.size() + (itemNeeds.isEmpty() ? 0 : 1 + itemNeeds.size());
        ctx.fill(x - 3, y - 3, x + 140, y + rows * rowH + 5, 0x90000000);

        // 标题行：进度 + 状态机状态
        ContainerFillManager.State st = containerFillManager.getState();
        String stTag = switch (st) {
            case IDLE      -> "";
            case PREPARING -> " \u23f3";
            case ARMED_OPEN -> " \u2b50";
            case OPENING   -> " \u27f3";
            case COOLDOWN  -> " \u2026";
        };
        String title = "C: " + doneC + "/" + totalC + stTag;
        int titleColor = doneC == totalC ? 0xFF55FF55 : 0xFFFFAA00;
        ctx.drawText(tr, title, x, y + 4, titleColor, true);
        y += rowH;

        // 容器类型行
        for (var entry : types.entrySet()) {
            RenderUtils.drawItem(ctx, new ItemStack(entry.getKey()), x, y, 1f, false);
            int[] c = entry.getValue();
            int color = c[0] == c[1] ? 0xFF55FF55 : 0xFFFF5555;
            ctx.drawText(tr, c[0] + "/" + c[1], x + iconW + 4, y + 4, color, true);
            y += rowH;
        }

        // 物品需求行
        if (!itemNeeds.isEmpty()) {
            ctx.drawText(tr, "-- Items --", x, y + 4, 0xFF999999, true);
            y += rowH;

            for (var entry : itemNeeds.entrySet()) {
                Item item = entry.getKey();
                int need = entry.getValue();
                int have = InvUtils.find(item).count();

                RenderUtils.drawItem(ctx, new ItemStack(item), x, y, 1f, false);
                int color = have >= need ? 0xFF55FF55 : 0xFFFFAA00;
                ctx.drawText(tr, have + "/" + need, x + iconW + 4, y + 4, color, true);
                y += rowH;
            }
        }
    }

    /**
     * 打开容器填充详情屏幕。由 containerScreenKey 快捷键触发。
     */
    private void openContainerFillScreen() {
        if (!isActive() || !fillContainers.get()) return;
        mc.setScreen(new ContainerFillScreen(GuiThemes.get(), containerFillManager,
            containerInfoRange.get(), placeRange.get()));
    }

    @Override
    public String getInfoString() {
        int preview = previewCandidates.size();
        int supported = tasks.size();
        int unsupported = unsupportedTasks.size();
        int disabled = disabledTasks.size();
        StringBuilder sb = new StringBuilder();
        if (armed != null) sb.append("ARMED (");
        sb.append(preview).append("/").append(supported);
        if (disabled > 0) sb.append(" ~").append(disabled);
        if (unsupported > 0) sb.append(" !").append(unsupported);
        if (armed != null) sb.append(")");
        // 容器填充状态
        if (fillContainers.get() && containerFillManager.getSchematicContainerCount() > 0) {
            sb.append(" C:").append(containerFillManager.getSatisfiedCount())
              .append("/").append(containerFillManager.getSchematicContainerCount());
        }
        return sb.toString();
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
