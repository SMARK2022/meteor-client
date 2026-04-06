package meteordevelopment.meteorclient.utils.printer.behavior;

import meteordevelopment.meteorclient.utils.printer.*;

import net.minecraft.block.DaylightDetectorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.state.property.Properties;

/**
 * DaylightDetectorBehavior - 阳光探测器反转状态修正
 *
 * 当蓝图要求的阳光探测器反转状态与世界不一致时，通过右键切换。
 *
 * 属性处理：
 * - INVERTED: 可通过右键修正（目标属性）
 * - POWER:    运行时属性，忽略
 */
public class DaylightDetectorBehavior implements PrinterBehavior {

    @Override
    public Group group() {
        return Group.INTERACTABLE;
    }

    @Override
    public Key key() {
        return Key.DAYLIGHT_DETECTOR;
    }

    @Override
    public boolean supports(PrinterTask task) {
        if (!(task.desiredState().getBlock() instanceof DaylightDetectorBlock)) return false;
        if (!(task.currentState().getBlock() instanceof DaylightDetectorBlock)) return false;

        // INVERTED 必须不一致
        return !BlockUtilHelper.propertiesMatch(task, Properties.INVERTED);
    }

    @Override
    public boolean isSatisfied(PrinterTask task) {
        if (!(task.currentState().getBlock() instanceof DaylightDetectorBlock)) return false;
        return BlockUtilHelper.propertiesMatch(task, Properties.INVERTED);
    }

    @Override
    public ActionPlan plan(PrinterTask task, MinecraftClient mc, boolean strict, boolean checkLos, double maxReach) {
        ActionPlan.Interaction interaction = InteractionPlanner.planSelfInteraction(
            mc, task.pos(), strict, checkLos, maxReach);
        if (interaction == null) return null;

        boolean desiredInverted = task.desiredState().get(Properties.INVERTED);

        return new ActionPlan.UseBlock(
            task.pos(),
            task.desiredState(),
            interaction,
            null,
            ActionPlan.SneakPolicy.REQUIRE_NOT_SNEAK,
            ActionPlan.HandPolicy.PREFER_MAIN_NO_SWITCH,
            state -> state.contains(Properties.INVERTED)
                && state.get(Properties.INVERTED) != desiredInverted
        );
    }
}
