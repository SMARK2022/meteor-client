package meteordevelopment.meteorclient.utils.printer;

import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
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
     * @param ctx 放置上下文
     * @param opt 放置选项
     * @return 精确的点击坐标
     */
    Vec3d calculate(PlacementContext ctx, PlacementOption opt);

    /**
     * 组合计算器：如果第一个返回 null，则使用第二个
     *
     * @param fallback 备选计算器
     * @return 组合后的计算器
     */
    default HitVecCalculator orElse(HitVecCalculator fallback) {
        return (ctx, opt) -> {
            Vec3d result = this.calculate(ctx, opt);
            return result != null ? result : fallback.calculate(ctx, opt);
        };
    }

    // ==================== hitVec计算方法 ====================

    /**
     * 计算标准方块的hitVec（点击位置）
     * 点击邻居方块的指定面的中心
     */
    static Vec3d getHitVec(BlockPos neighborPos, Direction clickedSide) {
        return Vec3d.ofCenter(neighborPos).add(Vec3d.of(clickedSide.getVector()).multiply(0.5));
    }

    /**
     * 计算半砖的hitVec
     * 水平点击：通过Y偏移决定上/下半砖
     * 垂直点击：直接使用面中心
     */
    static Vec3d getHitVecForSlab(BlockPos neighborPos, Direction clickedSide, SlabType targetSlabType) {
        if (clickedSide.getAxis().isHorizontal()) {
            // 水平方向：调整Y坐标
            // TOP: Y + 0.25 (点击上半部分)
            // BOTTOM: Y - 0.25 (点击下半部分)
            double yOffset = (targetSlabType == SlabType.TOP) ? 0.25 : -0.25;
            return new Vec3d(
                    neighborPos.getX() + 0.5,
                    neighborPos.getY() + 0.5 + yOffset,
                    neighborPos.getZ() + 0.5).add(Vec3d.of(clickedSide.getVector()).multiply(0.5));
        }
        // 垂直方向：直接使用面中心
        return Vec3d.ofCenter(neighborPos).add(Vec3d.of(clickedSide.getVector()).multiply(0.5));
    }

    /**
     * 计算楼梯的点击位置
     * 核心修复：
     * - 如果要放倒置楼梯 (TOP)，必须点击侧面的上半部分 (Y + 0.25)。
     * - 如果要放正置楼梯 (BOTTOM)，点击侧面下半部分 (Y - 0.25)。
     */
    static Vec3d getHitVecForStairs(BlockPos neighborPos, Direction clickedSide,
            net.minecraft.block.enums.BlockHalf targetHalf) {
        // 如果点击的是水平侧面 (东南西北)
        if (clickedSide.getAxis().isHorizontal()) {
            // TOP(倒置) -> 向上偏移 0.25
            // BOTTOM(正置) -> 向下偏移 0.25
            double yOffset = (targetHalf == net.minecraft.block.enums.BlockHalf.TOP) ? 0.25 : -0.25;

            return new Vec3d(
                    neighborPos.getX() + 0.5,
                    neighborPos.getY() + 0.5 + yOffset, // <--- 关键修正
                    neighborPos.getZ() + 0.5).add(Vec3d.of(clickedSide.getVector()).multiply(0.5));
        }

        // 如果点击的是上下底面，直接点中心即可（Minecraft 机制保证：点底面必倒置，点顶面必正置）
        return Vec3d.ofCenter(neighborPos).add(Vec3d.of(clickedSide.getVector()).multiply(0.5));
    }
}