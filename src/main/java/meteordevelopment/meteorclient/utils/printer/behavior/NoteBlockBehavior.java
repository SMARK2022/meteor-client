package meteordevelopment.meteorclient.utils.printer.behavior;

import meteordevelopment.meteorclient.utils.printer.*;

import net.minecraft.block.NoteBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.state.property.Properties;

/**
 * NoteBlockBehavior - 音符盒音高修正行为
 *
 * 当蓝图要求的音符盒音高与世界中不一致时，
 * 通过右键交互循环 NOTE 到目标值。
 *
 * 设计要点：
 * - 每 tick 只做一次 click，不维护内部计数器
 * - 依赖世界状态驱动：下一 tick 重新检查，不够再点
 * - NOTE 范围 0-24，每次右键 (current + 1) % 25
 * - 从当前到目标需要 (desired - current + 25) % 25 次右键
 * - stillNeedsAction 谓词防止 stale-plan
 *
 * 属性处理：
 * - NOTE:       可通过右键修正（目标属性，循环 0-24）
 * - INSTRUMENT: 由下方方块决定，运行时属性，忽略
 * - POWERED:    红石信号，运行时属性，忽略
 */
public class NoteBlockBehavior implements PrinterBehavior {

    @Override
    public Group group() {
        return Group.REDSTONE;
    }

    @Override
    public Key key() {
        return Key.NOTE_BLOCK_NOTE;
    }

    @Override
    public boolean supports(PrinterTask task) {
        if (!(task.desiredState().getBlock() instanceof NoteBlock)) return false;
        if (!(task.currentState().getBlock() instanceof NoteBlock)) return false;

        // NOTE 必须不一致
        return !BlockUtilHelper.propertiesMatch(task, Properties.NOTE);
    }

    @Override
    public boolean isSatisfied(PrinterTask task) {
        if (!(task.currentState().getBlock() instanceof NoteBlock)) return false;
        return BlockUtilHelper.propertiesMatch(task, Properties.NOTE);
    }

    @Override
    public ActionPlan plan(PrinterTask task, MinecraftClient mc, boolean strict, boolean checkLos, double maxReach) {
        ActionPlan.Interaction interaction = InteractionPlanner.planSelfInteraction(
            mc, task.pos(), strict, checkLos, maxReach);
        if (interaction == null) return null;

        int desiredNote = task.desiredState().get(Properties.NOTE);

        return new ActionPlan.UseBlock(
            task.pos(),
            task.desiredState(),
            interaction,
            null,
            ActionPlan.SneakPolicy.REQUIRE_NOT_SNEAK,
            ActionPlan.HandPolicy.PREFER_MAIN_NO_SWITCH,
            state -> state.contains(Properties.NOTE)
                && !state.get(Properties.NOTE).equals(desiredNote)
        );
    }
}
