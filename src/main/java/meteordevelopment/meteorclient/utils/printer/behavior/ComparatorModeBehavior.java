package meteordevelopment.meteorclient.utils.printer.behavior;

import meteordevelopment.meteorclient.utils.printer.*;

import net.minecraft.block.ComparatorBlock;
import net.minecraft.block.enums.ComparatorMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.state.property.Properties;

/**
 * ComparatorModeBehavior - 比较器模式修正行为
 *
 * 当蓝图要求的比较器模式（比较/减法）与世界中不一致时，
 * 通过右键交互切换模式。
 *
 * 属性处理：
 * - COMPARATOR_MODE: 可通过右键修正（目标属性）
 * - HORIZONTAL_FACING: 不可在位修改（必须方向一致才处理）
 * - POWERED: 运行时属性，忽略
 */
public class ComparatorModeBehavior implements PrinterBehavior {

    @Override
    public boolean supports(PrinterTask task) {
        if (!(task.desiredState().getBlock() instanceof ComparatorBlock)) return false;
        if (!(task.currentState().getBlock() instanceof ComparatorBlock)) return false;

        // 朝向必须一致
        if (!task.desiredState().contains(Properties.HORIZONTAL_FACING)
            || !task.currentState().contains(Properties.HORIZONTAL_FACING)) return false;
        if (task.desiredState().get(Properties.HORIZONTAL_FACING)
            != task.currentState().get(Properties.HORIZONTAL_FACING)) return false;

        // 模式必须不一致
        if (!task.desiredState().contains(Properties.COMPARATOR_MODE)
            || !task.currentState().contains(Properties.COMPARATOR_MODE)) return false;
        return task.desiredState().get(Properties.COMPARATOR_MODE)
            != task.currentState().get(Properties.COMPARATOR_MODE);
    }

    @Override
    public boolean isSatisfied(PrinterTask task) {
        if (!(task.currentState().getBlock() instanceof ComparatorBlock)) return false;
        if (!task.desiredState().contains(Properties.COMPARATOR_MODE)
            || !task.currentState().contains(Properties.COMPARATOR_MODE)) return false;
        return task.desiredState().get(Properties.COMPARATOR_MODE)
            == task.currentState().get(Properties.COMPARATOR_MODE);
    }

    @Override
    public ActionPlan plan(PrinterTask task, MinecraftClient mc, boolean strict, boolean checkLos, double maxReach) {
        ActionPlan.Interaction interaction = InteractionPlanner.planSelfInteraction(
            mc, task.pos(), strict, checkLos, maxReach);
        if (interaction == null) return null;

        ComparatorMode desiredMode = task.desiredState().get(Properties.COMPARATOR_MODE);

        return new ActionPlan.UseBlock(
            task.pos(),
            task.desiredState(),
            interaction,
            null,
            ActionPlan.SneakPolicy.REQUIRE_NOT_SNEAK,
            ActionPlan.HandPolicy.PREFER_MAIN_NO_SWITCH,
            state -> state.contains(Properties.COMPARATOR_MODE)
                && state.get(Properties.COMPARATOR_MODE) != desiredMode
        );
    }
}
