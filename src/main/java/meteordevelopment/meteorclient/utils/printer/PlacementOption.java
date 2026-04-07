package meteordevelopment.meteorclient.utils.printer;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * 放置选项 - 描述一次完整的交互方案
 * 包含两种模式：
 * 1. NEIGHBOR: 依靠邻居方块（传统放置）
 * - direction: 邻居在目标的哪个方向
 * - getInteractPos: 返回 offset(direction)
 * - getClickedFace: 返回 direction.getOpposite()
 * 2. SELF: 依靠自身方块（双层半砖、含水等）
 * - direction: 点击自身的哪个面
 * - getInteractPos: 返回 targetPos (自己)
 * - getClickedFace: 返回 direction (直接点击该面)
 *
 * actualTargetState: 【可选】当 resolve() 为了满足需求而修改了目标状态时（如 DOUBLE->BOTTOM/TOP），
 *                    此字段保存最终决定的状态，打印机使用此值而非原始 requiredState
 *
 * actualTargetPos: 【可选】当 resolve() 将放置位置重定向时（如双箱子合并阶段跳到 pairPos），
 *                  此字段保存最终决定的放置位置，调用者应使用此值而非原始 targetPos
 *
 * hitVec: 【核心】精确的点击坐标，在过滤前由 HitVecCalculator 计算
 *         - 这确保了过滤器（如视线检查）能基于实际点击位置进行判断
 *         - 避免了判定与执行的脱节问题
 */
public record PlacementOption(Direction direction, boolean isSelf, BlockState actualTargetState, Vec3d hitVec, BlockPos actualTargetPos) {

    // 基础工厂方法（HitVec 初始为 null，由 Resolver 填充）
    public static PlacementOption neighbor(Direction dir) {
        return new PlacementOption(dir, false, null, null, null);
    }

    public static PlacementOption neighbor(Direction dir, BlockState actualTarget) {
        return new PlacementOption(dir, false, actualTarget, null, null);
    }

    public static PlacementOption self(Direction face) {
        return new PlacementOption(face, true, null, null, null);
    }

    public static PlacementOption self(Direction face, BlockState actualTarget) {
        return new PlacementOption(face, true, actualTarget, null, null);
    }

    /**
     * 创建携带 HitVec 的新实例（流式处理）
     * 用于 Resolver 在过滤前注入计算好的点击坐标
     */
    public PlacementOption withHitVec(Vec3d vec) {
        return new PlacementOption(direction, isSelf, actualTargetState, vec, actualTargetPos);
    }

    /**
     * 创建携带实际状态的新实例（流式处理）
     * 用于处理需要状态转换的场景（如 DOUBLE -> BOTTOM/TOP）
     */
    public PlacementOption withState(BlockState state) {
        return new PlacementOption(direction, isSelf, state, hitVec, actualTargetPos);
    }

    /**
     * 创建携带实际放置位置的新实例（流式处理）
     * 用于处理位置重定向场景（如双箱子合并跳到 pairPos）
     */
    public PlacementOption withActualPos(BlockPos pos) {
        return new PlacementOption(direction, isSelf, actualTargetState, hitVec, pos);
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
