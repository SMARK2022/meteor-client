package meteordevelopment.meteorclient.utils.printer.behavior;

import meteordevelopment.meteorclient.utils.printer.*;

import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.state.property.Properties;

/**
 * DoorBehavior - 门开关状态修正
 *
 * 当蓝图要求的门开关状态与世界不一致时，通过右键切换。
 * 仅处理可手动切换的门（排除铁门）。
 *
 * 门是两格高方块，点击任一半都会同时切换。
 * 只为下半创建任务（canonical），避免上下两半重复规划和渲染。
 *
 * 属性处理：
 * - OPEN:        可通过右键修正（目标属性）
 * - FACING:      不可在位修改
 * - HINGE:       不可在位修改
 * - HALF:        由位置决定（上/下半）
 * - POWERED:     运行时属性，忽略
 */
public class DoorBehavior implements PrinterBehavior {

    @Override
    public boolean supports(PrinterTask task) {
        if (!(task.desiredState().getBlock() instanceof DoorBlock)) return false;
        if (!(task.currentState().getBlock() instanceof DoorBlock)) return false;

        // 铁门不可手动切换
        if (task.currentState().getBlock() == Blocks.IRON_DOOR) return false;

        // Canonical：只为下半创建任务，一次点击会同时切换上下两半
        if (task.desiredState().contains(Properties.DOUBLE_BLOCK_HALF)
            && task.desiredState().get(Properties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.LOWER) return false;

        // 不变量必须一致
        if (!propertiesMatch(task, Properties.HORIZONTAL_FACING)) return false;
        if (!propertiesMatch(task, Properties.DOOR_HINGE)) return false;

        // OPEN 必须不一致
        return !propertiesMatch(task, Properties.OPEN);
    }

    @Override
    public boolean isSatisfied(PrinterTask task) {
        if (!(task.currentState().getBlock() instanceof DoorBlock)) return false;
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
