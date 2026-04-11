package meteordevelopment.meteorclient.systems.modules.player;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.GL;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.printer.SpawnCheckHelper;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.block.enums.BlockFace;
import net.minecraft.block.enums.SlabType;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.math.*;

import java.util.*;

/**
 * SpawnProof — hostile mob 刷怪面扫描器 + 虚拟覆盖层任务提供者。
 *
 * <p>两层扫描体系：
 * <ol>
 *   <li><b>实时扫描</b> — 增量预算制，玩家周围连续巡检，输出→ Printer 放置任务</li>
 *   <li><b>全域分析</b> — 按键触发，AFK 中心 + 刷怪半径一次性扫描，输出→ 缓存 + 报告</li>
 * </ol>
 *
 * <p>缓存层持久化所有已知可刷怪位置（实时更新覆盖写入），
 * 即使玩家离开也保持高亮提示。渲染仅显示最近 N 个坐标，
 * 双通道深度测试（正常+穿透）保证墙后可见。
 *
 * <p><b>SpawnProof 自身不执行任何放置操作，只负责"算任务"。</b>
 * Printer 启用 spawn-proof 开关后消费 {@link #getPlacementPositions()} 执行放置。
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
    private final SettingGroup sgAfk     = settings.createGroup("AFK / Analysis");
    private final SettingGroup sgRender  = settings.createGroup("Render");

    // ── 通用 ──

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("SLAB = 下半砖永久几何 / BUTTON = 按钮轻量覆盖 / TORCH = 火把照明")
        .defaultValue(Mode.SLAB)
        .build());

    private final Setting<List<Block>> slabBlock = sgGeneral.add(new BlockListSetting.Builder()
        .name("slab-block")
        .description("SLAB 模式使用的方块（取第一个）。")
        .defaultValue(List.of(Blocks.SMOOTH_STONE_SLAB))
        .visible(() -> mode.get() == Mode.SLAB)
        .build());

    private final Setting<List<Block>> buttonBlock = sgGeneral.add(new BlockListSetting.Builder()
        .name("button-block")
        .description("BUTTON 模式使用的方块（取第一个）。")
        .defaultValue(List.of(Blocks.STONE_BUTTON))
        .visible(() -> mode.get() == Mode.BUTTON)
        .build());

    private final Setting<Integer> horizontalRange = sgGeneral.add(new IntSetting.Builder()
        .name("horizontal-range")
        .description("实时扫描水平半径。")
        .defaultValue(24).min(8).sliderRange(8, 64)
        .build());

    private final Setting<Integer> verticalRange = sgGeneral.add(new IntSetting.Builder()
        .name("vertical-range")
        .description("实时扫描垂直范围 (±)。")
        .defaultValue(16).min(4).sliderRange(4, 48)
        .build());

    private final Setting<Integer> scanBudget = sgGeneral.add(new IntSetting.Builder()
        .name("scan-budget")
        .description("每 tick 最大检查数。")
        .defaultValue(2048).min(128).sliderRange(128, 8192)
        .build());

    // ── AFK / 分析 ──

    private final Setting<Keybind> setAfkCenterKey = sgAfk.add(new KeybindSetting.Builder()
        .name("set-afk-center")
        .description("按下设定 AFK 中心为当前位置。")
        .defaultValue(Keybind.none())
        .action(() -> {
            if (mc.player != null) {
                this.afkCenter = mc.player.getBlockPos().toImmutable();
                info("AFK center → %s", this.afkCenter.toShortString());
            }
        })
        .build());

    private final Setting<Integer> spawnRadius = sgAfk.add(new IntSetting.Builder()
        .name("spawn-radius")
        .description("刷怪半径（AFK 中心起算），用于全域分析。")
        .defaultValue(128).min(16).sliderRange(16, 200)
        .build());

    private final Setting<Keybind> analyzeKey = sgAfk.add(new KeybindSetting.Builder()
        .name("analyze")
        .description("按下运行全域刷怪面分析（结果缓存并显示报告）。")
        .defaultValue(Keybind.none())
        .action(this::startAnalysis)
        .build());

    // ── 渲染 ──

    private final Setting<Boolean> renderOverlay = sgRender.add(new BoolSetting.Builder()
        .name("render-overlay")
        .description("显示可刷怪面高亮标记。")
        .defaultValue(true)
        .build());

    private final Setting<Integer> renderRange = sgRender.add(new IntSetting.Builder()
        .name("render-range")
        .description("高亮渲染最大距离。")
        .defaultValue(48).min(4).sliderRange(4, 96)
        .visible(renderOverlay::get)
        .build());

    private final Setting<Integer> maxHighlights = sgRender.add(new IntSetting.Builder()
        .name("max-highlights")
        .description("同时显示的最大高亮标记数。")
        .defaultValue(25).min(1).sliderRange(1, 100)
        .visible(renderOverlay::get)
        .build());

    private final Setting<SettingColor> markerColor = sgRender.add(new ColorSetting.Builder()
        .name("marker-color")
        .description("标记填充色（alpha = 正常通道透明度，穿透通道取半）。")
        .defaultValue(new SettingColor(255, 50, 50, 153))
        .visible(renderOverlay::get)
        .build());

    private final Setting<SettingColor> markerLineColor = sgRender.add(new ColorSetting.Builder()
        .name("marker-line-color")
        .description("标记轮廓色。")
        .defaultValue(new SettingColor(255, 50, 50, 200))
        .visible(renderOverlay::get)
        .build());

    // ==================== 内部状态 ====================

    // ── 实时扫描（Printer 消费） ──
    private final Set<BlockPos> spawnablePositions = new HashSet<>();
    private final Set<BlockPos> torchPlacementPositions = new HashSet<>();
    private List<BlockPos> scanQueue = Collections.emptyList();
    private int scanIndex;
    private BlockPos lastCenter;

    // ── 空间索引缓存（4³ cell，渲染 + 持久化提醒，受 AFK 中心+半径约束） ──
    private final Map<Long, List<BlockPos>> cellIndex = new HashMap<>();
    private final Set<BlockPos> cachedSet = new HashSet<>();

    // ── 全域分析（游标式 O(1) 内存，不预分配队列） ──
    private boolean analysisActive;
    private int aDx, aDz, aY, aMinY, aMaxY, aHRange;
    private int aFound;
    private long aChecked;

    // ── AFK 中心 ──
    private BlockPos afkCenter;

    // ── 渲染缓存 ──
    private List<BlockPos> renderHighlights = Collections.emptyList();
    private SettingColor ghostFill, ghostLine;
    private int hlTick;

    // ── 常量 ──
    private static final double RESCAN_DIST_SQ = 4.0 * 4.0;
    private static final int TORCH_GRID = 7;
    private static final int HL_INTERVAL = 5;
    private static final int ANALYSIS_BUDGET = 16384;
    private static final int CELL_BITS = 2;  // 2^2 = 4 blocks per cell edge

    // ==================== 生命周期 ====================

    public SpawnProof() {
        super(Categories.Player, "spawn-proof",
            "Scans hostile mob spawn surfaces and provides overlay tasks for Printer.");
    }

    @Override
    public void onActivate() {
        spawnablePositions.clear();
        torchPlacementPositions.clear();
        // cellIndex/cachedSet 不清除 — 跨 toggle 保留历史缓存
        scanQueue = Collections.emptyList();
        scanIndex = 0;
        lastCenter = null;
        analysisActive = false;
        renderHighlights = Collections.emptyList();
    }

    @Override
    public void onDeactivate() {
        spawnablePositions.clear();
        torchPlacementPositions.clear();
        scanQueue = Collections.emptyList();
        analysisActive = false;
        renderHighlights = Collections.emptyList();
    }

    // ==================== 主 Tick ====================

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.world == null || mc.player == null) return;

        // 1) 全域分析推进
        if (analysisActive) tickAnalysis();

        // 2) 实时扫描
        BlockPos center = mc.player.getBlockPos();
        if (lastCenter == null || center.getSquaredDistance(lastCenter) > RESCAN_DIST_SQ) {
            rebuildScanQueue(center);
            lastCenter = center;
        }

        int budget = scanBudget.get();
        int queueSize = scanQueue.size();
        if (queueSize > 0) {
            boolean isTorch = mode.get() == Mode.TORCH;

            for (int i = 0; i < budget && scanIndex < queueSize; i++, scanIndex++) {
                BlockPos pos = scanQueue.get(scanIndex);

                if (SpawnCheckHelper.quickRejectNotSpawnable(mc.world, pos)) {
                    spawnablePositions.remove(pos);
                    cacheRemove(pos);
                    continue;
                }

                boolean spawnable = isTorch
                    ? SpawnCheckHelper.canHostileSpawnAt(mc.world, pos)
                    : SpawnCheckHelper.isGeometricSpawnable(mc.world, pos);

                if (spawnable) {
                    BlockPos imm = pos.toImmutable();
                    spawnablePositions.add(imm);
                    cacheAdd(imm);
                } else {
                    spawnablePositions.remove(pos);
                    cacheRemove(pos);
                }
            }

            if (scanIndex >= queueSize) {
                scanIndex = 0;
                if (isTorch) computeTorchPlacement();
            }
        }

        // 3) 定期更新渲染高亮
        if (++hlTick >= HL_INTERVAL) {
            hlTick = 0;
            updateRenderHighlights();
        }
    }

    private void rebuildScanQueue(BlockPos center) {
        int hRange = horizontalRange.get(), vRange = verticalRange.get();
        int hRange2 = hRange * hRange;
        int minY = Math.max(mc.world.getBottomY() + 1, center.getY() - vRange);
        int maxY = Math.min(mc.world.getTopYInclusive() - 1, center.getY() + vRange);

        List<BlockPos> queue = new ArrayList<>((int) (Math.PI * hRange2 * (maxY - minY + 1)));
        for (int dx = -hRange; dx <= hRange; dx++)
            for (int dz = -hRange; dz <= hRange; dz++) {
                if (dx * dx + dz * dz > hRange2) continue;
                int wx = center.getX() + dx, wz = center.getZ() + dz;
                for (int y = minY; y <= maxY; y++) queue.add(new BlockPos(wx, y, wz));
            }

        scanQueue = queue;
        spawnablePositions.clear();
        torchPlacementPositions.clear();
        scanIndex = 0;
    }

    // ==================== TORCH grid-NMS ====================

    /**
     * Grid-NMS：每 TORCH_GRID³ 网格单元仅保留一个最接近单元中心的可刷怪位置。
     * 火把间距 ≥ 7 格，单根火把 (light 14) 覆盖半径 13 manhattan 完全重叠。
     * O(n)，扫描循环结束时调用一次。
     */
    private void computeTorchPlacement() {
        torchPlacementPositions.clear();
        if (spawnablePositions.isEmpty()) return;

        int g = TORCH_GRID;
        Map<Long, BlockPos> best = new HashMap<>();
        Map<Long, Double> bestD = new HashMap<>();

        for (BlockPos pos : spawnablePositions) {
            long key = gridKey(pos, g);
            double cx = (Math.floorDiv(pos.getX(), g) + 0.5) * g;
            double cz = (Math.floorDiv(pos.getZ(), g) + 0.5) * g;
            double d = (pos.getX() - cx) * (pos.getX() - cx) + (pos.getZ() - cz) * (pos.getZ() - cz);
            if (d < bestD.getOrDefault(key, Double.MAX_VALUE)) {
                best.put(key, pos);
                bestD.put(key, d);
            }
        }
        torchPlacementPositions.addAll(best.values());
    }

    private static long gridKey(BlockPos pos, int g) {
        int gx = Math.floorDiv(pos.getX(), g);
        int gy = Math.floorDiv(pos.getY(), g);
        int gz = Math.floorDiv(pos.getZ(), g);
        return ((long) gx << 40) | ((long) (gy & 0xFFFFF) << 20) | (gz & 0xFFFFF);
    }

    // ==================== 全域分析 ====================

    private void startAnalysis() {
        if (mc.world == null || mc.player == null) return;
        if (afkCenter == null) afkCenter = mc.player.getBlockPos().toImmutable();

        aHRange = spawnRadius.get();
        aMinY = Math.max(mc.world.getBottomY() + 1, afkCenter.getY() - 64);
        aMaxY = Math.min(mc.world.getTopYInclusive() - 1, afkCenter.getY() + 64);
        aDx = -aHRange; aDz = -aHRange; aY = aMinY;
        aFound = 0; aChecked = 0;
        cacheClear();
        analysisActive = true;
        info("§7Analyzing: r=%d from %s...", aHRange, afkCenter.toShortString());
    }

    private void tickAnalysis() {
        int hRange2 = aHRange * aHRange;
        boolean isTorch = mode.get() == Mode.TORCH;

        for (int i = 0; i < ANALYSIS_BUDGET; ) {
            if (aDx > aHRange) { finishAnalysis(); return; }
            if (aDx * aDx + aDz * aDz > hRange2) { advanceColumn(); continue; }

            int wx = afkCenter.getX() + aDx, wz = afkCenter.getZ() + aDz;
            if (!mc.world.isChunkLoaded(wx >> 4, wz >> 4)) { advanceColumn(); continue; }

            BlockPos pos = new BlockPos(wx, aY, wz);
            aChecked++; i++;

            if (!SpawnCheckHelper.quickRejectNotSpawnable(mc.world, pos)) {
                boolean spawnable = isTorch
                    ? SpawnCheckHelper.canHostileSpawnAt(mc.world, pos)
                    : SpawnCheckHelper.isGeometricSpawnable(mc.world, pos);
                if (spawnable) { cacheAdd(pos.toImmutable()); aFound++; }
            }

            aY++;
            if (aY > aMaxY) advanceColumn();
        }

        // 进度通知（action bar，不污染聊天）
        if (aChecked % 65536 < ANALYSIS_BUDGET) {
            long total = (long) (Math.PI * aHRange * aHRange) * (aMaxY - aMinY + 1);
            int pct = total > 0 ? (int) (aChecked * 100 / total) : 0;
            mc.player.sendMessage(
                Text.literal("§7[SpawnProof] Analyzing... " + pct + "% (" + aFound + " found)"), true);
        }
    }

    private void advanceColumn() {
        aY = aMinY; aDz++;
        if (aDz > aHRange) { aDz = -aHRange; aDx++; }
    }

    private void finishAnalysis() {
        analysisActive = false;
        int needed = mode.get() == Mode.TORCH ? countGridCells(cachedSet) : aFound;
        String unit = switch (mode.get()) {
            case SLAB -> "slabs"; case BUTTON -> "buttons"; case TORCH -> "torches (NMS)";
        };
        info("§a═══ SpawnProof Analysis ═══");
        info("Center: %s  Radius: %d", afkCenter.toShortString(), aHRange);
        info("Checked: %,d  Spawnable: §c%,d§r surfaces", aChecked, aFound);
        info("Mode %s → §e%,d§r %s needed", mode.get(), needed, unit);
    }

    /** 统计 TORCH 模式下 grid-NMS 后的火把数（= 非空网格单元数） */
    private int countGridCells(Set<BlockPos> src) {
        Set<Long> gridCells = new HashSet<>();
        for (BlockPos pos : src) gridCells.add(gridKey(pos, TORCH_GRID));
        return gridCells.size();
    }

    // ==================== 空间索引缓存 ====================

    private static long cellKeyDirect(int cx, int cy, int cz) {
        return ((long) cx << 40) | ((long) (cy & 0xFFFFF) << 20) | (cz & 0xFFFFF);
    }

    private static long cellKey(BlockPos pos) {
        return cellKeyDirect(pos.getX() >> CELL_BITS, pos.getY() >> CELL_BITS, pos.getZ() >> CELL_BITS);
    }

    /** 是否在 AFK 中心 + 刷怪半径范围内（无 AFK 中心时不限） */
    private boolean isWithinCacheBounds(BlockPos pos) {
        if (afkCenter == null) return true;
        int dx = pos.getX() - afkCenter.getX(), dz = pos.getZ() - afkCenter.getZ();
        int r = spawnRadius.get();
        return dx * dx + dz * dz <= r * r;
    }

    private void cacheAdd(BlockPos pos) {
        if (!isWithinCacheBounds(pos)) return;
        if (cachedSet.add(pos)) {
            cellIndex.computeIfAbsent(cellKey(pos), k -> new ArrayList<>(4)).add(pos);
        }
    }

    private void cacheRemove(BlockPos pos) {
        if (cachedSet.remove(pos)) {
            long key = cellKey(pos);
            List<BlockPos> list = cellIndex.get(key);
            if (list != null) { list.remove(pos); if (list.isEmpty()) cellIndex.remove(key); }
        }
    }

    private void cacheClear() { cachedSet.clear(); cellIndex.clear(); }

    // ==================== 渲染 ====================

    /**
     * 每 HL_INTERVAL tick 重算最近 N 个高亮坐标。
     * 空间索引扩展搜索 + spawnablePositions 补充（覆盖 AFK 范围外的实时数据）。
     */
    private void updateRenderHighlights() {
        if (!renderOverlay.get() || mc.player == null) {
            renderHighlights = Collections.emptyList();
            return;
        }

        Vec3d eye = mc.player.getEyePos();
        int maxD = renderRange.get(), max = maxHighlights.get();
        double maxDist2 = maxD * (double) maxD;
        int ecx = (int) Math.floor(eye.x) >> CELL_BITS;
        int ecy = (int) Math.floor(eye.y) >> CELL_BITS;
        int ecz = (int) Math.floor(eye.z) >> CELL_BITS;
        int maxR = (maxD + (1 << CELL_BITS) - 1) >> CELL_BITS;

        PriorityQueue<BlockPos> pq = new PriorityQueue<>(max + 1,
            Comparator.comparingDouble((BlockPos p) ->
                -eye.squaredDistanceTo(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)));

        // 1) 空间索引扩展搜索（cachedSet 覆盖区域）
        outer:
        for (int r = 0; r <= maxR; r++) {
            for (int dx = -r; dx <= r; dx++)
                for (int dy = -r; dy <= r; dy++)
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz)) != r) continue;
                        List<BlockPos> cell = cellIndex.get(cellKeyDirect(ecx + dx, ecy + dy, ecz + dz));
                        if (cell == null) continue;
                        for (BlockPos pos : cell) {
                            double d2 = eye.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                            if (d2 > maxDist2) continue;
                            pq.add(pos);
                            if (pq.size() > max) pq.poll();
                        }
                    }
            // 提前终止：PQ 已满且下一层最近距离必然超过当前最远
            if (pq.size() >= max && !pq.isEmpty()) {
                double nextMin = Math.max(0, (r + 1) * (1 << CELL_BITS) - ((1 << CELL_BITS) - 1));
                double pqMax = eye.squaredDistanceTo(
                    pq.peek().getX() + 0.5, pq.peek().getY() + 0.5, pq.peek().getZ() + 0.5);
                if (pqMax < nextMin * nextMin) break outer;
            }
        }

        // 2) 补充 spawnablePositions（可能在 AFK 范围外，但在实时扫描范围内）
        int ex = (int) eye.x, ey = (int) eye.y, ez = (int) eye.z;
        for (BlockPos pos : spawnablePositions) {
            if (cachedSet.contains(pos)) continue;
            if (Math.abs(pos.getX() - ex) > maxD || Math.abs(pos.getY() - ey) > maxD || Math.abs(pos.getZ() - ez) > maxD) continue;
            pq.add(pos);
            if (pq.size() > max) pq.poll();
        }

        renderHighlights = new ArrayList<>(pq);

        SettingColor sc = markerColor.get(), lc = markerLineColor.get();
        ghostFill = new SettingColor(sc.r, sc.g, sc.b, sc.a / 2);
        ghostLine = new SettingColor(lc.r, lc.g, lc.b, lc.a / 2);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderOverlay.get() || renderHighlights.isEmpty()) return;

        SettingColor sc = markerColor.get();
        SettingColor lc = markerLineColor.get();

        for (BlockPos pos : renderHighlights) {
            double y = pos.getY() + 0.005;
            net.minecraft.util.math.Box box = new net.minecraft.util.math.Box(
                pos.getX() + 0.1, y, pos.getZ() + 0.1,
                pos.getX() + 0.9, y + 0.01, pos.getZ() + 0.9);

            // Pass 1: 正常深度测试（完整 alpha）
            event.renderer.box(box, sc, lc, ShapeMode.Both, 0);
            // Pass 2: 关闭深度测试（半 alpha，墙后可见）
            GL.disableDepth();
            event.renderer.box(box, ghostFill, ghostLine, ShapeMode.Both, 0);
            GL.enableDepth();
        }
    }

    // ==================== 公共 API（Printer 消费） ====================

    /** 当前模式 */
    public Mode getMode() { return mode.get(); }

    /**
     * Printer 应放置方块的位置集合。
     * TORCH → grid-NMS 后的稀疏最优点 / SLAB|BUTTON → 全部可刷怪面。
     */
    public Set<BlockPos> getPlacementPositions() {
        return switch (mode.get()) {
            case TORCH -> Collections.unmodifiableSet(torchPlacementPositions);
            case SLAB, BUTTON -> Collections.unmodifiableSet(spawnablePositions);
        };
    }

    /** 所有检测到的可刷怪位置（含 TORCH 模式的全部暗点，渲染用） */
    public Set<BlockPos> getSpawnablePositions() {
        return Collections.unmodifiableSet(spawnablePositions);
    }

    /** 当前模式对应的目标方块状态（Printer 的 desiredState） */
    public BlockState getDesiredState() {
        return switch (mode.get()) {
            case SLAB -> {
                Block b = slabBlock.get().isEmpty() ? Blocks.SMOOTH_STONE_SLAB : slabBlock.get().getFirst();
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

    /** 当前模式使用的物品 */
    public net.minecraft.item.Item getRequiredItem() {
        return getDesiredState().getBlock().asItem();
    }
}
