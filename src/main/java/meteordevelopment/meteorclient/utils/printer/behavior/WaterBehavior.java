package meteordevelopment.meteorclient.utils.printer.behavior;

import meteordevelopment.meteorclient.utils.printer.*;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;

/**
 * WaterBehavior - 流体放置行为
 *
 * 处理水桶/岩浆桶放置流体源方块。
 * 产出 {@link ActionPlan.UseItemOnBlock} 类型的动作计划。
 *
 * 机制：手持桶对邻居表面 interactBlock，流体出现在 targetPos。
 * 与 PlaceBlock 共用几何解析（ResolverRegistry），但物品和验证逻辑不同。
 */
public class WaterBehavior implements PrinterBehavior {

    @Override
    public boolean supports(PrinterTask task) {
        Block desired = task.desiredState().getBlock();

        // 只处理水和岩浆的源方块
        if (desired != Blocks.WATER && desired != Blocks.LAVA) return false;
        if (!task.desiredState().contains(FluidBlock.LEVEL)) return false;
        if (task.desiredState().get(FluidBlock.LEVEL) != 0) return false; // 只放源方块

        // 当前位置不能已经是目标流体源
        BlockState current = task.currentState();
        if (current.getBlock() == desired
            && current.contains(FluidBlock.LEVEL)
            && current.get(FluidBlock.LEVEL) == 0) return false;

        // 检查有对应的桶
        Item bucket = getBucket(desired);
        return bucket != null && InvUtils.find(bucket).found();
    }

    @Override
    public boolean isSatisfied(PrinterTask task) {
        Block desired = task.desiredState().getBlock();
        BlockState current = task.currentState();
        return current.getBlock() == desired
            && current.contains(FluidBlock.LEVEL)
            && current.get(FluidBlock.LEVEL) == 0;
    }

    @Override
    public ActionPlan plan(PrinterTask task, MinecraftClient mc, boolean strict, boolean checkLos, double maxReach) {
        Block desired = task.desiredState().getBlock();
        Item bucket = getBucket(desired);
        if (bucket == null) return null;
        if (!InvUtils.find(bucket).found()) return null;

        // 复用 PlaceBlock 的几何解析：找到邻居表面来点击
        PlacementContext ctx = PlacementContext.of(
            mc.world, task.pos(), task.desiredState(), mc.player, strict, checkLos, maxReach
        );
        PlacementOption option = ResolverRegistry.resolve(ctx);
        if (option == null || option.hitVec() == null) return null;

        Vec3d hitVec = option.hitVec();
        float yaw = (float) Rotations.getYaw(hitVec);
        float pitch = (float) Rotations.getPitch(hitVec);

        BlockPos interactPos = option.getInteractPos(task.pos());
        boolean selfPlacement = interactPos.equals(task.pos());

        // 潜行策略：如果邻居是交互类方块（箱子等），需要潜行来绕过
        var interactState = mc.world.getBlockState(interactPos);
        ActionPlan.SneakPolicy sneakPolicy = BlockUtilHelper.SNEAK_BLOCKS.contains(interactState.getBlock())
            ? ActionPlan.SneakPolicy.REQUIRE_SNEAK
            : ActionPlan.SneakPolicy.KEEP_CURRENT;

        return new ActionPlan.UseItemOnBlock(
            task.pos(),
            task.desiredState(),
            new ActionPlan.Interaction(
                interactPos,
                option.getClickedFace(),
                hitVec,
                yaw,
                pitch,
                selfPlacement
            ),
            bucket,
            sneakPolicy,
            ActionPlan.HandPolicy.ANY_HAND_WITH_ITEM,
            state -> !(state.getBlock() == desired
                && state.contains(FluidBlock.LEVEL)
                && state.get(FluidBlock.LEVEL) == 0)
        );
    }

    private static Item getBucket(Block fluidBlock) {
        if (fluidBlock == Blocks.WATER) return Items.WATER_BUCKET;
        if (fluidBlock == Blocks.LAVA) return Items.LAVA_BUCKET;
        return null;
    }
}
