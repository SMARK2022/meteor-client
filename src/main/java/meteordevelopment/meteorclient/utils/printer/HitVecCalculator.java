package meteordevelopment.meteorclient.utils.printer;

import net.minecraft.block.BlockState;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;

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
 * - [核心升级] 基于 VoxelShape 的精确碰撞箱计算，避免点击空气
 *
 * 核心概念：
 * - neighborPos: 我们点击的邻居方块位置
 * - clickedSide: 我们点击的邻居方块的哪个面
 * - hitVec: 精确的点击坐标（世界坐标系）
 * - VoxelShape: 方块的真实碰撞箱或轮廓形状
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

    // ==================== 核心辅助方法：基于形状计算 ====================

    /**
     * 获取邻居方块指定面的中心点（默认期望高度 0.5）
     */
    static Vec3d getShapeHitVec(BlockView world, BlockPos pos, Direction side) {
        // 默认期望点击方块中心高度 0.5
        return getExtremeHitVec(world, pos, side, 0.5);
    }

    /**
     * [核心升级] 获取邻居方块指定面上的最佳点击点
     * 根据【期望的相对高度】计算点击位置，并将其 Clamp（限制）在方块实际碰撞箱的有效范围内。
     *
     * 应用场景：
     * - 放置 TOP 半砖：desiredYOffset = 0.8（期望点在上面）
     * - 放置 BOTTOM 半砖：desiredYOffset = 0.2（期望点在下面）
     * - 通用中心点击：desiredYOffset = 0.5（中间）
     *
     * 逻辑举例：
     * - 目标 TOP (0.8)，邻居是全方块 (0~1.0) -> 点击点 Y = 0.8（完美）
     * - 目标 TOP (0.8)，邻居是半砖 (0~0.5) -> 点击点 Y ≈ 0.49（被限制在最高点，随后会被过滤器拒绝）
     * - 目标 TOP (0.8)，邻居是栅栏 (0~1.5) -> 点击点 Y = 0.8（完美）
     *
     * @param world 世界访问视图
     * @param pos 邻居方块坐标
     * @param side 要点击的面（UP/DOWN/NORTH/SOUTH/EAST/WEST）
     * @param desiredYOffset 期望的相对高度偏移（0.0 ~ 1.0+）。例如 0.8 代表期望点击在 y+0.8 的高度。
     * @return 精确的、被限制在碰撞箱内的点击坐标
     */
    static Vec3d getExtremeHitVec(BlockView world, BlockPos pos, Direction side, double desiredYOffset) {
        BlockState state = world.getBlockState(pos);
        VoxelShape shape = state.getOutlineShape(world, pos);

        if (shape.isEmpty()) {
            return Vec3d.ofCenter(pos).add(Vec3d.of(side.getVector()).multiply(0.5));
        }

        // 获取形状在各轴上的边界（相对坐标 0.0 ~ 1.0+）
        double minX = shape.getMin(Direction.Axis.X);
        double maxX = shape.getMax(Direction.Axis.X);
        double minY = shape.getMin(Direction.Axis.Y);
        double maxY = shape.getMax(Direction.Axis.Y);
        double minZ = shape.getMin(Direction.Axis.Z);
        double maxZ = shape.getMax(Direction.Axis.Z);

        // [边界收缩] 保留微小余量，防止点击点恰好在边缘导致浮点数判定失效
        final double MARGIN = 0.001;

        // 计算实际可点击的 Y 范围（Clamp Range）
        double clampedMinY = minY + MARGIN;
        double clampedMaxY = maxY - MARGIN;

        // 处理极扁方块（如地毯 0.0625），防止 min > max
        if (clampedMinY > clampedMaxY) {
            double mid = (minY + maxY) / 2.0;
            clampedMinY = mid - 0.001;
            clampedMaxY = mid + 0.001;
        }

        // [核心逻辑] 将期望高度（desiredYOffset）限制在有效范围内
        // 如果邻居方块太矮，会自动吸附到邻居的最高点
        double finalRelY = Math.min(Math.max(desiredYOffset, clampedMinY), clampedMaxY);

        double x = pos.getX();
        double y = pos.getY();
        double z = pos.getZ();

        // [Fix] 面坐标轻微内缩，避免精确边界上的面判定抖动
        final double FACE_EPS = 1.0e-3;
        double cx = x + (minX + maxX) / 2.0;
        double cz = z + (minZ + maxZ) / 2.0;

        // 根据点击的面计算点击点
        switch (side) {
            case UP -> {
                // 点击顶面：固定在 Shape 最高点（轻微内缩）
                return new Vec3d(cx, y + Math.max(minY, maxY - FACE_EPS), cz);
            }
            case DOWN -> {
                // 点击底面：固定在 Shape 最低点（轻微内缩）
                return new Vec3d(cx, y + Math.min(maxY, minY + FACE_EPS), cz);
            }
            case NORTH -> {
                // 点击北面（Z 轴负向）：使用计算出的 finalRelY
                return new Vec3d(cx, y + finalRelY, z + Math.min(maxZ, minZ + FACE_EPS));
            }
            case SOUTH -> {
                // 点击南面（Z 轴正向）
                return new Vec3d(cx, y + finalRelY, z + Math.max(minZ, maxZ - FACE_EPS));
            }
            case WEST -> {
                // 点击西面（X 轴负向）
                return new Vec3d(x + Math.min(maxX, minX + FACE_EPS), y + finalRelY, cz);
            }
            case EAST -> {
                // 点击东面（X 轴正向）
                return new Vec3d(x + Math.max(minX, maxX - FACE_EPS), y + finalRelY, cz);
            }
        }
        return Vec3d.ofCenter(pos);
    }

}
