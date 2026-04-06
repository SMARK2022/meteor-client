package meteordevelopment.meteorclient.utils.printer.behavior;

import meteordevelopment.meteorclient.utils.printer.*;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;

import meteordevelopment.meteorclient.utils.player.InvUtils;

/**
 * WaterlogBehavior - 方块含水操作
 *
 * 当蓝图要求含水方块（WATERLOGGED=true）但当前未含水时，
 * 手持水桶对其右键交互使其变为含水状态。
 *
 * 属性处理：
 * - WATERLOGGED: 可通过水桶交互修正（目标属性）
 *
 * 注意：仅处理「加水」方向（将非含水变为含水），不处理排水。
 */
public class WaterlogBehavior implements PrinterBehavior {

    @Override
    public Group group() {
        return Group.FLUID;
    }

    @Override
    public boolean supports(PrinterTask task) {
        // 两者必须都含有 WATERLOGGED 属性
        if (!task.desiredState().contains(Properties.WATERLOGGED)) return false;
        if (!task.currentState().contains(Properties.WATERLOGGED)) return false;

        // 只处理需要加水的情况：desired=true, current=false
        if (!task.desiredState().get(Properties.WATERLOGGED)) return false;
        if (task.currentState().get(Properties.WATERLOGGED)) return false;

        // 方块类型必须一致（否则应由 BlockPlacementBehavior 处理）
        // inventory 就绪性留给 plan() 检查，避免缺桶时任务消失
        return task.desiredState().getBlock() == task.currentState().getBlock();
    }

    @Override
    public boolean isSatisfied(PrinterTask task) {
        if (!task.currentState().contains(Properties.WATERLOGGED)) return false;
        return task.currentState().get(Properties.WATERLOGGED);
    }

    @Override
    public ActionPlan plan(PrinterTask task, MinecraftClient mc, boolean strict, boolean checkLos, double maxReach) {
        if (!InvUtils.find(Items.WATER_BUCKET).found()) return null;

        // 对目标方块自身进行交互
        ActionPlan.Interaction interaction = InteractionPlanner.planSelfInteraction(
            mc, task.pos(), strict, checkLos, maxReach);
        if (interaction == null) return null;

        return new ActionPlan.UseItemOnBlock(
            task.pos(),
            task.desiredState(),
            interaction,
            Items.WATER_BUCKET,
            ActionPlan.SneakPolicy.REQUIRE_SNEAK, // 潜行避免触发方块交互（如箱子、按钮等）
            ActionPlan.HandPolicy.ANY_HAND_WITH_ITEM,
            state -> state.contains(Properties.WATERLOGGED)
                && !state.get(Properties.WATERLOGGED)
        );
    }
}
