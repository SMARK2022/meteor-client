package meteordevelopment.meteorclient.utils.printer;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * HitVecCalculator - 点击位置计算器接口
 *
 * 定义了计算方块放置时点击位置（hitVec）的策略。
 * 不同类型的方块需要不同的点击位置来确保正确的朝向/状态。
 *
 * 设计理念：
 * - 精确性：hitVec 决定了方块的最终朝向（尤其是半砖、楼梯）
 * - 灵活性：不同方块类型可以有不同的计算策略
 * - 可复用：通用策略可以被多种方块类型共享
 *
 * 核心概念：
 * - neighborPos: 我们点击的邻居方块位置
 * - clickedSide: 我们点击的邻居方块的哪个面
 * - hitVec: 精确的点击坐标（世界坐标系）
 */
@FunctionalInterface
public interface HitVecCalculator {

    /**
     * 计算点击位置
     *
     * @param ctx         放置上下文
     * @param neighborPos 邻居方块位置（被点击的方块）
     * @param clickedSide 点击的面（邻居方块的哪个面）
     * @return 精确的点击坐标
     */
    Vec3d calculate(PlacementContext ctx, BlockPos neighborPos, Direction clickedSide);

    /**
     * 组合计算器：如果第一个返回 null，则使用第二个
     *
     * @param fallback 备选计算器
     * @return 组合后的计算器
     */
    default HitVecCalculator orElse(HitVecCalculator fallback) {
        return (ctx, neighborPos, clickedSide) -> {
            Vec3d result = this.calculate(ctx, neighborPos, clickedSide);
            return result != null ? result : fallback.calculate(ctx, neighborPos, clickedSide);
        };
    }
}
