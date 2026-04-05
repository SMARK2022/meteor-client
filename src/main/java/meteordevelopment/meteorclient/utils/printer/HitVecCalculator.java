package meteordevelopment.meteorclient.utils.printer;

import net.minecraft.block.BlockState;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

    // ==================== 面片抽取与区域选点 ====================

    /**
     * 从 VoxelShape 抽取指定面上所有外露的面片。
     * 遍历 shape 的所有子 box，收集面坐标贴合外边界的矩形区域。
     *
     * 例如：上半砖的 EAST 面，只会产出 v∈[0.5,1] 的 patch，
     * 而不是整个 [0,1] 区间。
     */
    static List<FacePatch> getFacePatches(BlockView world, BlockPos pos, Direction face) {
        BlockState state = world.getBlockState(pos);
        VoxelShape shape = state.getOutlineShape(world, pos);
        if (shape.isEmpty()) return List.of();

        // 确定该面的外边界坐标
        double outerCoord = switch (face) {
            case EAST  -> shape.getMax(Direction.Axis.X);
            case WEST  -> shape.getMin(Direction.Axis.X);
            case UP    -> shape.getMax(Direction.Axis.Y);
            case DOWN  -> shape.getMin(Direction.Axis.Y);
            case SOUTH -> shape.getMax(Direction.Axis.Z);
            case NORTH -> shape.getMin(Direction.Axis.Z);
        };

        final double TOL = 0.001;
        List<FacePatch> patches = new ArrayList<>();

        for (Box box : shape.getBoundingBoxes()) {
            double fc, minU, maxU, minV, maxV;
            switch (face) {
                case EAST:  fc = box.maxX; minU = box.minZ; maxU = box.maxZ; minV = box.minY; maxV = box.maxY; break;
                case WEST:  fc = box.minX; minU = box.minZ; maxU = box.maxZ; minV = box.minY; maxV = box.maxY; break;
                case UP:    fc = box.maxY; minU = box.minX; maxU = box.maxX; minV = box.minZ; maxV = box.maxZ; break;
                case DOWN:  fc = box.minY; minU = box.minX; maxU = box.maxX; minV = box.minZ; maxV = box.maxZ; break;
                case SOUTH: fc = box.maxZ; minU = box.minX; maxU = box.maxX; minV = box.minY; maxV = box.maxY; break;
                case NORTH: fc = box.minZ; minU = box.minX; maxU = box.maxX; minV = box.minY; maxV = box.maxY; break;
                default: continue;
            }
            if (Math.abs(fc - outerCoord) <= TOL) {
                patches.add(new FacePatch(face, outerCoord, minU, maxU, minV, maxV));
            }
        }
        return patches;
    }

    /**
     * 在面片内生成 5 个稳定的确定性采样 (u, v) 坐标。
     * 每个点均做 1/64 内缩，避免落在边缘/角点。
     *
     * 采样布局：
     * 1. preferred-clamped（偏好点，尽量靠近期望高度）
     * 2. center（面片几何中心）
     * 3. u=25%, v=preferred（左偏）
     * 4. u=75%, v=preferred（右偏）
     * 5. u=center, v=alternative（对侧高度）
     */
    private static double[][] sampleUV(FacePatch patch, double preferredU, double preferredV) {
        final double INSET = 1.0 / 64.0;
        double sMinU = patch.minU() + INSET, sMaxU = patch.maxU() - INSET;
        double sMinV = patch.minV() + INSET, sMaxV = patch.maxV() - INSET;

        // 极窄 patch 退化为中心点
        if (sMinU > sMaxU) { sMinU = sMaxU = (patch.minU() + patch.maxU()) / 2; }
        if (sMinV > sMaxV) { sMinV = sMaxV = (patch.minV() + patch.maxV()) / 2; }

        double cU = Math.max(sMinU, Math.min(preferredU, sMaxU));
        double cV = Math.max(sMinV, Math.min(preferredV, sMaxV));
        double midU = (sMinU + sMaxU) / 2;
        double midV = (sMinV + sMaxV) / 2;
        double u25 = sMinU + (sMaxU - sMinU) * 0.25;
        double u75 = sMinU + (sMaxU - sMinU) * 0.75;
        double altV = (cV > midV)
            ? sMinV + (sMaxV - sMinV) * 0.25
            : sMinV + (sMaxV - sMinV) * 0.75;

        return new double[][] {
            { cU, cV },       // 1. preferred
            { midU, midV },   // 2. center
            { u25, cV },      // 3. left + preferred height
            { u75, cV },      // 4. right + preferred height
            { midU, altV }    // 5. center + alt height
        };
    }

    /**
     * 在指定面的所有面片中搜索最优可行点击点。
     *
     * 对每个 patch 的 5 个采样点逐一检查：
     * - Reach 合法
     * - NCP 方向合法（strict 模式）
     * - LOS 合法（如果启用）
     * 然后按「离边缘越远 + 越接近偏好高度」打分，取最优。
     *
     * 这是"初始点失败后 refinement"的核心方法。
     *
     * @param ctx             放置上下文
     * @param opt             当前候选（提供交互位置和面信息）
     * @param preferredHeight 期望的 Y 偏移（侧面有效，UP/DOWN 自动用 0.5）
     * @param checkLos        是否执行视线检测
     * @return 最优合法点，如果没有合法点则返回 null
     */
    static Vec3d findBestPointOnFace(PlacementContext ctx, PlacementOption opt,
                                     double preferredHeight, boolean checkLos) {
        BlockPos interactPos = opt.getInteractPos(ctx.targetPos());
        Direction face = opt.getClickedFace();

        List<FacePatch> patches = getFacePatches(ctx.world(), interactPos, face);
        if (patches.isEmpty()) return null;

        Vec3d eyePos = ctx.eyePos();
        double maxReach = ctx.maxReach();
        boolean strict = ctx.strict();
        BlockPos targetPos = ctx.targetPos();

        // 侧面用 preferredHeight 作为 V 轴偏好；垂直面（UP/DOWN）V 映射到 Z，无高度偏好
        double prefV = face.getAxis().isVertical() ? 0.5 : preferredHeight;
        double prefU = 0.5;

        Vec3d bestPoint = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (FacePatch patch : patches) {
            double[][] samples = sampleUV(patch, prefU, prefV);
            for (double[] uv : samples) {
                double u = uv[0], v = uv[1];
                Vec3d point = patch.toWorld(interactPos, u, v);

                // Reach
                if (eyePos.distanceTo(point) > maxReach + 0.1) continue;

                // NCP
                if (strict) {
                    Set<Direction> validDirs = BlockUtilHelper.getPlaceDirectionsNCP(eyePos, point);
                    if (!validDirs.contains(face)) continue;
                }

                // LOS
                if (checkLos) {
                    if (!BlockUtilHelper.canSeeFacePoint(
                            interactPos, face, point,
                            ctx.world(), ctx.player(), targetPos)) continue;
                }

                // 打分：居中越好 (+)，偏离偏好越差 (-)
                double margin = patch.centerMargin(u, v);
                double distToPref = Math.abs(v - prefV);
                double score = 4.0 * margin - 2.0 * distToPref;

                if (score > bestScore) {
                    bestScore = score;
                    bestPoint = point;
                }
            }
        }
        return bestPoint;
    }

    /**
     * 根据方块上下文推断期望的 Y 偏移高度。
     * - TOP 半砖/楼梯/活板门 → 0.8
     * - BOTTOM 半砖/楼梯/活板门 → 0.2
     * - 其他 → 0.5（中心）
     */
    static double getPreferredHeight(PlacementContext ctx) {
        if (ctx.hasProperty(SlabBlock.TYPE)) {
            SlabType type = ctx.getProperty(SlabBlock.TYPE);
            if (type == SlabType.TOP) return 0.8;
            if (type == SlabType.BOTTOM) return 0.2;
        }
        if (ctx.hasProperty(StairsBlock.HALF)) {
            return ctx.getProperty(StairsBlock.HALF) == BlockHalf.TOP ? 0.8 : 0.2;
        }
        if (ctx.hasProperty(TrapdoorBlock.HALF)) {
            return ctx.getProperty(TrapdoorBlock.HALF) == BlockHalf.TOP ? 0.8 : 0.2;
        }
        return 0.5;
    }

    // ==================== FacePatch 内部记录 ====================

    /**
     * FacePatch - 方块外露面片
     *
     * 描述 VoxelShape 某个面上的一个二维矩形可点击区域。
     * 由 {@link #getFacePatches} 从 outline shape 的子 box 中抽取。
     *
     * 坐标系约定（方块相对坐标 0~1）：
     * - EAST/WEST:   fixedCoord=x, u=z, v=y
     * - UP/DOWN:     fixedCoord=y, u=x, v=z
     * - NORTH/SOUTH: fixedCoord=z, u=x, v=y
     */
    record FacePatch(
        Direction face,
        double fixedCoord,
        double minU, double maxU,
        double minV, double maxV
    ) {
        private static final double FACE_EPS = 1.0e-3;

        /**
         * 将 patch 内的 (u, v) 坐标转换为世界坐标。
         * 自动沿法线方向内缩 FACE_EPS，避免精确边界判定抖动。
         */
        public Vec3d toWorld(BlockPos pos, double u, double v) {
            double fc = switch (face) {
                case EAST, UP, SOUTH -> fixedCoord - FACE_EPS;
                case WEST, DOWN, NORTH -> fixedCoord + FACE_EPS;
            };
            double bx = pos.getX(), by = pos.getY(), bz = pos.getZ();
            return switch (face) {
                case EAST, WEST   -> new Vec3d(bx + fc, by + v, bz + u);
                case UP, DOWN     -> new Vec3d(bx + u, by + fc, bz + v);
                case NORTH, SOUTH -> new Vec3d(bx + u, by + v, bz + fc);
            };
        }

        /**
         * 计算 (u, v) 距 patch 边缘的最小归一化距离。
         * 返回 0（在边缘）到 1（在正中心）。
         */
        public double centerMargin(double u, double v) {
            double uRange = maxU - minU;
            double vRange = maxV - minV;
            if (uRange <= 0 && vRange <= 0) return 0;
            double uM = uRange > 0 ? Math.min(u - minU, maxU - u) / (uRange / 2.0) : 1.0;
            double vM = vRange > 0 ? Math.min(v - minV, maxV - v) / (vRange / 2.0) : 1.0;
            return Math.min(Math.max(uM, 0), Math.max(vM, 0));
        }
    }

}
