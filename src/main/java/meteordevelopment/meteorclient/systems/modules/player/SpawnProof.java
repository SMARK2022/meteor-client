package meteordevelopment.meteorclient.systems.modules.player;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.printer.SpawnCheckHelper;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.block.enums.BlockFace;
import net.minecraft.block.enums.SlabType;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.*;

import java.util.*;

/**
 * SpawnProof — hostile mob 刷怪面扫描器 + 虚拟覆盖层任务提供者。
 *
 * <p>独立扫描玩家周围的可刷怪表面，维护"需要防刷怪"的坐标集合。
 * 当 Printer 启用 spawn-proof 开关时，这些位置会被转化为放置任务
 * （下半砖 / 按钮 / 火把），注入 Printer 的标准行为管线执行。
 *
 * <p><b>SpawnProof 自身不执行任何放置操作，只负责"算任务"。</b>
 * 这实现了"SpawnProof 算 + Printer 做"的架构分离。
 *
 * <p>三种模式：
 * <ul>
 *   <li><b>SLAB</b> — 下半砖（几何替换），永久、最稳、不依赖亮度</li>
 *   <li><b>BUTTON</b> — 地板按钮（轻量覆盖），省材料、改动小</li>
 *   <li><b>TORCH</b> — 地面火把（照明），临时施工便利</li>
 * </ul>
 *
 * <p>扫描采用增量预算制：每 tick 检查一批位置，循环巡检。
 * 玩家大距离移动时触发全量重建。避免单 tick 卡顿。
 */
public class SpawnProof extends Module {

    // ==================== 模式枚举 ====================

    public enum Mode {
        /** 下半砖 — 几何替换法，永久、最稳、默认推荐 */
        SLAB,
        /** 地板按钮 — 轻量覆盖，省材料 */
        BUTTON,
        /** 地面火把 — 照明法，临时施工便利 */
        TORCH
    }

    // ==================== 设置 ====================

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender  = settings.createGroup("Render");

