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
 * <p>三段式布局：
 * <ol>
 *   <li><b>状态概览</b> — 总进度、当前状态机状态、目标坐标</li>
 *   <li><b>容器明细</b> — 每个容器的坐标、类型、状态、蓝图需求</li>
 *   <li><b>物品汇总</b> — 按物品类型聚合的总需求 vs 玩家库存</li>
 * </ol>
 *
 * <p>注意：Meteor GUI 的 {@code theme.label()} 不支持 Minecraft 的 {@code §} 颜色码，
 * 使用 {@link WLabel#color} 字段设置颜色。
 */
public class ContainerFillScreen extends WindowScreen {

    // 语义颜色常量（ARGB 去掉 alpha，由 WLabel 内部处理）
    private static final Color COLOR_GREEN  = new Color(85, 255, 85);     // 成功/满足
    private static final Color COLOR_RED    = new Color(255, 85, 85);     // 失败/缺失
    private static final Color COLOR_YELLOW = new Color(255, 255, 85);    // 进行中
    private static final Color COLOR_GRAY   = new Color(170, 170, 170);   // 次要信息
    private static final Color COLOR_ORANGE = new Color(255, 170, 0);     // 警告

    private final ContainerFillManager manager;
    private final int range;

    public ContainerFillScreen(GuiTheme theme, ContainerFillManager manager, int range) {
        super(theme, "Container Fill");
        this.manager = manager;
        this.range = range;
    }

    @Override
    public void initWidgets() {
        if (mc.player == null || mc.world == null) {
            add(theme.label("World not loaded.")).expandX();
            return;
        }

        Vec3d eye = mc.player.getEyePos();
        double rangeSq = (double) range * range;

        // ── 收集范围内容器数据 ──
        List<Entry> entries = new ArrayList<>();
        int total = 0, satisfied = 0;

        for (BlockPos pos : manager.getRegisteredPositions()) {
            double dSq = eye.squaredDistanceTo(Vec3d.ofCenter(pos));
            if (dSq > rangeSq) continue;

            Map<Item, Integer> needs = manager.getNeedsAt(pos);
            if (needs == null || needs.isEmpty()) continue;

            boolean ok = manager.isSatisfied(pos);
            total++;
            if (ok) satisfied++;

            Item icon = null;
            BlockEntity be = mc.world.getBlockEntity(pos);
            if (be != null) icon = be.getCachedState().getBlock().asItem();

            entries.add(new Entry(pos, icon, ok, needs, dSq));
        }
        entries.sort(Comparator.comparingDouble(e -> e.distSq));

        // ════════════════════════════════════════
        //  §1  状态概览
        // ════════════════════════════════════════
        ContainerFillManager.State st = manager.getState();
        String stateText = switch (st) {
            case IDLE      -> "Idle";
            case PREPARING -> "Preparing...";
            case ARMED_OPEN -> "Armed (open)";
            case OPENING   -> "Opening...";
            case COOLDOWN  -> "Cooldown";
        };
        Color stateColor = switch (st) {
            case IDLE      -> COLOR_GRAY;
            case PREPARING, ARMED_OPEN, OPENING -> COLOR_YELLOW;
            case COOLDOWN  -> COLOR_ORANGE;
        };

        // 进度行
        WLabel progressLabel = add(theme.label("Progress: " + satisfied + " / " + total
            + "  |  State: " + stateText)).expandX().widget();
        progressLabel.color = (satisfied == total && total > 0) ? COLOR_GREEN : stateColor;

        // 当前目标位置
        BlockPos target = manager.getTargetPos();
        if (target != null) {
            WLabel targetLabel = add(theme.label(
                "  Target: " + target.getX() + ", " + target.getY() + ", " + target.getZ())).widget();
            targetLabel.color = COLOR_GRAY;
        }

        add(theme.horizontalSeparator()).expandX();

        if (entries.isEmpty()) {
            WLabel emptyLabel = add(theme.label("No schematic containers in range.")).expandX().widget();
            emptyLabel.color = COLOR_GRAY;
            return;
        }

        // ════════════════════════════════════════
        //  §2  容器明细
        // ════════════════════════════════════════
        add(theme.label("Containers", true)).expandX();

        WTable table = add(theme.table()).expandX().widget();
        table.add(theme.label("Pos", true));
        table.add(theme.label("Type", true));
        table.add(theme.label("Status", true));
        table.add(theme.label("Required Items", true));
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

            // 状态标示
            WLabel statusLabel = table.add(theme.label(e.satisfied ? "\u2713" : "\u2717")).widget();
            statusLabel.color = e.satisfied ? COLOR_GREEN : COLOR_RED;

            // 蓝图需求物品
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (var ni : e.needs.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(ni.getKey().getName().getString())
                  .append(" x").append(ni.getValue());
            }
            WLabel needsLabel = table.add(theme.label(sb.toString())).widget();
            needsLabel.color = e.satisfied ? COLOR_GREEN : Color.WHITE;

            table.row();
        }

        // ════════════════════════════════════════
        //  §3  物品汇总
        // ════════════════════════════════════════
        add(theme.horizontalSeparator()).expandX();
        add(theme.label("Item Summary", true)).expandX();

        // 聚合所有未满足容器的物品需求
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

    private record Entry(BlockPos pos, Item icon, boolean satisfied,
                         Map<Item, Integer> needs, double distSq) {}
}
