package meteordevelopment.meteorclient.utils.printer;

import net.minecraft.block.Block;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.state.property.Properties;

/**
 * ResolverRegistry - 策略注册表
 *
 * 管理不同方块类型对应的放置策略。
 * 这是整个规则引擎的入口点，通过方块类型获取对应的 PlacementResolver。
 *
 * 策略定义采用声明式风格：
 * - 每个策略是一个 Source + Filter + HitVec 的组合
 * - 策略通过取并集（Source）和交集（Filter）来决定可行方向
 *
 * 支持的方块类型：
 * 1. 半砖（Slab）：特殊的垂直支撑规则 + 异种半砖检查
 * 2. 楼梯（Stairs）：正置/倒置的垂直支撑规则
 * 3. 轴向方块（Axis）：原木、柱子等根据轴向选择方向
 * 4. 默认方块：六个方向均可
 *
 * 使用示例：
 * <pre>
 * PlacementResolver resolver = ResolverRegistry.get(blockState.getBlock());
 * PlacementOption opt = resolver.resolve(context);
 * Vec3d hitVec = resolver.calculateHitVec(context, opt);
 * </pre>
 */
public final class ResolverRegistry {

    private ResolverRegistry() {} // 禁止实例化

    // ==================== 预定义策略 ====================

    /**
     * 半砖放置策略
     *
     * 来源（并集）：
     * - 半砖自我补全（双层半砖优先尝试补全）
     * - 所有水平方向（东南西北）
     * - 半砖特定垂直支撑（BOTTOM->DOWN, TOP->UP）
     *
     * 过滤（交集）：
     * - 邻居可点击
     * - 限制 Self 操作只针对半砖
     * - 异种半砖检查（防止 BOTTOM 靠 TOP）
     * - 半砖垂直面检查
     * - NCP 方向检查（严格模式）
     * - 视线检查（如果启用）
     *
     * 点击位置：半砖专用（根据 TOP/BOTTOM 调整 Y 偏移）
     */
    public static final PlacementResolver SLAB_RESOLVER = PlacementResolver.create("slab")
        // 来源：自我补全 + 水平 + 垂直支撑
        .addSource(Rules.SLAB_SELF_COMPLETE)
        .addSource(Rules.ALL_HORIZONTAL)
        .addSource(Rules.SLAB_VERTICAL_SUPPORT)
        // 过滤：基础检查 + 半砖特殊检查
        .addFilter(Rules.CLICKABLE_NEIGHBOR)
        .addFilter(Rules.VALID_SELF_TARGET) // 确保不乱点自己
        .addFilter(Rules.NO_MISMATCHED_SLABS)
        .addFilter(Rules.SLAB_VERTICAL_FACE)
        .addFilter(Rules.NCP_STRICT)
        .addFilter(Rules.LINE_OF_SIGHT)
        // 点击位置
        .hitVec(Rules.SLAB);

    /**
     * 楼梯放置策略
     *
     * 来源（并集）：
     * - 所有水平方向（楼梯可以依附任何实体方块侧面）
     * - 楼梯特定垂直支撑（BOTTOM->DOWN, TOP->UP）
     *
     * 过滤（交集）：
     * - 邻居可点击
     * - 楼梯水平邻居半砖检查（防止 BOTTOM 靠 TOP 半砖）
     * - NCP 方向检查
     * - 视线检查
     *
     * 点击位置：楼梯专用（根据正置/倒置调整 Y 偏移）
     */
    public static final PlacementResolver STAIR_RESOLVER = PlacementResolver.create("stair")
        // 来源：水平 + 垂直支撑
        .addSource(Rules.ALL_HORIZONTAL)
        .addSource(Rules.STAIR_VERTICAL_SUPPORT)
        // 过滤：基础检查 + 楼梯特殊检查
        .addFilter(Rules.CLICKABLE_NEIGHBOR)
        .addFilter(Rules.NO_MISMATCHED_STAIR_SLAB)
        .addFilter(Rules.NCP_STRICT)
        .addFilter(Rules.LINE_OF_SIGHT)
        // 点击位置
        .hitVec(Rules.STAIR);

