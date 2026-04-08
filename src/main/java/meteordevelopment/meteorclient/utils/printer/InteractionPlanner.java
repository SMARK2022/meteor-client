package meteordevelopment.meteorclient.utils.printer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import meteordevelopment.meteorclient.utils.player.Rotations;

import java.util.List;

/**
 * InteractionPlanner - 通用交互几何规划器
 *
 * 统一 UseBlock（右键修状态）和 PlaceBlock（放置方块）的"在哪点、点哪面"几何规划。
 * 避免两套平行的面遍历/点击点选择逻辑。
 *
 * 两级规划策略（与 PlacementResolver 的 refinement 对齐）：
 * 1. 快速路径：使用 shape 中心/偏好点，大多数完整块直接通过
 * 2. Refinement：初始点失败时，在面片区域内搜索最优可行点
 *
 * 输入：目标位置、面优先级、strict/LOS/reach 约束
 * 输出：一个 {@link ActionPlan.Interaction}，或 null
 */
public final class InteractionPlanner {

    /** 默认面优先级：UP 最自然，然后水平四面，最后 DOWN */
    public static final Direction[] DEFAULT_FACE_ORDER = {
        Direction.UP,
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST,
        Direction.DOWN
    };

    private InteractionPlanner() {}

    /**
     * 为"点击方块自身"规划交互（UseBlock 类动作）。
     *
     * 每个面先尝试快速中心点，失败则在面片区域内 refinement。
     * 这与 PlacementResolver 对放置链路的处理策略完全一致，
     * 消除了 UseBlock 和 PlaceBlock 的几何差异。
     *
     * @param mc             客户端实例
     * @param pos            要点击的方块位置
     * @param preferredFaces 面优先级数组
     * @param strict         是否启用 NCP 方向检查
     * @param checkLos       是否检查视线
     * @param maxReach       最大交互距离
     * @return Interaction 或 null（所有面都不可行时）
     */
    public static ActionPlan.Interaction planSelfInteraction(
        MinecraftClient mc, BlockPos pos,
        Direction[] preferredFaces,
        boolean strict, boolean checkLos, double maxReach
    ) {
        Vec3d eyePos = mc.player.getEyePos();
        World world = mc.world;

        for (Direction face : preferredFaces) {
            // 快速路径：shape 中心点
            Vec3d hitVec = HitVecCalculator.getShapeHitVec(world, pos, face);
            if (hitVec != null && isPointValid(hitVec, face, pos, eyePos, world, mc, strict, checkLos, maxReach)) {
                return toInteraction(pos, face, hitVec);
            }

            // Refinement：面片区域内搜索最优可行点
            Vec3d refined = refineSelfFace(world, pos, face, eyePos, mc, strict, checkLos, maxReach);
            if (refined != null) {
                return toInteraction(pos, face, refined);
            }
        }
        return null;
    }

    /**
     * 使用默认面优先级规划自身交互。
     */
    public static ActionPlan.Interaction planSelfInteraction(
        MinecraftClient mc, BlockPos pos,
        boolean strict, boolean checkLos, double maxReach
    ) {
        return planSelfInteraction(mc, pos, DEFAULT_FACE_ORDER, strict, checkLos, maxReach);
    }

    // ==================== 内部方法 ====================

    /**
     * 验证点击点是否满足 reach / NCP / LOS 约束。
     *
     * <p>自身交互（右键方块本身来 toggle / waterlog / 调频等）的几何约束
     * 由服务端始终验证——玩家必须物理可达所点击的面。因此此处
     * 无论调用方的 strict / checkLos 如何设置，一律开启 NCP 方向检查和视线检查，
     * 防止选到被遮挡或背对玩家的面而导致服务端静默拒绝。
     */
    private static boolean isPointValid(
        Vec3d hitVec, Direction face, BlockPos pos,
        Vec3d eyePos, World world, MinecraftClient mc,
        boolean strict, boolean checkLos, double maxReach
    ) {
        return BlockUtilHelper.isPointValid(
            hitVec, face, pos, eyePos, world, mc.player,
            true,  // NCP: 自身交互始终验证面朝向
            true,  // LOS: 自身交互始终验证视线
           an checkLos, double maxReach
    ) {
        return BlockUtilHelper.isPointValid(
            hitVec, face, pos, eyePos, world, mc.player,
            true,  // NCP: 自身交互始终验证面朝向
            true,  // LOS: 自身交互始终验证视线
            maxReach, null
        );
    }

    /**
     * 面片级 refinement：在指定面的实际暴露区域内搜索最优可行点。
     *
     * 复用 {@link HitVecCalculator#getFacePatches} 的面片抽取和采样逻辑，
     * 与放置链路的 refinement 共享同一套几何基础设施。
     *
     * @return 最优合法点击坐标，未找到则返回 null
     */
    private static Vec3d refineSelfFace(
        World world, BlockPos pos, Direction face,
        Vec3d eyePos, MinecraftClient mc,
        boolean strict, boolean checkLos, double maxReach
    ) {
        List<HitVecCalculator.FacePatch> patches = HitVecCalculator.getFacePatches(world, pos, face);
        if (patches.isEmpty()) return null;

        Vec3d bestPoint = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (HitVecCalculator.FacePatch patch : patches) {
            // 5 个确定性采样点（面片中心 + 四角偏移），与 PlacementResolver 一致
            double[][] samples = sampleUV(patch);
            for (double[] uv : samples) {
                Vec3d point = patch.toWorld(pos, uv[0], uv[1]);

                if (!isPointValid(point, face, pos, eyePos, world, mc, strict, checkLos, maxReach)) {
                    continue;
                }

                // 打分：居中越好，离边缘越远越稳
                double margin = patch.centerMargin(uv[0], uv[1]);
                double score = margin;

                if (score > bestScore) {
                    bestScore = score;
                    bestPoint = point;
                }
            }
        }
        return bestPoint;
    }

    /**
     * 为自身交互的面片生成 5 个稳定采样点。
     * 不需要偏好高度（自身交互无放置方向要求），均匀覆盖即可。
     */
    private static double[][] sampleUV(HitVecCalculator.FacePatch patch) {
        final double INSET = 1.0 / 64.0;
        double sMinU = patch.minU() + INSET, sMaxU = patch.maxU() - INSET;
        double sMinV = patch.minV() + INSET, sMaxV = patch.maxV() - INSET;
        if (sMinU > sMaxU) { sMinU = sMaxU = (patch.minU() + patch.maxU()) / 2; }
        if (sMinV > sMaxV) { sMinV = sMaxV = (patch.minV() + patch.maxV()) / 2; }

        double midU = (sMinU + sMaxU) / 2;
        double midV = (sMinV + sMaxV) / 2;
        double u25 = sMinU + (sMaxU - sMinU) * 0.25;
        double u75 = sMinU + (sMaxU - sMinU) * 0.75;
        double v25 = sMinV + (sMaxV - sMinV) * 0.25;
        double v75 = sMinV + (sMaxV - sMinV) * 0.75;

        return new double[][] {
            { midU, midV },   // 中心
            { u25,  v25  },   // 左下
            { u75,  v25  },   // 右下
            { u25,  v75  },   // 左上
            { u75,  v75  }    // 右上
        };
    }

    /** 从点击坐标构造 Interaction */
    private static ActionPlan.Interaction toInteraction(BlockPos pos, Direction face, Vec3d hitVec) {
        float yaw = (float) Rotations.getYaw(hitVec);
        float pitch = (float) Rotations.getPitch(hitVec);
        return new ActionPlan.Interaction(pos, face, hitVec, yaw, pitch, true);
    }
}
