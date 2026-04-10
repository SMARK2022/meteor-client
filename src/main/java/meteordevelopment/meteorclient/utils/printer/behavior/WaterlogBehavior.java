package meteordevelopment.meteorclient.utils.printer.behavior;

import meteordevelopment.meteorclient.utils.printer.*;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;

import meteordevelopment.meteorclient.utils.player.InvUtils;

/**
 * WaterlogBehavior — 方块含水操作
 *
 * <p>当蓝图要求含水方块（WATERLOGGED=true）但当前未含水时，
 * 手持水桶对其自身 interactBlock 使其变为含水状态。
 *
 * <h2>Sneak 策略</h2>
 * <p>大多数 waterloggable 方块（半砖/楼梯/墙/栅栏）不需要潜行；
 * 只有 <b>有 GUI/onUse 交互</b> 的方块（如活板门、栅栏门）需要潜行绕过交互。
 * 使用 {@link BlockUtilHelper#determineSneakPolicy} 按方块逐一判断，
 * 避免对所有方块强制潜行导致多余的准备 tick。
 *
 * <p>注意：仅处理「加水」方向（将非含水变为含水），不处理排水。
 */
public class WaterlogBehavior implements PrinterBehavior {

    @Override
    public Group group() {
        return Group.FLUID;
    }

    @Override
    public Key key() {
        return Key.WATERLOG;
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

        // 按方块类型动态判断：活板门/栅栏门等交互方块需要 sneak，普通半砖/楼梯不需要
        ActionPlan.SneakPolicy sneak = BlockUtilHelper.determineSneakPolicy(task.currentState());

        return new ActionPlan.UseItemOnBlock(
            task.pos(),
            task.desiredState(),
            interaction,
            Items.WATER_BUCKET,
            sneak,
            ActionPlan.HandPolicy.ANY_HAND_WITH_ITEM,
            state -> state.contains(Properties.WATERLOGGED)
                && !state.get(Properties.WATERLOGGED)
        );
    }
}
