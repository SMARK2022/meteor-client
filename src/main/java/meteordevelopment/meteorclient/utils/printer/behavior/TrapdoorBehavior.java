package meteordevelopment.meteorclient.utils.printer.behavior;

import meteordevelopment.meteorclient.utils.printer.*;

import net.minecraft.block.Blocks;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.state.property.Properties;

/**
 * TrapdoorBehavior - 活板门开关状态修正
 *
 * 当蓝图要求的活板门开关状态与世界不一致时，通过右键切换。
 * 仅处理可手动切换的活板门（排除铁活板门）。
 *
 * 属性处理：
 * - OPEN:        可通过右键修正（目标属性）
 * - FACING:      不可在位修改（不一致则需 break-and-replace）
 * - BLOCK_HALF:  不可在位修改
 * - WATERLOGGED: 由环境决定，忽略
 * - POWERED:     运行时属性，忽略
 */
public class TrapdoorBehavior implements PrinterBehavior {

    @Override
    public Group group() {
        return Group.INTERACTABLE;
    }

    @Override
    public Key key() {
        return Key.TRAPDOOR_OPEN;
    }

    @Override
    public boolean supports(PrinterTask task) {
        if (!(task.desiredState().getBlock() instanceof TrapdoorBlock)) return false;
        if (!(task.currentState().getBlock() instanceof TrapdoorBlock)) return false;

        // 铁活板门不可手动切换
        if (task.currentState().getBlock() == Blocks.IRON_TRAPDOOR) return false;

        // 朝向和半部必须一致
        if (!propertiesMatch(task, Properties.HORIZONTAL_FACING)) return false;
        if (!propertiesMatch(task, Properties.BLOCK_HALF)) return false;

        // OPEN 必须不一致
        return !propertiesMatch(task, Properties.OPEN);
    }

    @Override
    public boolean isSatisfied(PrinterTask task) {
        if (!(task.currentState().getBlock() instanceof TrapdoorBlock)) return false;
        return propertiesMatch(task, Properties.OPEN);
    }

    @Override
    public ActionPlan plan(PrinterTask task, MinecraftClient mc, boolean strict, boolean checkLos, double maxReach) {
        ActionPlan.Interaction interaction = InteractionPlanner.planSelfInteraction(
            mc, task.pos(), strict, checkLos, maxReach);
        if (interaction == null) return null;

        boolean desiredOpen = task.desiredState().get(Properties.OPEN);

        return new ActionPlan.UseBlock(
            task.pos(),
            task.desiredState(),
            interaction,
            null,
            ActionPlan.SneakPolicy.REQUIRE_NOT_SNEAK,
            ActionPlan.HandPolicy.PREFER_MAIN_NO_SWITCH,
            state -> state.contains(Properties.OPEN) && state.get(Properties.OPEN) != desiredOpen
        );
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> boolean propertiesMatch(PrinterTask task, net.minecraft.state.property.Property<T> prop) {
        if (!task.desiredState().contains(prop) || !task.currentState().contains(prop)) return false;
        return task.desiredState().get(prop).equals(task.currentState().get(prop));
    }
}
