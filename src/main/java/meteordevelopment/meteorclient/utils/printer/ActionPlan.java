package meteordevelopment.meteorclient.utils.printer;

import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.function.Predicate;

/**
 * ActionPlan - 动作计划密封接口
 *
 * 将顶层语义从"放块计划"提升为"动作计划"。
 * 完整的四种动作谱系：
 * - {@link PlaceBlock}:     放置方块
 * - {@link UseBlock}:       右键交互修状态
 * - {@link UseItemOnBlock}: 手持物品对方块交互
 * - {@link UseItemInAir}:   纯物品使用（不依赖方块表面 hit）
 *
 * 两阶段架构中的角色：
 * - Pre tick: 由 {@link PrinterBehavior} 创建
 * - Pre-Flying: 由 Printer / Rotations callback 执行
 *
 * 内含三组紧密关联的类型：
 * - 策略枚举：{@link SneakPolicy}、{@link HandPolicy}
 * - 几何信息：{@link Interaction}
 * - 具体动作：PlaceBlock / UseBlock / UseItemOnBlock / UseItemInAir
 */
public sealed interface ActionPlan
    permits ActionPlan.PlaceBlock, ActionPlan.UseBlock, ActionPlan.UseItemOnBlock, ActionPlan.UseItemInAir {

    /** 目标方块位置 */
    BlockPos targetPos();

    /** 蓝图期望的最终状态 */
    BlockState desiredState();

    /** 几何交互信息（点哪里、点哪个面、转多少角度） */
    Interaction interaction();

    /** 需要的物品，null 表示不关心 */
    Item requiredItem();

    /** 潜行策略 */
    SneakPolicy sneakPolicy();

    /** 手部策略 */
    HandPolicy handPolicy();

    // ==================== 策略枚举 ====================

    /**
     * 潜行策略 - 三态替代原 boolean needsSneak
     *
     * KEEP_CURRENT:      不关心当前潜行状态
     * REQUIRE_SNEAK:     必须潜行（放块到容器/红石元件旁）
     * REQUIRE_NOT_SNEAK: 必须非潜行（右键修状态，如 repeater delay）
     */
    enum SneakPolicy {
        KEEP_CURRENT,
        REQUIRE_SNEAK,
        REQUIRE_NOT_SNEAK
    }

    /**
     * 手部策略 - 替代原来硬编码的 item switch 逻辑
     *
     * PREFER_MAIN_NO_SWITCH: 不切换物品，优先主手（UseBlock 默认策略）
     * ANY_HAND_WITH_ITEM:    主手或副手有目标物品即可
     * REQUIRE_MAIN_HAND:     必须在主手
     * REQUIRE_OFF_HAND:      必须在副手
     */
    enum HandPolicy {
        PREFER_MAIN_NO_SWITCH,
        ANY_HAND_WITH_ITEM,
        REQUIRE_MAIN_HAND,
        REQUIRE_OFF_HAND
    }

    // ==================== 几何交互信息 ====================

    /**
     * Interaction - 一次交互的完整几何描述
     *
     * 从动作语义中分离出纯粹的"在哪里点、点哪个面、转到什么角度"。
     * 无论是 PlaceBlock 还是 UseBlock，几何层的表达方式是统一的。
     */
    record Interaction(
        /** 实际要点击的方块位置（邻居或自身） */
        BlockPos interactPos,

        /** 要点击的面 */
        Direction clickedFace,

        /** 精确的点击坐标 */
        Vec3d hitVec,

        /** 旋转角度 yaw */
        float yaw,

        /** 旋转角度 pitch */
        float pitch,

        /** 是否点击自身（双层半砖补全、右键修状态等） */
        boolean selfInteraction
    ) {}

    // ==================== 具体动作类型 ====================

    /**
     * PlaceBlock - 放置方块动作
     *
     * 语义：拿着物品，对邻居/自身执行 interactBlock，目标是在 targetPos 放下新方块。
     * 由 {@link BlockPlacementBehavior} 创建。
     */
    record PlaceBlock(
        BlockPos targetPos,
        BlockState desiredState,
        Interaction interaction,
        Item requiredItem,
        SneakPolicy sneakPolicy,
        HandPolicy handPolicy
    ) implements ActionPlan {}

    /**
     * UseBlock - 右键方块交互动作
     *
     * 语义：对已存在的方块执行 interactBlock，目标是修改其状态属性。
     * 例如：repeater delay 修正、comparator mode 切换、redstone wire dot/cross 切换。
     *
     * requiredItem 通常为 null（不需要特定物品）。
     * sneakPolicy 通常为 REQUIRE_NOT_SNEAK（必须非潜行才能与方块交互）。
     * stillNeedsAction 由 Behavior 提供，在执行前判断目标是否仍需修正（防止 stale-plan）。
     */
    record UseBlock(
        BlockPos targetPos,
        BlockState desiredState,
        Interaction interaction,
        Item requiredItem,
        SneakPolicy sneakPolicy,
        HandPolicy handPolicy,
        Predicate<BlockState> stillNeedsAction
    ) implements ActionPlan {}

    /**
     * UseItemOnBlock - 手持物品右键方块（vanilla doItemUse 链）
     *
     * 语义：手持特定物品，对邻居表面执行 vanilla 右键链（interactBlock → interactItem）。
     * 水桶/岩浆桶等物品的 useOnBlock 返回 PASS，实际放置由 item.use() 完成。
     *
     * 执行链：interactBlock → 若 ACCEPTED 则完成；若 PASS → interactItem → swing。
     * stillNeedsAction 判断目标位置是否仍需要该流体/效果。
     */
    record UseItemOnBlock(
        BlockPos targetPos,
        BlockState desiredState,
        Interaction interaction,
        Item requiredItem,
        SneakPolicy sneakPolicy,
        HandPolicy handPolicy,
        Predicate<BlockState> stillNeedsAction
    ) implements ActionPlan {}

    /**
     * UseItemInAir - 纯物品使用动作
     *
     * 语义：手持物品执行 interactItem，不依赖方块表面 hit。
     * 例如：未来可能支持的 ender pearl、fire charge 等。
     *
     * 注意：
     * - interaction 和 targetPos 仍然存在（用于旋转和任务定位），但交互不走 interactBlock。
     * - 协议层走 ClientPlayerInteractionManager.interactItem(...)，与 interactBlock 是不同入口。
     * - 当前无 behavior 使用此动作类型，预留以补齐动作谱系。
     */
    record UseItemInAir(
        BlockPos targetPos,
        BlockState desiredState,
        Interaction interaction,
        Item requiredItem,
        SneakPolicy sneakPolicy,
        HandPolicy handPolicy,
        Predicate<BlockState> stillNeedsAction
    ) implements ActionPlan {}
}
