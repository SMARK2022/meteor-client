package meteordevelopment.meteorclient.utils.printer.behavior;

import meteordevelopment.meteorclient.utils.printer.*;

import net.minecraft.block.LeverBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.state.property.Properties;

/**
 * LeverBehavior - 拉杆开关状态修正
 *
 * 当蓝图要求的拉杆供电状态与世界不一致时，通过右键切换。
 * 拉杆是稳定态开关，适合作为构建目标（区别于按钮的瞬时态）。
 *
 * 属性处理：
 * - POWERED:             可通过右键修正（目标属性，单次 toggle）
 * - BLOCK_FACE:          不可在位修改（FLOOR/WALL/CEILING，放置时决定）
 * - HORIZONTAL_FACING:   不可在位修改（放置时决定）
 */
public class LeverBehavior implements PrinterBehavior {

    @Override
    public Group group() {
        return Group.INTERACTABLE;
    }

    @Override
    public Key key() {
        return Key.LEVER_POWERED;
    }

    @Override
    public boolean supports(PrinterTask task) {
        if (!(task.desiredState().getBlock() instanceof LeverBlock)) return false;
        if (!(task.currentState().getBlock() instanceof LeverBlock)) return false;

        // 不变量必须一致：附着面和朝向
        if (!BlockUtilHelper.propertiesMatch(task, Properties.BLOCK_FACE)) return false;
        if (!BlockUtilHelper.propertiesMatch(task, Properties.HORIZONTAL_FACING)) return false;

        // POWERED 必须不一致
        return !BlockUtilHelper.propertiesMatch(task, Properties.POWERED);
    }

    @Override
    public boolean isSatisfied(PrinterTask task) {
        if (!(task.currentState().getBlock() instanceof LeverBlock)) return false;
        return BlockUtilHelper.propertiesMatch(task, Properties.POWERED);
    }

    @Override
    public ActionPlan plan(PrinterTask task, MinecraftClient mc, boolean strict, boolean checkLos, double maxReach) {
        ActionPlan.Interaction interaction = InteractionPlanner.planSelfInteraction(
            mc, task.pos(), strict, checkLos, maxReach);
        if (interaction == null) return null;

        boolean desiredPowered = task.desiredState().get(Properties.POWERED);

        return new ActionPlan.UseBlock(
            task.pos(),
            task.desiredState(),
            interaction,
            null,
            ActionPlan.SneakPolicy.REQUIRE_NOT_SNEAK,
            ActionPlan.HandPolicy.PREFER_MAIN_NO_SWITCH,
            state -> state.contains(Properties.POWERED) && state.get(Properties.POWERED) != desiredPowered
        );
    }
}
