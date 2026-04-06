package meteordevelopment.meteorclient.utils.printer.behavior;

import meteordevelopment.meteorclient.utils.printer.*;

import net.minecraft.block.FenceGateBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.state.property.Properties;

/**
 * FenceGateBehavior - 栅栏门开关状态修正
 *
 * 当蓝图要求的栅栏门开关状态与世界不一致时，通过右键切换。
 * 所有栅栏门均可手动切换。
 *
 * 属性处理：
 * - OPEN:        可通过右键修正（目标属性）
 * - FACING:      不可在位修改
 * - IN_WALL:     由环境决定（相邻是否为墙），忽略
 * - POWERED:     运行时属性，忽略
 */
public class FenceGateBehavior implements PrinterBehavior {

    @Override
    public Group group() {
        return Group.INTERACTABLE;
    }

    @Override
    public Key key() {
        return Key.FENCE_GATE_OPEN;
    }

    @Override
    public boolean supports(PrinterTask task) {
        if (!(task.desiredState().getBlock() instanceof FenceGateBlock)) return false;
        if (!(task.currentState().getBlock() instanceof FenceGateBlock)) return false;

        // 朝向必须一致
        if (!propertiesMatch(task, Properties.HORIZONTAL_FACING)) return false;

        // OPEN 必须不一致
        return !propertiesMatch(task, Properties.OPEN);
    }

    @Override
    public boolean isSatisfied(PrinterTask task) {
        if (!(task.currentState().getBlock() instanceof FenceGateBlock)) return false;
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
