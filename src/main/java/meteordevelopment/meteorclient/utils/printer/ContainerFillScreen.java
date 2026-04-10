package meteordevelopment.meteorclient.utils.printer;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.WItemWithLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
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
 * <p>三段式布局：
 * <ol>
 *   <li><b>状态概览</b> — 总进度、当前状态机状态</li>
 *   <li><b>容器明细</b> — 每个容器的坐标、类型、状态、蓝图需求</li>
 *   <li><b>物品汇总</b> — 按物品类型聚合的总需求 vs 玩家库存</li>
 * </ol>
 */
public class ContainerFillScreen extends WindowScreen {

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
        String stateLabel = switch (st) {
            case IDLE      -> "§7Idle";
            case PREPARING -> "§ePreparing...";
            case OPENING   -> "§eOpening...";
            case COOLDOWN  -> "§eCooldown";
        };

        add(theme.label("Progress: §f" + satisfied + " §7/ §f" + total
            + "  §8|  State: " + stateLabel)).expandX();

        BlockPos target = manager.getTargetPos();
        if (target != null) {
            add(theme.label("  §8Target: §f" + target.getX() + ", " + target.getY() + ", " + target.getZ()));
        }

        add(theme.horizontalSeparator()).expandX();

        if (entries.isEmpty()) {
            add(theme.label("§7No schematic containers in range.")).expandX();
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
                table.add(theme.label("§8?"));
            }

            // 状态
            table.add(theme.label(e.satisfied ? "§a✓" : "§c✗"));

            // 蓝图需求物品
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (var ni : e.needs.entrySet()) {
                if (!first) sb.append("§8, ");
                first = false;
                sb.append(e.satisfied ? "§a" : "§f")
                  .append(ni.getKey().getName().getString())
                  .append(" §7×").append(ni.getValue());
            }
            table.add(theme.label(sb.toString()));

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
            add(theme.label("§aAll containers satisfied!")).expandX();
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
            itemTable.add(theme.label("§f" + need));
            itemTable.add(theme.label("§f" + have));
            itemTable.add(theme.label(have >= need
                ? "§a✓ OK"
                : "§c✗ −" + (need - have)));
            itemTable.row();
        }
    }

    private record Entry(BlockPos pos, Item icon, boolean satisfied,
                         Map<Item, Integer> needs, double distSq) {}
}
