package meteordevelopment.meteorclient.utils.printer.behavior;

import meteordevelopment.meteorclient.utils.printer.*;

import net.minecraft.block.BlockState;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.block.enums.WireConnection;
import net.minecraft.client.MinecraftClient;

/**
 * RedstoneDotCrossBehavior - 红石线点状/十字状切换行为
 *
 * 当蓝图要求的红石线形态（dot 或 cross）与世界中不一致时，
 * 通过右键交互切换。仅处理孤立红石线的点/十字切换。
 *
 * Minecraft 中，右键点击红石线可在以下两种状态间切换：
 * - Dot (点状): 四个方向连接均为 NONE，只有中心点
 * - Cross (十字): 四个方向连接均为 SIDE，向四方延伸
 *
 * 对于已有邻居时的连接状态，由游戏自动决定，右键无法修正。
 */
public class RedstoneDotCrossBehavior implements PrinterBehavior {

    @Override
    public Group group() {
        return Group.REDSTONE;
    }

    @Override
    public boolean supports(PrinterTask task) {
        if (!(task.desiredState().getBlock() instanceof RedstoneWireBlock)) return false;
        if (!(task.currentState().getBlock() instanceof RedstoneWireBlock)) return false;

        // 只处理 dot ↔ cross 的切换
        boolean desiredIsDot = isDot(task.desiredState());
        boolean desiredIsCross = isCross(task.desiredState());
        boolean currentIsDot = isDot(task.currentState());
        boolean currentIsCross = isCross(task.currentState());

        // 必须一边是 dot 一边是 cross（可切换的情况）
        return (desiredIsDot && currentIsCross) || (desiredIsCross && currentIsDot);
    }

    @Override
    public boolean isSatisfied(PrinterTask task) {
        if (!(task.currentState().getBlock() instanceof RedstoneWireBlock)) return false;
        // 连接形态一致即满足（忽略 POWER）
        return connectionsMatch(task.desiredState(), task.currentState());
    }

    @Override
    public ActionPlan plan(PrinterTask task, MinecraftClient mc, boolean strict, boolean checkLos, double maxReach) {
        ActionPlan.Interaction interaction = InteractionPlanner.planSelfInteraction(
            mc, task.pos(), strict, checkLos, maxReach);
        if (interaction == null) return null;

        boolean desiredIsDot = isDot(task.desiredState());

        return new ActionPlan.UseBlock(
            task.pos(),
            task.desiredState(),
            interaction,
            null,
            ActionPlan.SneakPolicy.REQUIRE_NOT_SNEAK,
            ActionPlan.HandPolicy.PREFER_MAIN_NO_SWITCH,
            state -> state.getBlock() instanceof RedstoneWireBlock
                && isDot(state) != desiredIsDot
        );
    }

    // ==================== 内部工具 ====================

    private static boolean isDot(BlockState state) {
        return getConnection(state, RedstoneWireBlock.WIRE_CONNECTION_NORTH) == WireConnection.NONE
            && getConnection(state, RedstoneWireBlock.WIRE_CONNECTION_SOUTH) == WireConnection.NONE
            && getConnection(state, RedstoneWireBlock.WIRE_CONNECTION_EAST) == WireConnection.NONE
            && getConnection(state, RedstoneWireBlock.WIRE_CONNECTION_WEST) == WireConnection.NONE;
    }

    private static boolean isCross(BlockState state) {
        return getConnection(state, RedstoneWireBlock.WIRE_CONNECTION_NORTH) == WireConnection.SIDE
            && getConnection(state, RedstoneWireBlock.WIRE_CONNECTION_SOUTH) == WireConnection.SIDE
            && getConnection(state, RedstoneWireBlock.WIRE_CONNECTION_EAST) == WireConnection.SIDE
            && getConnection(state, RedstoneWireBlock.WIRE_CONNECTION_WEST) == WireConnection.SIDE;
    }

    private static boolean connectionsMatch(BlockState a, BlockState b) {
        return getConnection(a, RedstoneWireBlock.WIRE_CONNECTION_NORTH) == getConnection(b, RedstoneWireBlock.WIRE_CONNECTION_NORTH)
            && getConnection(a, RedstoneWireBlock.WIRE_CONNECTION_SOUTH) == getConnection(b, RedstoneWireBlock.WIRE_CONNECTION_SOUTH)
            && getConnection(a, RedstoneWireBlock.WIRE_CONNECTION_EAST) == getConnection(b, RedstoneWireBlock.WIRE_CONNECTION_EAST)
            && getConnection(a, RedstoneWireBlock.WIRE_CONNECTION_WEST) == getConnection(b, RedstoneWireBlock.WIRE_CONNECTION_WEST);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> T getConnection(BlockState state, net.minecraft.state.property.Property<T> prop) {
        return state.contains(prop) ? state.get(prop) : null;
    }
}
