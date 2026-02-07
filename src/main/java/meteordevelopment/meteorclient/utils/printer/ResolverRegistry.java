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
 * Direction dir = resolver.resolve(context);
 * Vec3d hitVec = resolver.calculateHitVec(context, dir);
 * </pre>
 */
public final class ResolverRegistry {

    private ResolverRegistry() {} // 禁止实例化

    // ==================== 预定义策略 ====================

    /**
     * 半砖放置策略
     *
     * 来源（并集）：
     * - 所有水平方向（东南西北）
     * - 半砖特定垂直支撑（BOTTOM->DOWN, TOP->UP）
     *
     * 过滤（交集）：
     * - 邻居可点击
     * - 异种半砖检查（防止 BOTTOM 靠 TOP）
     * - 半砖垂直面检查
     * - NCP 方向检查（严格模式）
     * - 视线检查（如果启用）
     *
     * 点击位置：半砖专用（根据 TOP/BOTTOM 调整 Y 偏移）
     */
    public static final PlacementResolver SLAB_RESOLVER = PlacementResolver.create("slab")
        // 来源：水平 + 垂直支撑
        .addSource(Rules.ALL_HORIZONTAL)
        .addSource(Rules.SLAB_VERTICAL_SUPPORT)
        // 过滤：基础检查 + 半砖特殊检查
        .addFilter(Rules.CLICKABLE_NEIGHBOR)
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
     * @param ctx 放置上下文
     * @return 最佳放置方向，如果无法放置则返回 null
     */
    public static net.minecraft.util.math.Direction resolve(PlacementContext ctx) {
        // 处理双层半砖特殊情况
        PlacementContext adjustedCtx = adjustForDoubleSlab(ctx);

        // 获取策略并解析
        PlacementResolver resolver = get(adjustedCtx.targetState());
        return resolver.resolve(adjustedCtx);
    }

    /**
     * 检查是否可以放置
     *
     * @param ctx 放置上下文
     * @return true 表示存在可行的放置方向
     */
    public static boolean canPlace(PlacementContext ctx) {
        PlacementContext adjustedCtx = adjustForDoubleSlab(ctx);
        PlacementResolver resolver = get(adjustedCtx.targetState());
        return resolver.canResolve(adjustedCtx);
    }
}
