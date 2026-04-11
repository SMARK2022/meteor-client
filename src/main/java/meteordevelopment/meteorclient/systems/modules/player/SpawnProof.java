package meteordevelopment.meteorclient.systems.modules.player;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.renderer.GL;
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
import net.minecraft.text.Text;
import net.minecraft.util.math.*;

import java.util.*;

/**
 * SpawnProof — hostile mob 刷怪面扫描器 + 虚拟覆盖层任务提供者。
 *
 * <p>两层扫描体系：
 * <ol>
 *   <li><b>实时扫描</b> — 每 tick 全量扫描玩家周围（默认 6 格），输出→ Printer</li>
 *   <li><b>全域分析</b> — 按键触发，AFK 中心 + 缓存半径一次性扫描，输出→ 缓存 + 报告</li>
 * </ol>
 *
 * <p>空间索引缓存（4³ cell）持久化所有已知可刷怪位置，实时扫描覆盖写入。
 * 渲染取并集（缓存 ∪ 实时）中最近 N 个，双通道深度（正常+穿透）。
 */
public class SpawnProof extends Module {

    public enum Mode { SLAB, BUTTON, TORCH }

    // ==================== 设置 ====================

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAfk     = settings.createGroup("AFK / 分析");
    private final SettingGroup sgRender  = settings.createGroup("渲染");

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("防刷模式。SLAB = 下半砖 / BUTTON = 按钮 / TORCH = 火把")
        .defaultValue(Mode.SLAB)
        .build());

    private final Setting<List<Block>> slabBlock = sgGeneral.add(new BlockListSetting.Builder()
        .name("slab-block")
        .description("SLAB 模式使用的半砖方块（取第一个）。")
        .defaultValue(List.of(Blocks.SMOOTH_STONE_SLAB))
        .visible(() -> mode.get() == Mode.SLAB)
        .build());

    private final Setting<List<Block>> buttonBlock = sgGeneral.add(new BlockListSetting.Builder()
        .name("button-block")
        .description("BUTTON 模式使用的按钮方块（取第一个）。")
        .defaultValue(List.of(Blocks.STONE_BUTTON))
        .visible(() -> mode.get() == Mode.BUTTON)
        .build());

    private final Setting<Integer> realtimeRange = sgGeneral.add(new IntSetting.Builder()
        .name("realtime-range")
        .description("实时扫描半径。")
        .defaultValue(6).min(4).sliderRange(4, 12)
        .build());

    private final Setting<Integer> scanInterval = sgGeneral.add(new IntSetting.Builder()
        .name("scan-interval")
        .description("实时扫描间隔（tick），1 = 每 tick，2 = 每 2 tick。")
        .defaultValue(2).min(1).sliderRange(1, 10)
        .build());

    // ── AFK / 分析 ──

    private final Setting<Integer> cacheRadius = sgAfk.add(new IntSetting.Builder()
        .name("cache-radius")
        .description("缓存半径（AFK 中心起算），全域分析的扫描范围。")
        .defaultValue(128).min(16).sliderRange(16, 200)
        .build());

    // ── 渲染 ──

    private final Setting<Boolean> renderOverlay = sgRender.add(new BoolSetting.Builder()
        .name("render-overlay")
        .description("显示可刷怪面高亮标记。")
        .defaultValue(true)
        .build());

    private final Setting<Integer> maxHighlights = sgRender.add(new IntSetting.Builder()
        .name("max-highlights")
        .description("同时显示的最大高亮标记数。")
        .defaultValue(25).min(1).sliderRange(1, 100)
        .visible(renderOverlay::get)
        .build());

    private final Setting<SettingColor> markerColor = sgRender.add(new ColorSetting.Builder()
        .name("marker-color")
        .description("标记填充色（穿透通道自动取半 alpha）。")
        .defaultValue(new SettingColor(255, 50, 50, 153))
        .visible(renderOverlay::get)
        .build());

    // ==================== 内部状态 ====================

    // 实时扫描（Printer 消费）
    private final Set<BlockPos> spawnablePositions = new HashSet<>();
    private final Set<BlockPos> torchPlacementPositions = new HashSet<>();

    // 空间索引缓存（4³ cell，受 AFK+cacheRadius 约束）
    private final Map<Long, List<BlockPos>> cellIndex = new HashMap<>();
    private final Set<BlockPos> cachedSet = new HashSet<>();

    // 全域分析（游标式 O(1) 内存）
    private boolean analysisActive;
    private int aDx, aDz, aY, aMinY, aMaxY, aHRange;
    private int aFound;
    private long aChecked;

    private BlockPos afkCenter;
    private List<BlockPos> renderHighlights = Collections.emptyList();
    private SettingColor ghostColor;
    private int hlTick;
    private int scanTick;

    private static final int TORCH_GRID = 7;
    private static final int HL_INTERVAL = 5;
    private static final int ANALYSIS_BUDGET = 16384;
    private static final int CELL_BITS = 2; // 4³ blocks per cell

    // ==================== 生命周期 ====================

    public SpawnProof() {
        super(Categories.Player, "spawn-proof",
            "扫描 hostile mob 刷怪面，联动 Printer 自动放置防刷方块。");
    }

    @Override
    public void onActivate() {
        spawnablePositions.clear();
        torchPlacementPositions.clear();
        // cellIndex/cachedSet 不清除 — 跨 toggle 保留
        analysisActive = false;
        renderHighlights = Collections.emptyList();
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WTable table = theme.table();

        // AFK 中心行
        table.add(theme.label(afkCenter != null ? "AFK: " + afkCenter.toShortString() : "AFK: (未设定)"));
        WButton setBtn = table.add(theme.button("设定当前位置")).expandCellX().right().widget();
        setBtn.action = () -> {
            if (mc.player != null) {
                afkCenter = mc.player.getBlockPos().toImmutable();
                info("AFK 中心 → %s", afkCenter.toShortString());
            }
        };
        WButton clearBtn = table.add(theme.button("清除")).right().widget();
        clearBtn.action = () -> {
            afkCenter = null;
            cacheClear();
            info("AFK 中心已清除，缓存已清空。");
        };
        table.row();

        // 分析按钮
        WButton analyzeBtn = table.add(theme.button("运行全域分析")).expandCellX().right().widget();
        analyzeBtn.action = this::startAnalysis;
        table.add(theme.label("r=" + cacheRadius.get()));

        return table;
    }

    @Override
    public void onDeactivate() {
        spawnablePositions.clear();
        torchPlacementPositions.clear();
        analysisActive = false;
        renderHighlights = Collections.emptyList();
    }

    // ==================== 主 Tick ====================

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.world == null || mc.player == null) return;

        if (analysisActive) tickAnalysis();

        if (++scanTick >= scanInterval.get()) {
            scanTick = 0;
            scanRealtime();
        }

        if (++hlTick >= HL_INTERVAL) {
            hlTick = 0;
            updateRenderHighlights();
        }
    }

    /**
     * 每 tick 全量扫描玩家周围 realtimeRange 圆柱区域。
     * 使用 BlockPos.Mutable 复用坐标，仅为命中位置创建 Immutable。
     */
    private void scanRealtime() {
        int hRange = realtimeRange.get();
        int hRange2 = hRange * hRange;
        boolean isTorch = mode.get() == Mode.TORCH;
        BlockPos center = mc.player.getBlockPos();
        int minY = Math.max(mc.world.getBottomY() + 1, center.getY() - hRange);
        int maxY = Math.min(mc.world.getTopYInclusive() - 1, center.getY() + hRange);

        spawnablePositions.clear();
        BlockPos.Mutable mpos = new BlockPos.Mutable();

        for (int dx = -hRange; dx <= hRange; dx++)
            for (int dz = -hRange; dz <= hRange; dz++) {
                if (dx * dx + dz * dz > hRange2) continue;
                int wx = center.getX() + dx, wz = center.getZ() + dz;
                for (int y = minY; y <= maxY; y++) {
                    mpos.set(wx, y, wz);

                    if (SpawnCheckHelper.quickRejectNotSpawnable(mc.world, mpos)) {
                        cacheRemove(mpos);
                        continue;
                    }

                    boolean spawnable = isTorch
                        ? SpawnCheckHelper.canHostileSpawnAt(mc.world, mpos)
                        : SpawnCheckHelper.isGeometricSpawnable(mc.world, mpos);

                    if (spawnable) {
                        BlockPos imm = mpos.toImmutable();
                        spawnablePositions.add(imm);
                        cacheAdd(imm);
                    } else {
                        cacheRemove(mpos);
                    }
                }
            }

        if (isTorch) computeTorchPlacement();
    }

    // ==================== TORCH grid-NMS ====================

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

        aHRange = cacheRadius.get();
        aMinY = Math.max(mc.world.getBottomY() + 1, afkCenter.getY() - 64);
        aMaxY = Math.min(mc.world.getTopYInclusive() - 1, afkCenter.getY() + 64);
        aDx = -aHRange; aDz = -aHRange; aY = aMinY;
        aFound = 0; aChecked = 0;
        cacheClear();
        analysisActive = true;
        info("§7分析中: r=%d from %s...", aHRange, afkCenter.toShortString());
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

        if (aChecked % 65536 < ANALYSIS_BUDGET) {
            long total = (long) (Math.PI * aHRange * aHRange) * (aMaxY - aMinY + 1);
            int pct = total > 0 ? (int) (aChecked * 100 / total) : 0;
            mc.player.sendMessage(
                Text.literal("§7[SpawnProof] " + pct + "% (" + aFound + " found)"), true);
        }
    }

    private void advanceColumn() {
        aY = aMinY; aDz++;
        if (aDz > aHRange) { aDz = -aHRange; aDx++; }
    }

    private void finishAnalysis() {
        analysisActive = false;
        int needed = mode.get() == Mode.TORCH ? countTorchGridCells(cachedSet) : aFound;
        String unit = switch (mode.get()) {
            case SLAB -> "slabs"; case BUTTON -> "buttons"; case TORCH -> "torches (NMS)";
        };
        info("§a═══ SpawnProof 分析报告 ═══");
        info("中心: %s  半径: %d", afkCenter.toShortString(), aHRange);
        info("检查: %,d  可刷怪面: §c%,d§r", aChecked, aFound);
        info("模式 %s → §e%,d§r %s", mode.get(), needed, unit);
    }

    private int countTorchGridCells(Set<BlockPos> src) {
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

    /** AFK 中心 + cacheRadius 范围内才缓存；无 AFK 中心时不限 */
    private boolean isWithinCacheBounds(BlockPos pos) {
        if (afkCenter == null) return true;
        int dx = pos.getX() - afkCenter.getX(), dz = pos.getZ() - afkCenter.getZ();
        int r = cacheRadius.get();
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
     * 空间索引扩展搜索（缓存）+ spawnablePositions 补充（实时），
     * 取并集中最近 N 个坐标。
     */
    private void updateRenderHighlights() {
        if (!renderOverlay.get() || mc.player == null) {
            renderHighlights = Collections.emptyList();
            return;
        }

        Vec3d eye = mc.player.getEyePos();
        int max = maxHighlights.get();
        int ecx = (int) Math.floor(eye.x) >> CELL_BITS;
        int ecy = (int) Math.floor(eye.y) >> CELL_BITS;
        int ecz = (int) Math.floor(eye.z) >> CELL_BITS;
        // 搜索覆盖整个缓存半径（无渲染距离限制，稀疏跳空支撑高效扩展）
        int maxR = (cacheRadius.get() >> CELL_BITS) + 1;

        PriorityQueue<BlockPos> pq = new PriorityQueue<>(max + 1,
            Comparator.comparingDouble((BlockPos p) ->
                -eye.squaredDistanceTo(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)));

        // 1) 空间索引 Chebyshev shell 扩展
        outer:
        for (int r = 0; r <= maxR; r++) {
            for (int dx = -r; dx <= r; dx++)
                for (int dy = -r; dy <= r; dy++)
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz)) != r) continue;
                        List<BlockPos> cell = cellIndex.get(cellKeyDirect(ecx + dx, ecy + dy, ecz + dz));
                        if (cell == null) continue;
                        for (BlockPos pos : cell) {
                            pq.add(pos);
                            if (pq.size() > max) pq.poll();
                        }
                    }
            if (pq.size() >= max && !pq.isEmpty()) {
                double nextMin = Math.max(0, (r + 1) * (1 << CELL_BITS) - ((1 << CELL_BITS) - 1));
                double pqMax = eye.squaredDistanceTo(
                    pq.peek().getX() + 0.5, pq.peek().getY() + 0.5, pq.peek().getZ() + 0.5);
                if (pqMax < nextMin * nextMin) break outer;
            }
        }

        // 2) 补充 spawnablePositions（可能在缓存范围外）
        for (BlockPos pos : spawnablePositions) {
            if (cachedSet.contains(pos)) continue;
            pq.add(pos);
            if (pq.size() > max) pq.poll();
        }

        renderHighlights = new ArrayList<>(pq);

        SettingColor sc = markerColor.get();
        ghostColor = new SettingColor(sc.r, sc.g, sc.b, sc.a / 2);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderOverlay.get() || renderHighlights.isEmpty()) return;

        SettingColor sc = markerColor.get();

        for (BlockPos pos : renderHighlights) {
            double y = pos.getY() + 0.005;
            net.minecraft.util.math.Box box = new net.minecraft.util.math.Box(
                pos.getX() + 0.1, y, pos.getZ() + 0.1,
                pos.getX() + 0.9, y + 0.01, pos.getZ() + 0.9);

            event.renderer.box(box, sc, sc, ShapeMode.Both, 0);
            GL.disableDepth();
            event.renderer.box(box, ghostColor, ghostColor, ShapeMode.Both, 0);
            GL.enableDepth();
        }
    }

    // ==================== 公共 API（Printer 消费） ====================

    public Mode getMode() { return mode.get(); }

    /** Printer 应放置方块的位置集合 */
    public Set<BlockPos> getPlacementPositions() {
        return switch (mode.get()) {
            case TORCH -> Collections.unmodifiableSet(torchPlacementPositions);
            case SLAB, BUTTON -> Collections.unmodifiableSet(spawnablePositions);
        };
    }

    /** 当前模式对应的目标方块状态 */
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
