package meteordevelopment.meteorclient.utils.printer.behavior;

import meteordevelopment.meteorclient.utils.printer.*;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.FluidFillable;
import net.minecraft.client.MinecraftClient;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;

import java.util.List;

/**
 * WaterBehavior — 流体源方块放置行为
 *
 * <p>手持水桶/岩浆桶对邻居表面 interactBlock，流体出现在 targetPos。
 *
 * <h2>关键语义</h2>
 * <p>水桶不是方块物品。它的 item-on-block 语义会<b>优先作用于被点击方块本身</b>：
 * 如果被点击方块是 {@link FluidFillable}（半砖/楼梯/活板门等），
 * 水桶会被该方块"消费"（变为 waterlogged），而非在 targetPos 释放流体。
 *
 * <p>因此 plan 阶段必须过滤掉这类"危险 support"，而非依赖 sneak 绕过
 * （sneak 只能绕过 GUI/onUse 交互，不能阻止 FluidFillable 消费桶）。
 */
public class WaterBehavior implements PrinterBehavior {

    @Override
    public Group group() {
        return Group.FLUID;
    }

    @Override
    public Key key() {
        return Key.FLUID_SOURCE;
    }

    @Override
    public boolean supports(PrinterTask task) {
        Block desired = task.desiredState().getBlock();
        if (desired != Blocks.WATER && desired != Blocks.LAVA) return false;
        if (!task.desiredState().contains(FluidBlock.LEVEL)) return false;
        if (task.desiredState().get(FluidBlock.LEVEL) != 0) return false;

        BlockState current = task.currentState();
        if (current.getBlock() == desired
            && current.contains(FluidBlock.LEVEL)
            && current.get(FluidBlock.LEVEL) == 0) return false;

        return getBucket(desired) != null;
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

        Fluid fluid = (desired == Blocks.WATER) ? Fluids.WATER : Fluids.LAVA;

        // resolveAll → 取全部几何合法选项 → 过滤掉会消费桶的危险 support
        PlacementContext ctx = PlacementContext.of(
            mc.world, task.pos(), task.desiredState(), mc.player, strict, checkLos, maxReach
        );
        List<PlacementOption> all = ResolverRegistry.get(ctx.targetState()).resolveAll(ctx);

        PlacementOption best = null;
        ActionPlan.SneakPolicy bestSneak = null;

        for (PlacementOption opt : all) {
            if (opt.hitVec() == null) continue;

            BlockPos interactPos = opt.getInteractPos(task.pos());
            boolean self = interactPos.equals(task.pos());

            // 核心过滤：邻居方块如果会吞掉桶（FluidFillable 且当前可接收该流体），拒绝
            if (!self) {
                BlockState interactState = mc.world.getBlockState(interactPos);
                if (wouldConsumeFluid(interactState, interactPos, mc, fluid)) continue;
            }

            // sneak 策略：仅对有 GUI/onUse 交互的方块潜行
            BlockState interactState = mc.world.getBlockState(opt.getInteractPos(task.pos()));
            ActionPlan.SneakPolicy sneak = BlockUtilHelper.determineSneakPolicy(interactState);

            // 选优：不需要 sneak 的优先
            if (best == null || (bestSneak == ActionPlan.SneakPolicy.REQUIRE_SNEAK
                && sneak == ActionPlan.SneakPolicy.KEEP_CURRENT)) {
                best = opt;
                bestSneak = sneak;
            }
        }

        if (best == null) return null;

        Vec3d hitVec = best.hitVec();
        BlockPos interactPos = best.getInteractPos(task.pos());
        boolean selfPlacement = interactPos.equals(task.pos());

        return new ActionPlan.UseItemOnBlock(
            task.pos(),
            task.desiredState(),
            new ActionPlan.Interaction(
                interactPos,
                best.getClickedFace(),
                hitVec,
                (float) Rotations.getYaw(hitVec),
                (float) Rotations.getPitch(hitVec),
                selfPlacement
            ),
            bucket,
            bestSneak,
            ActionPlan.HandPolicy.ANY_HAND_WITH_ITEM,
            state -> !(state.getBlock() == desired
                && state.contains(FluidBlock.LEVEL)
                && state.get(FluidBlock.LEVEL) == 0)
        );
    }

    /**
     * 该方块是否会消费桶中的流体（而非让流体释放到 targetPos 去）。
     * <p>判据：方块实现 {@link FluidFillable} 且 {@code canFillWithFluid} 返回 true。
     */
    private static boolean wouldConsumeFluid(BlockState state, BlockPos pos, MinecraftClient mc, Fluid fluid) {
        Block block = state.getBlock();
        return block instanceof FluidFillable fillable
            && fillable.canFillWithFluid(mc.player, mc.world, pos, state, fluid);
    }

    private static Item getBucket(Block fluidBlock) {
        if (fluidBlock == Blocks.WATER) return Items.WATER_BUCKET;
        if (fluidBlock == Blocks.LAVA) return Items.LAVA_BUCKET;
        return null;
    }
}
