package meteordevelopment.meteorclient.utils.printer.behavior;

import meteordevelopment.meteorclient.utils.printer.*;

import net.minecraft.block.BlockState;
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
 * 处理标准方块放置，包含双箱子 per-cell 状态机：
 * - 标准放置：空气/流体/可替换位置放下新方块
 * - 半砖升级：单层→双层
 * - 双箱子：每格只对自己负责，根据邻居状态推导当前格下一步可构建状态
 *
 * 双箱子状态机（{@link ChestStep}）：
 * - SEED_SINGLE：当前格为空/可替换，partner 未就绪 → 安全放 SINGLE
 * - MERGE_HERE：当前格为空/可替换，partner 已是 SINGLE → 放置触发合并
 * - DEFER：当前格已是 SINGLE 中间态，等待 partner 推进 → 视为临时满足
 * - REQUIRES_REPLACE：两侧均为 SINGLE，放置路径无法修复 → 不接管
 */
public class BlockPlacementBehavior implements PrinterBehavior {

    /** 双箱子格位状态机：描述当前格这一步应执行的动作 */
    private enum ChestStep {
        NONE,               // 非双箱子目标，或条件不满足
        SATISFIED,          // 当前格已匹配最终目标
        DEFER,              // 当前格已是合理中间态（SINGLE），等待 partner 推进
        SEED_SINGLE,        // 安全放置独立 SINGLE（partner 未就绪）
        MERGE_HERE,         // 放置 SINGLE 并与 partner 合并
        REQUIRES_REPLACE    // 两侧均为 SINGLE，需要 break-replace
    }

    @Override
    public boolean supports(PrinterTask task) {
        if (task.desiredState().getBlock() instanceof FluidBlock) return false;
        if (task.desiredState().getBlock().asItem() == Items.AIR) return false;

        if (isSlabUpgrade(task)) return true;

        // 双箱子中间态（SINGLE）：由 isSatisfied 判定为 DEFER
        if (isChestIntermediate(task)) return true;

        var current = task.currentState();
        return current.isAir() || current.getBlock() instanceof FluidBlock || current.isReplaceable();
    }

    @Override
    public boolean isSatisfied(PrinterTask task) {
        if (task.desiredState().getBlock() != task.currentState().getBlock()) return false;

        // 半砖：双层升级
        if (task.desiredState().getBlock() instanceof SlabBlock
            && task.desiredState().contains(SlabBlock.TYPE)
            && task.currentState().contains(SlabBlock.TYPE)) {
            SlabType desired = task.desiredState().get(SlabBlock.TYPE);
            SlabType current = task.currentState().get(SlabBlock.TYPE);
            if (desired == SlabType.DOUBLE && current != SlabType.DOUBLE) return false;
        }

        // 双箱子：SINGLE 中间态视为 DEFER（临时满足），等待 partner 推进
        if (task.desiredState().getBlock() instanceof ChestBlock
            && task.desiredState().contains(ChestBlock.CHEST_TYPE)
            && task.currentState().contains(ChestBlock.CHEST_TYPE)) {
            ChestType desired = task.desiredState().get(ChestBlock.CHEST_TYPE);
            ChestType current = task.currentState().get(ChestBlock.CHEST_TYPE);
            if (desired == current) return true;
            // SINGLE 且同种同向 → DEFER
            if (current == ChestType.SINGLE
                && (desired == ChestType.LEFT || desired == ChestType.RIGHT)
                && task.currentState().get(Properties.HORIZONTAL_FACING)
                    == task.desiredState().get(Properties.HORIZONTAL_FACING)) {
                return true;
            }
            return false;
        }

        return true;
    }

    @Override
    public ActionPlan plan(PrinterTask task, MinecraftClient mc, boolean strict, boolean checkLos, double maxReach) {
        Item item = task.desiredState().getBlock().asItem();
        if (!InvUtils.find(item).found()) return null;

        // 双箱子走 per-cell 状态机
        ChestStep step = classifyChestCell(task, mc);
        if (step == ChestStep.SEED_SINGLE || step == ChestStep.MERGE_HERE) {
            return planChest(task, mc, strict, checkLos, maxReach, step);
        }
        // DEFER/SATISFIED/REQUIRES_REPLACE/NONE 中的 DEFER 已在 isSatisfied 拦截

        // 普通放置 / 半砖升级
        PlacementContext ctx = PlacementContext.of(
            mc.world, task.pos(), task.desiredState(), mc.player, strict, checkLos, maxReach
        );
        PlacementOption option = ResolverRegistry.resolve(ctx);
        if (option == null || option.hitVec() == null) return null;

        return buildPlacePlan(task.pos(), task.desiredState(), option, mc, item);
    }

    // ==================== 双箱子状态机 ====================

