package meteordevelopment.meteorclient.utils.printer.behavior;

import meteordevelopment.meteorclient.utils.printer.*;

import net.minecraft.block.RepeaterBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.state.property.Properties;

/**
 * RepeaterDelayBehavior - 中继器延迟修正行为
 *
 * 当蓝图要求的 repeater delay 与世界中不一致时，
 * 通过右键交互循环 delay 到目标值。
 *
 * 设计要点：
 * - 每 tick 只做一次 click，不维护内部计数器
 * - 依赖世界状态驱动：下一 tick 重新检查，不够再点
 * - stillNeedsAction 谓词防止 stale-plan（执行前再次检查 delay 是否仍需修正）
 *
 * 属性处理：
 * - DELAY:   可通过右键修正（目标属性）
 * - FACING:  不可在位修改（必须方向一致才处理）
 * - POWERED: 运行时属性，忽略
 * - LOCKED:  运行时属性，忽略
 */
public class RepeaterDelayBehavior implements PrinterBehavior {

    @Override
    public Group group() {
        return Group.REDSTONE;
    }

    @Override
    public Key key() {
        return Key.REPEATER_DELAY;
    }

    @Override
    public boolean supports(PrinterTask task) {
        if (!(task.desiredState().getBlock() instanceof RepeaterBlock)) return false;
        if (!(task.currentState().getBlock() instanceof RepeaterBlock)) return false;

        // 朝向必须一致
        if (!BlockUtilHelper.propertiesMatch(task, Properties.HORIZONTAL_FACING)) return false;

        // DELAY 必须不一致
        return !BlockUtilHelper.propertiesMatch(task, Properties.DELAY);
    }

    @Override
    public boolean isSatisfied(PrinterTask task) {
        if (!(task.currentState().getBlock() instanceof RepeaterBlock)) return false;
        return BlockUtilHelper.propertiesMatch(task, Properties.DELAY);
    }

    @Override
    public ActionPlan plan(PrinterTask task, MinecraftClient mc, boolean strict, boolean checkLos, double maxReach) {
        ActionPlan.Interaction interaction = InteractionPlanner.planSelfInteraction(
            mc, task.pos(), strict, checkLos, maxReach);
        if (interaction == null) return null;

        int desiredDelay = task.desiredState().get(Properties.DELAY);

        return new ActionPlan.UseBlock(
            task.pos(),
            task.desiredState(),
            interaction,
            null,
            ActionPlan.SneakPolicy.REQUIRE_NOT_SNEAK,
            ActionPlan.HandPolicy.PREFER_MAIN_NO_SWITCH,
            state -> state.contains(Properties.DELAY)
                && !state.get(Properties.DELAY).equals(desiredDelay)
        );
    }
}
