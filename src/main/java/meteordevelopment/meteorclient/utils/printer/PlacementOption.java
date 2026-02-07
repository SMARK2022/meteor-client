package meteordevelopment.meteorclient.utils.printer;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * 放置选项 - 描述一次潜在的交互操作
 * 包含两种模式：
 * 1. NEIGHBOR: 依靠邻居方块（传统放置）
 * - direction: 邻居在目标的哪个方向
 * - getNeighborPos: 返回 offset(direction)
 * - getClickedFace: 返回 direction.getOpposite()
 * 2. SELF: 依靠自身方块（双层半砖、含水等）
 * - direction: 点击自身的哪个面
 * - getNeighborPos: 返回 targetPos (自己)
 * - getClickedFace: 返回 direction (直接点击该面)
 */
public record PlacementOption(Direction direction, boolean isSelf) {

    // 快捷工厂方法
    public static PlacementOption neighbor(Direction dir) {
        return new PlacementOption(dir, false);
    }

    public static PlacementOption self(Direction face) {
        return new PlacementOption(face, true);
    }

    /**
     * 获取实际要点击的那个方块的坐标
     */
    public BlockPos getInteractPos(BlockPos targetPos) {
        return isSelf ? targetPos : targetPos.offset(direction);
    }

    /**
     * 获取实际要点击的那个面
     */
    public Direction getClickedFace() {
        return isSelf ? direction : direction.getOpposite();
    }
}