    /**
     * 轴向方块放置策略（原木、柱子、干草块、锁链等）
     *
     * 来源：
     * - 轴向特定方向（X轴->东西, Y轴->上下, Z轴->南北）
     *
     * 过滤：
     * - 邻居可点击
     * - NCP 方向检查
     * - 视线检查
     *
     * 点击位置：中心点击
     */
    public static final PlacementResolver AXIS_RESOLVER = PlacementResolver.create("axis")
        // 来源：轴向特定
        .addSource(Rules.AXIS_SPECIFIC)
        // 过滤：基础检查
        .addFilter(Rules.CLICKABLE_NEIGHBOR)
        .addFilter(Rules.NCP_STRICT)
        .addFilter(Rules.LINE_OF_SIGHT)
        // 点击位置
        .hitVec(Rules.CENTER);

    /**
     * 默认方块放置策略
     *
     * 来源：
     * - 所有六个方向
     *
     * 过滤：
     * - 邻居可点击
     * - NCP 方向检查
     * - 视线检查
     *
     * 点击位置：中心点击
     */
    public static final PlacementResolver DEFAULT_RESOLVER = PlacementResolver.create("default")
        // 来源：所有方向
        .addSource(Rules.ALL_DIRECTIONS)
        // 过滤：基础检查
        .addFilter(Rules.CLICKABLE_NEIGHBOR)
        .addFilter(Rules.NCP_STRICT)
        .addFilter(Rules.LINE_OF_SIGHT)
        // 点击位置
        .hitVec(Rules.CENTER);

    // ==================== 策略获取方法 ====================

    /**
     * 根据方块类型获取对应的放置策略
     *
     * @param block 方块类型
     * @return 对应的 PlacementResolver
     */
    public static PlacementResolver get(Block block) {
        // 1. 半砖
        if (block instanceof SlabBlock) {
            return SLAB_RESOLVER;
        }

        // 2. 楼梯
        if (block instanceof StairsBlock) {
            return STAIR_RESOLVER;
        }

        // 3. 轴向方块（通过默认状态检查是否有 AXIS 属性）
        if (block.getDefaultState().contains(Properties.AXIS)) {
            return AXIS_RESOLVER;
        }

        // 4. 默认
        return DEFAULT_RESOLVER;
    }

    /**
     * 根据方块状态获取对应的放置策略
     * 可以更精确地判断方块类型
     *
     * @param state 方块状态
     * @return 对应的 PlacementResolver
     */
    public static PlacementResolver get(net.minecraft.block.BlockState state) {
        Block block = state.getBlock();

        // 1. 半砖
        if (block instanceof SlabBlock) {
            return SLAB_RESOLVER;
        }

        // 2. 楼梯
        if (block instanceof StairsBlock) {
            return STAIR_RESOLVER;
        }

        // 3. 轴向方块
        if (state.contains(Properties.AXIS)) {
            return AXIS_RESOLVER;
        }

        // 4. 默认
        return DEFAULT_RESOLVER;
    }

    // ==================== 双层半砖特殊处理 ====================

    /**
     * 获取双层半砖的实际放置策略
     *
     * 双层半砖不是一步到位的，需要根据当前世界状态决定放置哪一半：
     * - 如果当前是空气：按 BOTTOM 策略放置（开始建造）
     * - 如果当前是 BOTTOM：按 TOP 策略放置（补全）
     * - 如果当前是 TOP：按 BOTTOM 策略放置（补全）
     *
     * @param ctx 放置上下文
     * @return 调整后的上下文（修改了 targetState）
     */
    public static PlacementContext adjustForDoubleSlab(PlacementContext ctx) {
        if (!ctx.hasProperty(SlabBlock.TYPE)) return ctx;

        SlabType targetType = ctx.getProperty(SlabBlock.TYPE);
        if (targetType != SlabType.DOUBLE) return ctx;

        // 检查当前世界状态
        var currentState = ctx.currentState();
        if (!(currentState.getBlock() instanceof SlabBlock)) {
            // 当前是空气或其他，按 BOTTOM 放置
            return ctx.withTargetState(ctx.targetState().with(SlabBlock.TYPE, SlabType.BOTTOM));
        }

        if (!currentState.contains(SlabBlock.TYPE)) return ctx;

        SlabType currentType = currentState.get(SlabBlock.TYPE);
        if (currentType == SlabType.DOUBLE) {
            // 已经是双层，不需要放置
            return ctx;
        }

        // 根据当前类型决定要放置的类型
        SlabType neededType = (currentType == SlabType.BOTTOM) ? SlabType.TOP : SlabType.BOTTOM;
        return ctx.withTargetState(ctx.targetState().with(SlabBlock.TYPE, neededType));
    }

