package meteordevelopment.meteorclient.utils.printer.behavior;

import meteordevelopment.meteorclient.utils.printer.*;

import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.state.property.Properties;

/**
 * DoorBehavior - 门开关状态修正
 *
 * 当蓝图要求的门开关状态与世界不一致时，通过右键切换。
 * 仅处理可手动切换的门（排除铁门）。
 * 门是两格高方块，点击任一半都会同时切换。
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
    public Group group() {
        return Group.INTERACTABLE;
    }

    @Override
    public Key key() {
        return Key.DOOR_OPEN;
    }

    @Override
    public boolean supports(PrinterTask task) {
        if (!(task.desiredState().getBlock() instanceof DoorBlock)) return false;
        if (!(task.currentState().getBlock() instanceof DoorBlock)) return false;

        // 铁门不可手动切换
        if (task.currentState().getBlock() == Blocks.IRON_DOOR) return false;

        // 不变量必须一致
        if (!BlockUtilHelper.propertiesMatch(task, Properties.HORIZONTAL_FACING)) return false;
        if (!BlockUtilHelper.propertiesMatch(task, Properties.DOOR_HINGE)) return false;

        // OPEN 必须不一致
        return !BlockUtilHelper.propertiesMatch(task, Properties.OPEN);
    }

    @Override
    public boolean isSatisfied(PrinterTask task) {
        if (!(task.currentState().getBlock() instanceof DoorBlock)) return false;
        return BlockUtilHelper.propertiesMatch(task, Properties.OPEN);
    }

    @Override
    public ActionPlan plan(PrinterTask task, MinecraftClient mc, boolean strict, boolean checkLos, double maxReach) {
        // 门是两格高方块，点击上半或下半都会同时切换。
        // task 已 canonical 到 lower half，但交互表面应包括上下两格。
        // 先尝试下半（canonical 位置），失败再尝试上半。
        net.minecraft.util.math.BlockPos lowerPos = task.pos();
        net.minecraft.util.math.BlockPos upperPos = lowerPos.up();

        ActionPlan.Interaction interaction = InteractionPlanner.planSelfInteraction(
            mc, lowerPos, strict, checkLos, maxReach);
        net.minecraft.util.math.BlockPos interactPos = lowerPos;

        if (interaction == null) {
            interaction = InteractionPlanner.planSelfInteraction(
                mc, upperPos, strict, checkLos, maxReach);
            interactPos = upperPos;
        }
        if (interaction == null) return null;

        boolean desiredOpen = task.desiredState().get(Properties.OPEN);

        return new ActionPlan.UseBlock(
            interactPos,
            task.desiredState(),
            interaction,
            null,
            ActionPlan.SneakPolicy.REQUIRE_NOT_SNEAK,
            ActionPlan.HandPolicy.PREFER_MAIN_NO_SWITCH,
            state -> state.contains(Properties.OPEN) && state.get(Properties.OPEN) != desiredOpen
        );
    }

}
