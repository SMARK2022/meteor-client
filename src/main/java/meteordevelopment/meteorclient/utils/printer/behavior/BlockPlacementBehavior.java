package meteordevelopment.meteorclient.utils.printer.behavior;

import meteordevelopment.meteorclient.utils.printer.*;

import net.minecraft.block.AbstractChestBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;

/**
 * BlockPlacementBehavior - 方块放置行为
 *
 * 处理标准的方块放置任务：
 * - 在空气/可替换位置放下新方块
 * - 完成半砖单层→双层升级
 * - 双箱子两阶段放置（安全 SINGLE → 合并，位置重定向由 ResolverRegistry 处理）
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

        // 情况 2：双箱子完成（thisPos 已有 SINGLE，需要合并阶段，ResolverRegistry 会重定向到 pairPos）
        if (isDoubleChestCompletion(task)) return true;

        // 情况 3：标准放置（当前位置是空气、流体或可替换方块）
        var current = task.currentState();
        if (current.isAir()) return true;
        if (current.getBlock() instanceof FluidBlock) return true;
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

        // 双箱子额外检查：ChestType 必须匹配（SINGLE 不等于 LEFT/RIGHT）
        if (task.desiredState().getBlock() instanceof ChestBlock
            && task.desiredState().contains(ChestBlock.CHEST_TYPE)
            && task.currentState().contains(ChestBlock.CHEST_TYPE)) {

            ChestType desired = task.desiredState().get(ChestBlock.CHEST_TYPE);
            ChestType current = task.currentState().get(ChestBlock.CHEST_TYPE);
            if (desired != current) return false;
        }

        return true;
    }

    @Override
    public ActionPlan plan(PrinterTask task, MinecraftClient mc, boolean strict, boolean checkLos, double maxReach) {
        Item item = task.desiredState().getBlock().asItem();

        // 检查物品栏（包括背包）
        if (!InvUtils.find(item).found()) return null;

        // 使用规则引擎解析放置几何（双箱子/半砖的特殊逻辑在 ResolverRegistry.resolve() 内处理）
        PlacementContext ctx = PlacementContext.of(
            mc.world, task.pos(), task.desiredState(), mc.player, strict, checkLos, maxReach
        );
        PlacementOption option = ResolverRegistry.resolve(ctx);
        if (option == null || option.hitVec() == null) return null;

        // 确定实际放置位置（双箱子合并阶段可能重定向到 pairPos）
        BlockPos effectivePos = option.actualTargetPos() != null ? option.actualTargetPos() : task.pos();

        // 计算旋转角度
        Vec3d hitVec = option.hitVec();
        float yaw = (float) Rotations.getYaw(hitVec);
        float pitch = (float) Rotations.getPitch(hitVec);

        // 确定交互位置（基于实际放置位置计算）
        BlockPos interactPos = option.getInteractPos(effectivePos);
        boolean selfPlacement = interactPos.equals(effectivePos);

        // 确定潜行策略
        var interactState = mc.world.getBlockState(interactPos);
        ActionPlan.SneakPolicy sneakPolicy = BlockUtilHelper.determineSneakPolicy(interactState);

        // 双箱子潜行修正：根据阶段和交互目标精确调整
        // - Plan A merge（点击箱子侧面）：determineSneakPolicy 已处理 → REQUIRE_SNEAK
        // - Plan B merge（点击非箱子面）：需要 NOT_SNEAK 让规则3自动合并生效
        // - Single-safe + 附近有同向 SINGLE：需要 SNEAK 抑制规则3意外合并
        // - Single-safe + 附近无 SINGLE：无风险 → KEEP_CURRENT
        if (isDoubleChestTarget(task) && !(interactState.getBlock() instanceof AbstractChestBlock)) {
            sneakPolicy = computeChestSneakPolicy(mc, effectivePos, task);
        }

        return new ActionPlan.PlaceBlock(
            effectivePos,
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

    /**
     * 判断是否为双箱子完成阶段（thisPos 已有同种同向 SINGLE，需要在 pairPos 触发合并）
     */
    private boolean isDoubleChestCompletion(PrinterTask task) {
        if (!isDoubleChestTarget(task)) return false;
        // thisPos 已有同种方块（SINGLE chest），但 ChestType 不匹配 → 需要合并阶段
        return task.currentState().getBlock() == task.desiredState().getBlock()
            && task.currentState().contains(ChestBlock.CHEST_TYPE)
            && task.currentState().get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE;
    }

    /** 判断任务目标是否为双箱子（LEFT 或 RIGHT） */
    private static boolean isDoubleChestTarget(PrinterTask task) {
        if (!(task.desiredState().getBlock() instanceof ChestBlock)) return false;
        if (!task.desiredState().contains(ChestBlock.CHEST_TYPE)) return false;
        ChestType type = task.desiredState().get(ChestBlock.CHEST_TYPE);
        return type == ChestType.LEFT || type == ChestType.RIGHT;
    }

    /**
     * 双箱子非箱子面交互时的潜行策略计算
     *
     * 判断当前阶段（合并 vs 安全 SINGLE），返回对应策略：
     * - 合并阶段（Plan B）：REQUIRE_NOT_SNEAK，让规则3自动合并生效
     * - 安全 SINGLE + 附近有同向 SINGLE：REQUIRE_SNEAK，抑制规则3意外合并
     * - 安全 SINGLE + 附近无同向 SINGLE：KEEP_CURRENT，无合并风险
     */
    private ActionPlan.SneakPolicy computeChestSneakPolicy(MinecraftClient mc, BlockPos effectivePos, PrinterTask task) {
        Direction facing = task.desiredState().get(Properties.HORIZONTAL_FACING);
        ChestType taskType = task.desiredState().get(ChestBlock.CHEST_TYPE);
        Direction pairDir = taskType == ChestType.LEFT
            ? facing.rotateYClockwise()
            : facing.rotateYCounterclockwise();

        // 判断阶段：pair 方向（任一侧）有同种同向 SINGLE → 合并阶段
        boolean isMergePhase = isSameFacingSingle(mc, task.pos().offset(pairDir), task)
            || isSameFacingSingle(mc, task.pos(), task);

        if (isMergePhase) {
            // Plan B merge：不潜行 → 规则3自动合并
            return ActionPlan.SneakPolicy.REQUIRE_NOT_SNEAK;
        }

        // 安全 SINGLE 阶段：检查放置位置两侧是否有会触发规则3的同向 SINGLE
        boolean cwHasSingle = isSameFacingSingle(mc, effectivePos.offset(facing.rotateYClockwise()), task);
        boolean ccwHasSingle = isSameFacingSingle(mc, effectivePos.offset(facing.rotateYCounterclockwise()), task);

        return (cwHasSingle || ccwHasSingle)
            ? ActionPlan.SneakPolicy.REQUIRE_SNEAK
            : ActionPlan.SneakPolicy.KEEP_CURRENT;
    }

    /** 检查指定位置是否为同种同向 SINGLE 箱子 */
    private static boolean isSameFacingSingle(MinecraftClient mc, BlockPos pos, PrinterTask task) {
        var state = mc.world.getBlockState(pos);
        return state.getBlock() instanceof ChestBlock
            && state.getBlock() == task.desiredState().getBlock()
            && state.contains(ChestBlock.CHEST_TYPE)
            && state.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE
            && state.get(Properties.HORIZONTAL_FACING) == task.desiredState().get(Properties.HORIZONTAL_FACING);
    }
}
