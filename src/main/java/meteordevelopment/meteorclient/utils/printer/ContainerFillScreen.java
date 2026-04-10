package meteordevelopment.meteorclient.utils.printer;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.WItemWithLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
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
 * <p>以可滚动表格形式展示当前蓝图中所有包含物品的容器信息：
 * <ul>
 *   <li>每行一个容器：坐标、类型 icon、满足状态、缺失物品列表</li>
 *   <li>底部汇总行：分物品满足情况 + 分容器满足情况</li>
 * </ul>
 *
 * <p>通过 Printer 模块中的快捷键绑定打开。
 */
public class ContainerFillScreen extends WindowScreen {

    private final ContainerFillManager manager;
    private final int range;

    /**
     * @param theme   GUI 主题
     * @param manager 容器填充管理器（数据来源）
     * @param range   显示范围（方块数）
     */
    public ContainerFillScreen(GuiTheme theme, ContainerFillManager manager, int range) {
        super(theme, "Container Fill Details");
        this.manager = manager;
        this.range = range;
    }

    @Override
    public void initWidgets() {
        if (mc.player == null || mc.world == null) {
            add(theme.label("No world loaded.")).expandX();
            return;
        }

        Vec3d playerPos = mc.player.getEyePos();
        double rangeSq = (double) range * range;

        // ── 收集范围内的容器数据 ──
        List<ContainerEntry> entries = new ArrayList<>();
        Map<Item, int[]> totalItemTally = new LinkedHashMap<>(); // item → [have, need]
        int totalContainers = 0;
        int satisfiedContainers = 0;

        for (BlockPos pos : manager.getRegisteredPositions()) {
            double distSq = playerPos.squaredDistanceTo(Vec3d.ofCenter(pos));
            if (distSq > rangeSq) continue;

            Map<Item, Integer> needs = manager.getNeedsAt(pos);
            if (needs == null || needs.isEmpty()) continue;

            boolean satisfied = manager.isSatisfied(pos);
            totalContainers++;
            if (satisfied) satisfiedContainers++;

            // 容器类型 icon
            Item containerIcon = null;
            BlockEntity be = mc.world.getBlockEntity(pos);
            if (be != null) containerIcon = be.getCachedState().getBlock().asItem();

            // 计算缺失物品
            Map<Item, Integer> missing = new LinkedHashMap<>();
            if (!satisfied) {
                for (var entry : needs.entrySet()) {
                    // 简化：这里展示蓝图需求量（实际缺失需要打开容器才能知道）
                    missing.put(entry.getKey(), entry.getValue());
                }
            }

            // 汇总物品需求
            for (var entry : needs.entrySet()) {
                int[] tally = totalItemTally.computeIfAbsent(entry.getKey(), k -> new int[2]);
                FindItemResult find = InvUtils.find(entry.getKey());
                // have 不累加（同一物品在多个容器中需要，但玩家持有量只算一次）
                tally[0] = find.found() ? find.count() : 0;
                tally[1] += entry.getValue();
            }

            entries.add(new ContainerEntry(pos, containerIcon, satisfied, needs, missing, distSq));
        }

        // 按距离排序
        entries.sort(Comparator.comparingDouble(e -> e.distSq));

        // ── 标题 ──
        String titleText = "Containers within " + range + " blocks: "
            + satisfiedContainers + "/" + totalContainers + " satisfied";
        add(theme.label(titleText)).expandX();
        add(theme.horizontalSeparator()).expandX();

        if (entries.isEmpty()) {
            add(theme.label("No schematic containers found in range.")).expandX();
            return;
        }

        // ── 容器明细表 ──
        WTable table = add(theme.table()).expandX().widget();

        // 表头
        table.add(theme.label("Pos", true));
        table.add(theme.label("Type", true));
        table.add(theme.label("Status", true));
        table.add(theme.label("Needed Items", true));
        table.row();

        for (ContainerEntry entry : entries) {
            // 坐标
            String posStr = entry.pos.getX() + ", " + entry.pos.getY() + ", " + entry.pos.getZ();
            table.add(theme.label(posStr));

            // 类型 icon
            if (entry.containerIcon != null) {
                table.add(new WItemWithLabel(new ItemStack(entry.containerIcon),
                    entry.containerIcon.getName().getString()));
            } else {
                table.add(theme.label("?"));
            }

            // 满足状态
            if (entry.satisfied) {
                table.add(theme.label("§a✓ OK"));
            } else {
                table.add(theme.label("§c✗ Missing"));
            }

            // 缺失物品
            if (entry.satisfied || entry.missing.isEmpty()) {
                table.add(theme.label("—"));
            } else {
                StringBuilder sb = new StringBuilder();
                boolean first = true;
                for (var mi : entry.missing.entrySet()) {
                    if (!first) sb.append(", ");
                    first = false;
                    sb.append(mi.getKey().getName().getString())
                      .append(" x").append(mi.getValue());
                }
                table.add(theme.label(sb.toString()));
            }

            table.row();
        }

        // ── 分隔线 ──
        add(theme.horizontalSeparator()).expandX();

        // ── 容器类型汇总 ──
        add(theme.label("Container Type Summary", true)).expandX();
        WTable typeTable = add(theme.table()).expandX().widget();

        Map<Item, int[]> typeSummaries = manager.getTypeSummaries();
        for (var entry : typeSummaries.entrySet()) {
            Item icon = entry.getKey();
            int[] counts = entry.getValue();
            typeTable.add(new WItemWithLabel(new ItemStack(icon), icon.getName().getString()));
            String countStr = counts[0] + " / " + counts[1]
                + (counts[0] == counts[1] ? " §a(OK)" : " §c(Incomplete)");
            typeTable.add(theme.label(countStr));
            typeTable.row();
        }

        // ── 物品需求汇总 ──
        add(theme.horizontalSeparator()).expandX();
        add(theme.label("Item Requirements Summary", true)).expandX();
        WTable itemTable = add(theme.table()).expandX().widget();

        itemTable.add(theme.label("Item", true));
        itemTable.add(theme.label("Have", true));
        itemTable.add(theme.label("Need", true));
        itemTable.add(theme.label("Status", true));
        itemTable.row();

        for (var entry : totalItemTally.entrySet()) {
            Item item = entry.getKey();
            int have = entry.getValue()[0];
            int need = entry.getValue()[1];

            itemTable.add(new WItemWithLabel(new ItemStack(item), item.getName().getString()));
            itemTable.add(theme.label(String.valueOf(have)));
            itemTable.add(theme.label(String.valueOf(need)));

            if (have >= need) {
                itemTable.add(theme.label("§a✓ Sufficient"));
            } else {
                int deficit = need - have;
                itemTable.add(theme.label("§c✗ Need " + deficit + " more"));
            }
            itemTable.row();
        }
    }

    /**
     * 容器条目数据。
     */
    private record ContainerEntry(
        BlockPos pos,
        Item containerIcon,
        boolean satisfied,
        Map<Item, Integer> needs,
        Map<Item, Integer> missing,
        double distSq
    ) {}
}
