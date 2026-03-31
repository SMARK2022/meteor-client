package meteordevelopment.meteorclient.utils.printer;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * FacePatch - 方块外露面片
 *
 * 描述 VoxelShape 某个面上的一个二维矩形可点击区域。
 * 由 {@link HitVecCalculator#getFacePatches} 从 outline shape 的子 box 中抽取。
 *
 * 坐标系约定（方块相对坐标 0~1）：
 * - EAST/WEST:   fixedCoord=x, u=z, v=y
 * - UP/DOWN:     fixedCoord=y, u=x, v=z
 * - NORTH/SOUTH: fixedCoord=z, u=x, v=y
 *
 * 核心用途：
 * 当中心点被遮挡或超出 reach 时，在同一面的实际暴露区域内
 * 搜索一个更优的合法点击点，避免误判"整个面不可点"。
 */
public record FacePatch(
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
