package meteordevelopment.meteorclient.utils.printer.behavior;

import meteordevelopment.meteorclient.utils.printer.*;

import net.minecraft.block.FluidBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;

/**
 * BlockPlacementBehavior - 方块放置行为
 *
 * 处理标准的方块放置任务：
 * - 在空气/可替换位置放下新方块
 * - 完成半砖单层→双层升级
 *
 * 内部调用 {@link ResolverRegistry} 进行几何层解析。
 * 产出 {@link ActionPlan.PlaceBlock} 类型的动作计划。
 */
public class BlockPlacementBehavior implements PrinterBehavior {

    @Override
    public boolean supports(PrinterTask task) {
        // 流体目标不由此行为处理（将来由 WaterBehavior 负责）
        if (task.desiredState().getBlock() instanceof FluidBlock) return false;

        // 方块必须有对应物品
        Item item = task.desiredState().getBlock().asItem();
        if (item == Items.AIR) return false;

        // 情况 1：半砖升级（同种方块但需要从单层变双层）
        if (isSlabUpgrade(task)) return true;

        // 情况 2：标准放置（当前位置可替换）
        var current = task.currentState();
        if (current.isAir()) return true;
        if (current.getBlock() instanceof FluidBlock) return true;
        if (current.getFluidState() != null && !current.getFluidState().isEmpty()) return true;
        return current.isReplaceable();
    }

    @Override
    public boolean isSatisfied(PrinterTask task) {
        // 方块类型不同 → 未满足
        if (task.desiredState().getBlock() != task.currentState().getBlock()) return false;

        // 半砖额外检查：双层升级
        if (task.desiredState().getBlock() instanceof SlabBlock
            && task.desiredState().contains(SlabBlock.TYPE)
            && task.currentState().contains(SlabBlock.TYPE)) {

            SlabType desired = task.desiredState().get(SlabBlock.TYPE);
            SlabType current = task.currentState().get(SlabBlock.TYPE);
            if (desired == SlabType.DOUBLE && current != SlabType.DOUBLE) return false;
        }

        return true;
    }

    @Override
    public ActionPlan plan(PrinterTask task, MinecraftClient mc, boolean strict, boolean checkLos, double maxReach) {
        Item item = task.desiredState().getBlock().asItem();

        // 检查物品栏（包括背包）
        if (!InvUtils.find(item).found()) return null;

        // 使用规则引擎解析放置几何
        PlacementContext ctx = PlacementContext.of(
            mc.world, task.pos(), task.desiredState(), mc.player, strict, checkLos, maxReach
        );
        PlacementOption option = ResolverRegistry.resolve(ctx);
        if (option == null || option.hitVec() == null) return null;

        // 计算旋转角度
        Vec3d hitVec = option.hitVec();
        float yaw = (float) Rotations.getYaw(hitVec);
        float pitch = (float) Rotations.getPitch(hitVec);

        // 确定交互位置
        BlockPos interactPos = option.getInteractPos(task.pos());
        boolean selfPlacement = interactPos.equals(task.pos());

        // 确定潜行策略
        var interactState = mc.world.getBlockState(interactPos);
        ActionPlan.SneakPolicy sneakPolicy = BlockUtilHelper.SNEAK_BLOCKS.contains(interactState.getBlock())
            ? ActionPlan.SneakPolicy.REQUIRE_SNEAK
            : ActionPlan.SneakPolicy.KEEP_CURRENT;

        return new ActionPlan.PlaceBlock(
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
            item,
            sneakPolicy,
            ActionPlan.HandPolicy.ANY_HAND_WITH_ITEM
        );
    }

    /**
     * 判断是否为半砖升级（单层→双层）
     */
    private boolean isSlabUpgrade(PrinterTask task) {
        if (!(task.desiredState().getBlock() instanceof SlabBlock)) return false;
        if (task.desiredState().getBlock() != task.currentState().getBlock()) return false;
        if (!task.desiredState().contains(SlabBlock.TYPE) || !task.currentState().contains(SlabBlock.TYPE)) return false;

        SlabType desired = task.desiredState().get(SlabBlock.TYPE);
        SlabType current = task.currentState().get(SlabBlock.TYPE);
        return desired == SlabType.DOUBLE && current != SlabType.DOUBLE;
    }
}
