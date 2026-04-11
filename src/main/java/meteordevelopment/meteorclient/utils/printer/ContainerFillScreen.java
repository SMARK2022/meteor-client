package meteordevelopment.meteorclient.utils.printer;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.WItemWithLabel;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * ContainerFillScreen — 容器填充详情 GUI
 *
 * <p>四段式布局：
 * <ol>
 *   <li><b>状态概览</b> — 总进度、状态机状态、目标坐标</li>
 *   <li><b>Nearby</b> — reach 范围内容器（pickTarget 工作集），显示 Need / Actual / Missing</li>
 *   <li><b>Cached</b> — 扫描范围内但超出 reach 的容器（缓存视图）</li>
 *   <li><b>物品汇总</b> — 按物品类型聚合的总需求 vs 玩家库存</li>
 * </ol>
 *
 * <p>注意：Meteor GUI 的 {@code theme.label()} 不支持 Minecraft 的 {@code §} 颜色码，
 * 使用 {@link WLabel#color} 字段设置颜色。
 */
public class ContainerFillScreen extends WindowScreen {

    // 语义颜色常量
    private static final Color COLOR_GREEN  = new Color(85, 255, 85);     // 满足
    private static final Color COLOR_RED    = new Color(255, 85, 85);     // 缺失
    private static final Color COLOR_YELLOW = new Color(255, 255, 85);    // 进行中
    private static final Color COLOR_GRAY   = new Color(170, 170, 170);   // 次要信息
    private static final Color COLOR_ORANGE = new Color(255, 170, 0);     // 警告/NEEDS_SPLIT

    private final ContainerFillManager manager;
    private final int range;
    private final double reachRange;

    public ContainerFillScreen(GuiTheme theme, ContainerFillManager manager, int range, double reachRange) {
        super(theme, "Container Fill");
        this.manager = manager;
        this.range = range;
        this.reachRange = reachRange;
    }

    @Override
    public void initWidgets() {
        if (mc.player == null || mc.world == null) {
            add(theme.label("World not loaded.")).expandX();
            return;
        }

        Vec3d eye = mc.player.getEyePos();
        double rangeSq = (double) range * range;
        double reachSq = reachRange * reachRange;

        // ── 收集并分组容器数据 ──
        List<Entry> nearby = new ArrayList<>();
        List<Entry> cached = new ArrayList<>();
        int total = 0, satisfied = 0;

        for (BlockPos pos : manager.getRegisteredPositions()) {
            double dSq = eye.squaredDistanceTo(Vec3d.ofCenter(pos));
            if (dSq > rangeSq) continue;

            Map<Item, Integer> needs = manager.getNeedsAt(pos);
            if (needs == null || needs.isEmpty()) continue;

            ContainerFillManager.ContainerSnapshot snap = manager.getSnapshot(pos);
            boolean ok = snap != null && snap.status() == ContainerFillManager.ContainerSnapshot.Status.SATISFIED;
            total++;
            if (ok) satisfied++;

            Item icon = null;
            BlockEntity be = mc.world.getBlockEntity(pos);
            if (be != null) icon = be.getCachedState().getBlock().asItem();

            Entry entry = new Entry(pos, icon, snap, needs, dSq);
            (dSq <= reachSq ? nearby : cached).add(entry);
        }
        nearby.sort(Comparator.comparingDouble(e -> e.distSq));
        cached.sort(Comparator.comparingDouble(e -> e.distSq));

        // ════════════════════════════════════════
        //  §1  状态概览
        // ════════════════════════════════════════
        ContainerFillManager.State st = manager.getState();
        String stateText = switch (st) {
            case IDLE       -> "Idle";
            case PREPARING  -> "Preparing...";
            case ARMED_OPEN -> "Armed (open)";
            case OPENING    -> "Opening...";
            case COOLDOWN   -> "Cooldown";
        };
        Color stateColor = switch (st) {
            case IDLE       -> COLOR_GRAY;
            case PREPARING, ARMED_OPEN, OPENING -> COLOR_YELLOW;
            case COOLDOWN   -> COLOR_ORANGE;
        };

        WLabel progressLabel = add(theme.label("Progress: " + satisfied + " / " + total
            + "  |  State: " + stateText)).expandX().widget();
        progressLabel.color = (satisfied == total && total > 0) ? COLOR_GREEN : stateColor;

        BlockPos target = manager.getTargetPos();
        if (target != null) {
            WLabel targetLabel = add(theme.label(
                "  Target: " + target.getX() + ", " + target.getY() + ", " + target.getZ())).widget();
            targetLabel.color = COLOR_GRAY;
        }

        // ════════════════════════════════════════
        //  §2  Nearby（reach 内工作集）
        // ════════════════════════════════════════
        add(theme.horizontalSeparator()).expandX();
        add(theme.label("Nearby  (reach " + String.format("%.1f", reachRange) + ")", true)).expandX();

        if (nearby.isEmpty()) {
            WLabel emptyLabel = add(theme.label("  No containers in reach.")).expandX().widget();
            emptyLabel.color = COLOR_GRAY;
        } else {
            addContainerTable(nearby);
        }

        // ════════════════════════════════════════
        //  §3  Cached（reach 外缓存视图）
        // ════════════════════════════════════════
        if (!cached.isEmpty()) {
            add(theme.horizontalSeparator()).expandX();
            add(theme.label("Cached  (range " + range + ")", true)).expandX();
            addContainerTable(cached);
        }

        // ════════════════════════════════════════
        //  §4  物品汇总
        // ════════════════════════════════════════
        add(theme.horizontalSeparator()).expandX();
        add(theme.label("Item Summary", true)).expandX();

        Map<Item, Integer> totalNeeds = manager.getAllUnsatisfiedItemNeeds();
        if (totalNeeds.isEmpty()) {
            WLabel allOk = add(theme.label("All containers satisfied!")).expandX().widget();
            allOk.color = COLOR_GREEN;
            return;
        }

        WTable itemTable = add(theme.table()).expandX().widget();
        itemTable.add(theme.label("Item", true));
        itemTable.add(theme.label("Need", true));
        itemTable.add(theme.label("Have", true));
        itemTable.add(theme.label("Verdict", true));
        itemTable.row();

        for (var ni : totalNeeds.entrySet()) {
            Item item = ni.getKey();
            int need = ni.getValue();
            int have = InvUtils.find(item).count();

            itemTable.add(new WItemWithLabel(new ItemStack(item), item.getName().getString()));
            itemTable.add(theme.label(String.valueOf(need)));
            itemTable.add(theme.label(String.valueOf(have)));

            boolean sufficient = have >= need;
            WLabel verdictLabel = itemTable.add(theme.label(
                sufficient ? "\u2713 OK" : "\u2717 -" + (need - have))).widget();
            verdictLabel.color = sufficient ? COLOR_GREEN : COLOR_RED;

            itemTable.row();
        }
    }

    // ==================== 容器明细表（Nearby / Cached 共用） ====================

    /**
     * 渲染容器列表，显示 Pos / Type / Status / Need / Actual / Missing。
     * Actual 和 Missing 列来源于 {@link ContainerFillManager.ContainerSnapshot}，
     * 若尚未打开过容器则显示 "?" 表示未知。
     */
    private void addContainerTable(List<Entry> entries) {
        WTable table = add(theme.table()).expandX().widget();
        table.add(theme.label("Pos", true));
        table.add(theme.label("Type", true));
        table.add(theme.label("Status", true));
        table.add(theme.label("Items", true));
        table.row();

        for (Entry e : entries) {
            // 坐标
            table.add(theme.label(e.pos.getX() + ", " + e.pos.getY() + ", " + e.pos.getZ()));

            // 类型 icon
            if (e.icon != null) {
                table.add(new WItemWithLabel(new ItemStack(e.icon), e.icon.getName().getString()));
            } else {
                WLabel unknownLabel = table.add(theme.label("?")).widget();
                unknownLabel.color = COLOR_GRAY;
            }

            // 状态标识（从快照推断）
            String statusText;
            Color statusColor;
            if (e.snapshot == null) {
                statusText = "?";
                statusColor = COLOR_GRAY;
            } else {
                statusText = switch (e.snapshot.status()) {
                    case SATISFIED    -> "\u2713";
                    case PARTIAL      -> "\u25B3";   // △
                    case NO_MATERIALS -> "\u2205";   // ∅
                };
                statusColor = switch (e.snapshot.status()) {
                    case SATISFIED    -> COLOR_GREEN;
                    case PARTIAL      -> COLOR_YELLOW;
                    case NO_MATERIALS -> COLOR_RED;
                };
            }
            WLabel statusLabel = table.add(theme.label(statusText)).widget();
            statusLabel.color = statusColor;

            // 物品明细：每项显示 "ItemName: actual/need (-missing)"
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (var ni : e.needs.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                Item item = ni.getKey();
                int need = ni.getValue();
                sb.append(item.getName().getString()).append(": ");

                if (e.snapshot != null && e.snapshot.actualTotals() != null) {
                    int actual = e.snapshot.actualTotals().getOrDefault(item, 0);
                    int missing = Math.max(0, need - actual);
                    sb.append(actual).append("/").append(need);
                    if (missing > 0) sb.append(" (-").append(missing).append(")");
                } else {
                    sb.append("?/").append(need);
                }
            }
            WLabel itemsLabel = table.add(theme.label(sb.toString())).widget();
            if (e.snapshot != null) {
                itemsLabel.color = e.snapshot.status() == ContainerFillManager.ContainerSnapshot.Status.SATISFIED
                    ? COLOR_GREEN : Color.WHITE;
            } else {
                itemsLabel.color = COLOR_GRAY;
            }

            table.row();
        }
    }

    private record Entry(BlockPos pos, Item icon, ContainerFillManager.ContainerSnapshot snapshot,
                         Map<Item, Integer> needs, double distSq) {}
}
