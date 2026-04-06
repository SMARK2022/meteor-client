package meteordevelopment.meteorclient.utils.printer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import meteordevelopment.meteorclient.utils.player.Rotations;

/**
 * InteractionPlanner - 通用交互几何规划器
 *
 * 统一 UseBlock（右键修状态）和 PlaceBlock（放置方块）的"在哪点、点哪面"几何规划。
 * 避免两套平行的面遍历/点击点选择逻辑。
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

        for (Direction face : preferredFaces) {
            Vec3d hitVec = HitVecCalculator.getShapeHitVec(mc.world, pos, face);
            if (hitVec == null) continue;

            if (eyePos.distanceTo(hitVec) > maxReach + 0.1) continue;

            if (strict) {
                var validDirs = BlockUtilHelper.getPlaceDirectionsNCP(eyePos, hitVec);
                if (!validDirs.contains(face)) continue;
            }

            if (checkLos && !BlockUtilHelper.canSeeFacePoint(
                pos, face, hitVec, mc.world, mc.player, null)) {
                continue;
            }

            float yaw = (float) Rotations.getYaw(hitVec);
            float pitch = (float) Rotations.getPitch(hitVec);

            return new ActionPlan.Interaction(pos, face, hitVec, yaw, pitch, true);
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
}