    // ── 通用设置 ──

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Spawn-proof strategy. SLAB = permanent geometry. BUTTON = lightweight cover. TORCH = temporary lighting.")
        .defaultValue(Mode.SLAB)
        .build());

    private final Setting<List<Block>> slabBlock = sgGeneral.add(new BlockListSetting.Builder()
        .name("slab-block")
        .description("Slab block for SLAB mode (uses first selected). Must be a slab block.")
        .defaultValue(List.of(Blocks.SMOOTH_STONE_SLAB))
        .visible(() -> mode.get() == Mode.SLAB)
        .build());

    private final Setting<List<Block>> buttonBlock = sgGeneral.add(new BlockListSetting.Builder()
        .name("button-block")
        .description("Button block for BUTTON mode (uses first selected). Must be a button block.")
        .defaultValue(List.of(Blocks.STONE_BUTTON))
        .visible(() -> mode.get() == Mode.BUTTON)
        .build());

    private final Setting<Integer> horizontalRange = sgGeneral.add(new IntSetting.Builder()
        .name("horizontal-range")
        .description("Horizontal scan radius from player (blocks).")
        .defaultValue(24)
        .min(8)
        .sliderRange(8, 64)
        .build());

    private final Setting<Integer> verticalRange = sgGeneral.add(new IntSetting.Builder()
        .name("vertical-range")
        .description("Vertical scan range above/below player (blocks).")
        .defaultValue(16)
        .min(4)
        .sliderRange(4, 48)
        .build());

    private final Setting<Integer> scanBudget = sgGeneral.add(new IntSetting.Builder()
        .name("scan-budget")
        .description("Max spawn checks per tick. Higher = faster full scan, more CPU.")
        .defaultValue(2048)
        .min(128)
        .sliderRange(128, 8192)
        .build());

    // ── 渲染设置 ──

    private final Setting<Boolean> renderOverlay = sgRender.add(new BoolSetting.Builder()
        .name("render-overlay")
        .description("Show flat markers on spawnable surfaces.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> renderRange = sgRender.add(new IntSetting.Builder()
        .name("render-range")
        .description("Max distance from player to render markers.")
        .defaultValue(24)
        .min(4)
        .sliderRange(4, 48)
        .visible(renderOverlay::get)
        .build());

    private final Setting<SettingColor> markerColor = sgRender.add(new ColorSetting.Builder()
        .name("marker-color")
        .description("Fill color of spawnable surface markers.")
        .defaultValue(new SettingColor(255, 50, 50, 60))
        .visible(renderOverlay::get)
        .build());

    private final Setting<SettingColor> markerLineColor = sgRender.add(new ColorSetting.Builder()
        .name("marker-line-color")
        .description("Outline color of spawnable surface markers.")
        .defaultValue(new SettingColor(255, 50, 50, 160))
        .visible(renderOverlay::get)
        .build());

    // ==================== 内部状态 ====================

    /** 当前已确认的可刷怪坐标集（渲染用，包含所有检测到的位置） */
    private final Set<BlockPos> spawnablePositions = new HashSet<>();

    /**
     * TORCH 模式的放置候选集：grid-NMS 后的最优火把位置。
     * 每个 7×7×7 网格单元仅保留一个最接近单元中心的位置，
     * 确保火把间距 ≥ 7 格（light level 14，覆盖半径 13 manhattan）。
     */
    private final Set<BlockPos> torchPlacementPositions = new HashSet<>();

    /** 增量扫描队列 */
    private List<BlockPos> scanQueue = Collections.emptyList();
    private int scanIndex = 0;

    /** 上次全量重建时的玩家位置 */
    private BlockPos lastCenter;

    /** 移动超过 4 格触发重建 */
    private static final double RESCAN_DIST_SQ = 4.0 * 4.0;

    /** 火把 grid-NMS 网格大小（7 格间距确保 light level 14 的覆盖半径 13 完全重叠） */
    private static final int TORCH_GRID = 7;

    // ==================== 生命周期 ====================

    public SpawnProof() {
        super(Categories.Player, "spawn-proof",
            "Scans hostile mob spawn surfaces. When linked with Printer, auto-places blocks to prevent spawning.");
    }

    @Override
    public void onActivate() {
        spawnablePositions.clear();
        torchPlacementPositions.clear();
        scanQueue = Collections.emptyList();
        scanIndex = 0;
        lastCenter = null;
    }

    @Override
    public void onDeactivate() {
        spawnablePositions.clear();
        torchPlacementPositions.clear();
        scanQueue = Collections.emptyList();
    }

    // ==================== 增量扫描 ====================

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.world == null || mc.player == null) return;

        BlockPos center = mc.player.getBlockPos();

        // 玩家大距离移动 → 全量重建扫描队列
        if (lastCenter == null || center.getSquaredDistance(lastCenter) > RESCAN_DIST_SQ) {
            rebuildScanQueue(center);
            lastCenter = center;
        }

        // 增量预算扫描
        int budget = scanBudget.get();
        int queueSize = scanQueue.size();
        if (queueSize == 0) return;

        boolean isTorch = mode.get() == Mode.TORCH;

        for (int i = 0; i < budget && scanIndex < queueSize; i++, scanIndex++) {
            BlockPos pos = scanQueue.get(scanIndex);

            // 快速预过滤：地面不是实体方块 → 不可能刷怪（跳过 90%+ 位置）
            if (SpawnCheckHelper.quickRejectNotSpawnable(mc.world, pos)) {
                spawnablePositions.remove(pos);
                continue;
            }

            // 模式分流：
            // SLAB/BUTTON → 纯几何判定（不查光照，因为几何阻刷不依赖亮度）
            // TORCH       → 完整判定（含光照，已亮的位置不需要再插火把）
            boolean spawnable = isTorch
                ? SpawnCheckHelper.canHostileSpawnAt(mc.world, pos)
                : SpawnCheckHelper.isGeometricSpawnable(mc.world, pos);

            if (spawnable) {
                spawnablePositions.add(pos.toImmutable());
            } else {
                spawnablePositions.remove(pos);
            }
        }

        // 循环巡检 + TORCH 模式下每轮结束时重算 NMS 放置点
        if (scanIndex >= queueSize) {
            scanIndex = 0;
            if (isTorch) computeTorchPlacement();
        }
    }

    /**
     * 重建扫描队列：圆柱形区域内所有候选位置。
     */
    private void rebuildScanQueue(BlockPos center) {
        int hRange  = horizontalRange.get();
        int vRange  = verticalRange.get();
        int hRange2 = hRange * hRange;

        int minY = Math.max(mc.world.getBottomY() + 1, center.getY() - vRange);
        int maxY = Math.min(mc.world.getTopYInclusive() - 1, center.getY() + vRange);

        List<BlockPos> queue = new ArrayList<>((int) (Math.PI * hRange2 * (maxY - minY + 1)));

        for (int dx = -hRange; dx <= hRange; dx++) {
            for (int dz = -hRange; dz <= hRange; dz++) {
                if (dx * dx + dz * dz > hRange2) continue;
                int wx = center.getX() + dx;
                int wz = center.getZ() + dz;
                for (int y = minY; y <= maxY; y++) {
                    queue.add(new BlockPos(wx, y, wz));
                }
            }
        }

        scanQueue = queue;
        spawnablePositions.clear();
        torchPlacementPositions.clear();
        scanIndex = 0;
    }

    // ==================== TORCH grid-NMS ====================

    /**
     * Grid-NMS（Non-Maximum Suppression）：每 TORCH_GRID³ 网格单元仅保留
     * 一个最接近单元中心的可刷怪位置。这样火把间距 ≥ 7 格，
     * 单根火把 (light 14) 覆盖半径 13 manhattan 可完全重叠。
     *
     * <p>O(n)，在扫描循环结束时调用一次。
     */
    private void computeTorchPlacement() {
        torchPlacementPositions.clear();
        if (spawnablePositions.isEmpty()) return;

        int g = TORCH_GRID;
        Map<Long, BlockPos> bestPerCell = new HashMap<>();
        Map<Long, Double>   bestDist    = new HashMap<>();

        for (BlockPos pos : spawnablePositions) {
            long key = torchGridKey(pos, g);
            // 选离网格中心最近的位置（= 黑暗区域的"几何中心"近似）
            double cx = (Math.floorDiv(pos.getX(), g) + 0.5) * g;
            double cz = (Math.floorDiv(pos.getZ(), g) + 0.5) * g;
            double d  = (pos.getX() - cx) * (pos.getX() - cx)
                      + (pos.getZ() - cz) * (pos.getZ() - cz);

            if (d < bestDist.getOrDefault(key, Double.MAX_VALUE)) {
                bestPerCell.put(key, pos);
                bestDist.put(key, d);
            }
        }

        torchPlacementPositions.addAll(bestPerCell.values());
    }

    /** 3D 网格键：将 (x, y, z) 映射到 (gx, gy, gz) 网格单元 */
    private static long torchGridKey(BlockPos pos, int g) {
        int gx = Math.floorDiv(pos.getX(), g);
        int gy = Math.floorDiv(pos.getY(), g);
        int gz = Math.floorDiv(pos.getZ(), g);
        return ((long) gx << 40) | ((long) (gy & 0xFFFFF) << 20) | (gz & 0xFFFFF);
    }

    // ==================== 公共 API（Printer 消费） ====================

    /** 当前模式 */
    public Mode getMode() { return mode.get(); }

    /**
     * 返回 Printer 应放置方块的位置集合。
     *
     * <p>TORCH 模式返回 grid-NMS 后的最优火把位置（稀疏、高覆盖）。
     * SLAB/BUTTON 模式返回全部可刷怪位置（必须全覆盖，几何阻刷无覆盖半径）。
     */
    public Set<BlockPos> getPlacementPositions() {
        return switch (mode.get()) {
            case TORCH -> Collections.unmodifiableSet(torchPlacementPositions);
            case SLAB, BUTTON -> Collections.unmodifiableSet(spawnablePositions);
        };
    }

    /** 返回当前检测到的所有可刷怪位置（渲染用，含 TORCH 模式的全部暗点） */
    public Set<BlockPos> getSpawnablePositions() {
        return Collections.unmodifiableSet(spawnablePositions);
    }

    /**
     * 返回当前模式对应的目标方块状态。
     *
     * <p>Printer 在生成 overlay 放置任务时使用此状态作为 {@code desiredState}。
     * <ul>
     *   <li>SLAB → 下半砖（确定性，无朝向问题）</li>
     *   <li>BUTTON → 地板按钮 facing=NORTH（Printer 的 resolver 会自动旋转匹配）</li>
     *   <li>TORCH → 地面火把（无可变属性）</li>
     * </ul>
     */
    public BlockState getDesiredState() {
        return switch (mode.get()) {
            case SLAB -> {
                Block b = slabBlock.get().isEmpty() ? Blocks.SMOOTH_STONE_SLAB : slabBlock.get().getFirst();
                // 容错：如果用户选了非 SlabBlock，使用默认
                if (!(b instanceof SlabBlock)) b = Blocks.SMOOTH_STONE_SLAB;
                yield b.getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM);
            }
            case BUTTON -> {
                Block b = buttonBlock.get().isEmpty() ? Blocks.STONE_BUTTON : buttonBlock.get().getFirst();
                if (!(b instanceof ButtonBlock)) b = Blocks.STONE_BUTTON;
                yield b.getDefaultState()
                    .with(Properties.BLOCK_FACE, BlockFace.FLOOR)
                    .with(Properties.HORIZONTAL_FACING, Direction.NORTH);
            }
            case TORCH -> Blocks.TORCH.getDefaultState();
        };
    }

    /** 当前模式使用的物品（Printer 物品切换参考） */
    public net.minecraft.item.Item getRequiredItem() {
        return getDesiredState().getBlock().asItem();
    }

    // ==================== 渲染 ====================

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderOverlay.get() || mc.player == null) return;

        double rRange2 = renderRange.get() * (double) renderRange.get();
        Vec3d eye = mc.player.getEyePos();
        SettingColor sc = markerColor.get();
        SettingColor lc = markerLineColor.get();

        for (BlockPos pos : spawnablePositions) {
            double dist2 = eye.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (dist2 > rRange2) continue;

            // 扁平标记：贴地薄片（0.01 厚），视觉类似 MiniHUD 的光照标记
            double y = pos.getY() + 0.005;
            net.minecraft.util.math.Box box = new net.minecraft.util.math.Box(
                pos.getX() + 0.1, y, pos.getZ() + 0.1,
                pos.getX() + 0.9, y + 0.01, pos.getZ() + 0.9);
            event.renderer.box(box, sc, lc, ShapeMode.Both, 0);
        }
    }
}
