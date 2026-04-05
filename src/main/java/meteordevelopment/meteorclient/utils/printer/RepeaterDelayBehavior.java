package meteordevelopment.meteorclient.utils.printer;

import net.minecraft.block.RepeaterBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import meteordevelopment.meteorclient.utils.player.Rotations;

/**
 * RepeaterDelayBehavior - 中继器延迟修正行为
 *
 * 当蓝图要求的 repeater delay 与世界中不一致时，
 * 通过右键交互循环 delay 到目标值。
 *
 * 设计要点：
 * - 每 tick 只做一次 click，不维护内部计数器
 * - 依赖世界状态驱动：下一 tick 重新检查，不够再点
 * - 不会因为服务端延迟或状态没同步而"多点一手"
 * - 看起来也更像玩家操作
 *
 * 属性处理：
 * - DELAY:   可通过右键修正（目标属性）
 * - FACING:  不可在位修改（必须方向一致才处理）
 * - POWERED: 运行时属性，忽略
 * - LOCKED:  运行时属性，忽略
 */
public class RepeaterDelayBehavior implements PrinterBehavior {

    @Override
    public boolean supports(PrinterTask task) {
        // 双方都必须是 RepeaterBlock
        if (!(task.desiredState().getBlock() instanceof RepeaterBlock)) return false;
        if (!(task.currentState().getBlock() instanceof RepeaterBlock)) return false;

        // 朝向必须一致（不可在位修改，朝向不对需要 break-and-replace）
        if (!task.desiredState().contains(Properties.HORIZONTAL_FACING)
            || !task.currentState().contains(Properties.HORIZONTAL_FACING)) return false;
        if (task.desiredState().get(Properties.HORIZONTAL_FACING)
            != task.currentState().get(Properties.HORIZONTAL_FACING)) return false;

        // delay 必须不一致（这是我们要修的）
        if (!task.desiredState().contains(Properties.DELAY)
            || !task.currentState().contains(Properties.DELAY)) return false;
        return !task.desiredState().get(Properties.DELAY).equals(task.currentState().get(Properties.DELAY));
    }

    @Override
    public boolean isSatisfied(PrinterTask task) {
        if (!(task.currentState().getBlock() instanceof RepeaterBlock)) return false;
        if (!task.desiredState().contains(Properties.DELAY)
            || !task.currentState().contains(Properties.DELAY)) return false;
        return task.desiredState().get(Properties.DELAY).equals(task.currentState().get(Properties.DELAY));
    }

    @Override
    public ActionPlan plan(PrinterTask task, MinecraftClient mc, boolean strict, boolean checkLos, double maxReach) {
        BlockPos pos = task.pos();
        Vec3d eyePos = mc.player.getEyePos();

        // 尝试找到一个可见的面来点击 repeater
        FaceHit fh = findVisibleFace(mc, pos, eyePos, maxReach, strict, checkLos);
        if (fh == null) return null;

        float yaw = (float) Rotations.getYaw(fh.hitVec);
        float pitch = (float) Rotations.getPitch(fh.hitVec);

        return new ActionPlan.UseBlock(
            pos,
            task.desiredState(),
            new ActionPlan.Interaction(
                pos,        // interactPos = repeater 自身
                fh.face,    // clickedFace
                fh.hitVec,
                yaw,
                pitch,
                true         // selfInteraction
            ),
            null,   // 不强制切物品
            ActionPlan.SneakPolicy.REQUIRE_NOT_SNEAK,
            ActionPlan.HandPolicy.KEEP_CURRENT
        );
    }

    // ==================== 内部方法 ====================

    /**
     * 面 + 点击坐标配对
     */
    private record FaceHit(Direction face, Vec3d hitVec) {}

    /**
     * 在 repeater 的所有面中找到第一个合法可见的面和点击点。
     * 优先尝试 UP 面（最自然），然后依次尝试其他面。
     */
    private FaceHit findVisibleFace(MinecraftClient mc, BlockPos pos, Vec3d eyePos,
                                     double maxReach, boolean strict, boolean checkLos) {
        // 优先顺序：UP 最自然，然后水平面，最后 DOWN
        Direction[] faces = {
            Direction.UP,
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST,
            Direction.DOWN
        };

        for (Direction face : faces) {
            Vec3d hitVec = HitVecCalculator.getShapeHitVec(mc.world, pos, face);
            if (hitVec == null) continue;

            // Reach 检查
            if (eyePos.distanceTo(hitVec) > maxReach + 0.1) continue;

            // NCP 方向检查
            if (strict) {
                var validDirs = BlockUtilHelper.getPlaceDirectionsNCP(eyePos, hitVec);
                if (!validDirs.contains(face)) continue;
            }

            // LOS 检查（对 UseBlock 不需要 placementTargetPos 豁免）
            if (checkLos && !BlockUtilHelper.canSeeFacePoint(
                    pos, face, hitVec, mc.world, mc.player, null)) {
                continue;
            }

            return new FaceHit(face, hitVec);
        }
        return null;
    }
}