    // ==================== 便捷解析方法 ====================

    /**
     * 一站式解析方法：自动选择策略并解析方向
     *
     * 特殊处理双层半砖在空气位置的情况：
     * - 当目标是双层且当前是空气时，同时尝试 BOTTOM 和 TOP
     * - 这样能最大化放置成功率（两种方向都可以开始建造）
     *
     * @param ctx 放置上下文
     * @return 最佳放置选项，如果无法放置则返回 null
     */
    public static PlacementOption resolve(PlacementContext ctx) {
        // 对于双层半砖在空气位置，同时尝试两种放置方式
        if (ctx.hasProperty(SlabBlock.TYPE) &&
            ctx.getProperty(SlabBlock.TYPE) == SlabType.DOUBLE &&
            !(ctx.currentState().getBlock() instanceof SlabBlock)) {

            // 先尝试放 BOTTOM
            PlacementContext bottomCtx = ctx.withTargetState(ctx.targetState().with(SlabBlock.TYPE, SlabType.BOTTOM));
            PlacementResolver resolver = get(bottomCtx.targetState());
            PlacementOption result = resolver.resolve(bottomCtx);
            if (result != null) {
                return result;
            }

            // 再尝试放 TOP
            PlacementContext topCtx = ctx.withTargetState(ctx.targetState().with(SlabBlock.TYPE, SlabType.TOP));
            resolver = get(topCtx.targetState());
            result = resolver.resolve(topCtx);
            if (result != null) {
                return result;
            }

            // 两种都不行则返回 null
            return null;
        }

        // 处理其他双层半砖情况（当前已经是 BOTTOM 或 TOP）
        PlacementContext adjustedCtx = adjustForDoubleSlab(ctx);

        // 获取策略并解析
        PlacementResolver resolver = get(adjustedCtx.targetState());
        return resolver.resolve(adjustedCtx);
    }

    /**
     * 检查是否可以放置
     *
     * 特殊处理双层半砖在空气位置的情况：
     * - 只要 BOTTOM 或 TOP 之一可以放置，就返回 true
     *
     * @param ctx 放置上下文
     * @return true 表示存在可行的放置方向
     */
    public static boolean canPlace(PlacementContext ctx) {
        // 对于双层半砖在空气位置，检查两种放置方式是否至少有一种可行
        if (ctx.hasProperty(SlabBlock.TYPE) &&
            ctx.getProperty(SlabBlock.TYPE) == SlabType.DOUBLE &&
            !(ctx.currentState().getBlock() instanceof SlabBlock)) {

            // 检查放 BOTTOM 是否可行
            PlacementContext bottomCtx = ctx.withTargetState(ctx.targetState().with(SlabBlock.TYPE, SlabType.BOTTOM));
            PlacementResolver resolver = get(bottomCtx.targetState());
            if (resolver.canResolve(bottomCtx)) {
                return true;
            }

            // 检查放 TOP 是否可行
            PlacementContext topCtx = ctx.withTargetState(ctx.targetState().with(SlabBlock.TYPE, SlabType.TOP));
            resolver = get(topCtx.targetState());
            return resolver.canResolve(topCtx);
        }

        // 处理其他双层半砖情况
        PlacementContext adjustedCtx = adjustForDoubleSlab(ctx);
        PlacementResolver resolver = get(adjustedCtx.targetState());
        return resolver.canResolve(adjustedCtx);
    }
}