    /**
     * 分类当前格位在双箱子流程中的状态
     *
     * 每格只看自己 + partner 两个位置，不做对象级调度：
     * - C 已匹配 → SATISFIED
     * - C 为 SINGLE（中间态）→ partner 也是 SINGLE 则 REQUIRES_REPLACE，否则 DEFER
     * - C 为空 + partner 是 SINGLE → MERGE_HERE
     * - C 为空 + partner 未就绪 → SEED_SINGLE
     */
    private ChestStep classifyChestCell(PrinterTask task, MinecraftClient mc) {
        if (!(task.desiredState().getBlock() instanceof ChestBlock)) return ChestStep.NONE;
        if (!task.desiredState().contains(ChestBlock.CHEST_TYPE)) return ChestStep.NONE;
        ChestType desired = task.desiredState().get(ChestBlock.CHEST_TYPE);
        if (desired != ChestType.LEFT && desired != ChestType.RIGHT) return ChestStep.NONE;

        Direction facing = task.desiredState().get(Properties.HORIZONTAL_FACING);
        Direction partnerDir = desired == ChestType.LEFT
            ? facing.rotateYClockwise() : facing.rotateYCounterclockwise();
        BlockState singleRef = task.desiredState().with(ChestBlock.CHEST_TYPE, ChestType.SINGLE);

        BlockState currentC = task.currentState();
        BlockState currentP = mc.world.getBlockState(task.pos().offset(partnerDir));

        // C 已匹配最终态
        if (currentC.getBlock() == task.desiredState().getBlock()
            && currentC.contains(ChestBlock.CHEST_TYPE)
            && currentC.get(ChestBlock.CHEST_TYPE) == desired
            && currentC.get(Properties.HORIZONTAL_FACING) == facing) {
            return ChestStep.SATISFIED;
        }

        boolean cIsSingle = ResolverRegistry.isSingleChest(currentC, singleRef, facing);
        boolean pIsSingle = ResolverRegistry.isSingleChest(currentP, singleRef, facing);

        if (cIsSingle && pIsSingle) return ChestStep.REQUIRES_REPLACE;
        if (cIsSingle) return ChestStep.DEFER;

        boolean cIsEmpty = currentC.isAir() || currentC.isReplaceable()
            || currentC.getBlock() instanceof FluidBlock;
        if (!cIsEmpty) return ChestStep.NONE;

        return pIsSingle ? ChestStep.MERGE_HERE : ChestStep.SEED_SINGLE;
    }

    /**
     * 双箱子放置计划：根据 ChestStep 调用对应的 resolver helper
     *
     * 潜行策略：
     * - SEED_SINGLE + 附近有同向 SINGLE → REQUIRE_SNEAK（抑制规则3意外合并）
     * - MERGE_HERE Plan A（点击箱子侧面）→ determineSneakPolicy 自然 REQUIRE_SNEAK
     * - MERGE_HERE Plan B（非箱子面）→ REQUIRE_NOT_SNEAK（规则3自动合并）
     * - 与 base 策略（交互目标是否 SNEAK_BLOCK）合并，冲突则放弃候选
     */
    private ActionPlan planChest(PrinterTask task, MinecraftClient mc,
                                 boolean strict, boolean checkLos, double maxReach, ChestStep step) {
        Item item = task.desiredState().getBlock().asItem();
        Direction facing = task.desiredState().get(Properties.HORIZONTAL_FACING);
        ChestType desired = task.desiredState().get(ChestBlock.CHEST_TYPE);
        Direction partnerDir = desired == ChestType.LEFT
            ? facing.rotateYClockwise() : facing.rotateYCounterclockwise();
        BlockState singleState = task.desiredState().with(ChestBlock.CHEST_TYPE, ChestType.SINGLE);

        PlacementContext ctx = PlacementContext.of(
            mc.world, task.pos(), singleState, mc.player, strict, checkLos, maxReach
        );

        PlacementOption option = (step == ChestStep.MERGE_HERE)
            ? ResolverRegistry.resolveChestMerge(ctx, facing, partnerDir)
            : ResolverRegistry.resolveChestSingleSafe(ctx, facing);
        if (option == null || option.hitVec() == null) return null;

        // 潜行策略计算
        BlockPos interactPos = option.getInteractPos(task.pos());
        var interactState = mc.world.getBlockState(interactPos);
        ActionPlan.SneakPolicy baseSneakPolicy = BlockUtilHelper.determineSneakPolicy(interactState);
        ActionPlan.SneakPolicy chestPolicy = computeChestSneak(mc, task, step, interactState, facing);

        // 合并 base 与 chest 策略，冲突则放弃
        ActionPlan.SneakPolicy sneakPolicy = baseSneakPolicy;
        if (chestPolicy != ActionPlan.SneakPolicy.KEEP_CURRENT) {
            if (baseSneakPolicy == ActionPlan.SneakPolicy.REQUIRE_SNEAK
                && chestPolicy == ActionPlan.SneakPolicy.REQUIRE_NOT_SNEAK) return null;
            if (baseSneakPolicy == ActionPlan.SneakPolicy.REQUIRE_NOT_SNEAK
                && chestPolicy == ActionPlan.SneakPolicy.REQUIRE_SNEAK) return null;
            sneakPolicy = chestPolicy;
        }

        Vec3d hitVec = option.hitVec();
        boolean selfPlacement = interactPos.equals(task.pos());

        return new ActionPlan.PlaceBlock(
            task.pos(),
            task.desiredState(),
            new ActionPlan.Interaction(interactPos, option.getClickedFace(), hitVec,
                (float) Rotations.getYaw(hitVec), (float) Rotations.getPitch(hitVec), selfPlacement),
            item, sneakPolicy, ActionPlan.HandPolicy.ANY_HAND_WITH_ITEM
        );
    }

    /** 根据 ChestStep 和交互目标计算箱子专用潜行策略 */
    private ActionPlan.SneakPolicy computeChestSneak(MinecraftClient mc, PrinterTask task,
                                                      ChestStep step, BlockState interactState, Direction facing) {
        if (step == ChestStep.MERGE_HERE) {
            // Plan A（点击箱子）→ determineSneakPolicy 已处理；Plan B（非箱子）→ 需要 NOT_SNEAK
            return (interactState.getBlock() instanceof ChestBlock)
                ? ActionPlan.SneakPolicy.KEEP_CURRENT
                : ActionPlan.SneakPolicy.REQUIRE_NOT_SNEAK;
        }
        // SEED_SINGLE：检查当前格两侧是否有同向 SINGLE（会触发规则3意外合并）
        BlockPos cwPos = task.pos().offset(facing.rotateYClockwise());
        BlockPos ccwPos = task.pos().offset(facing.rotateYCounterclockwise());
        BlockState singleRef = task.desiredState().with(ChestBlock.CHEST_TYPE, ChestType.SINGLE);
        if (ResolverRegistry.isSingleChest(mc.world.getBlockState(cwPos), singleRef, facing)
            || ResolverRegistry.isSingleChest(mc.world.getBlockState(ccwPos), singleRef, facing)) {
            return ActionPlan.SneakPolicy.REQUIRE_SNEAK;
        }
        return ActionPlan.SneakPolicy.KEEP_CURRENT;
    }

    // ==================== 通用 helpers ====================

    /** 构建标准放置计划（普通方块 / 半砖） */
    private ActionPlan buildPlacePlan(BlockPos pos, BlockState desiredState,
                                      PlacementOption option, MinecraftClient mc, Item item) {
        Vec3d hitVec = option.hitVec();
        BlockPos interactPos = option.getInteractPos(pos);
        boolean selfPlacement = interactPos.equals(pos);
        var interactState = mc.world.getBlockState(interactPos);
        ActionPlan.SneakPolicy sneakPolicy = BlockUtilHelper.determineSneakPolicy(interactState);

        return new ActionPlan.PlaceBlock(
            pos, desiredState,
            new ActionPlan.Interaction(interactPos, option.getClickedFace(), hitVec,
                (float) Rotations.getYaw(hitVec), (float) Rotations.getPitch(hitVec), selfPlacement),
            item, sneakPolicy, ActionPlan.HandPolicy.ANY_HAND_WITH_ITEM
        );
    }

    /** 半砖升级（单层→双层）判定 */
    private boolean isSlabUpgrade(PrinterTask task) {
        if (!(task.desiredState().getBlock() instanceof SlabBlock)) return false;
        if (task.desiredState().getBlock() != task.currentState().getBlock()) return false;
        if (!task.desiredState().contains(SlabBlock.TYPE) || !task.currentState().contains(SlabBlock.TYPE)) return false;
        SlabType desired = task.desiredState().get(SlabBlock.TYPE);
        SlabType current = task.currentState().get(SlabBlock.TYPE);
        return desired == SlabType.DOUBLE && current != SlabType.DOUBLE;
    }

    /** 双箱子中间态判定（C = 同种同向 SINGLE，desired = LEFT/RIGHT）→ supports 通道进入 isSatisfied DEFER */
    private boolean isChestIntermediate(PrinterTask task) {
        if (!(task.desiredState().getBlock() instanceof ChestBlock)) return false;
        if (!task.desiredState().contains(ChestBlock.CHEST_TYPE)) return false;
        ChestType desired = task.desiredState().get(ChestBlock.CHEST_TYPE);
        if (desired != ChestType.LEFT && desired != ChestType.RIGHT) return false;
        return task.currentState().getBlock() == task.desiredState().getBlock()
            && task.currentState().contains(ChestBlock.CHEST_TYPE)
            && task.currentState().get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE
            && task.currentState().contains(Properties.HORIZONTAL_FACING)
            && task.currentState().get(Properties.HORIZONTAL_FACING)
                == task.desiredState().get(Properties.HORIZONTAL_FACING);
    }
}
