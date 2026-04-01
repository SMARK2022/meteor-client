package meteordevelopment.meteorclient.utils.printer;

import net.minecraft.util.math.Direction;

/**
 * CandidateFilter - 候选方向过滤器接口（裁决者）
 *
 * 定义了一种判断候选方向是否合法的策略。
 * 多个 Filter 可以组合使用，通过取交集来缩小候选范围。
 *
 * 设计理念：
 * - 单一职责：每个 Filter 只负责一种验证逻辑
 * - 组合性：多个 Filter 可以通过 and() 链式组合
 * - 短路求值：一旦某个 Filter 返回 false，后续不再执行
 *
 * 使用示例：
 * <pre>
 * // 组合多个过滤器
 * CandidateFilter combined = filter1.and(filter2).and(filter3);
 * boolean valid = combined.test(ctx, direction);
 * </pre>
 */
@FunctionalInterface
public interface CandidateFilter {

    /**
     * 测试指定选项是否满足条件
     *
     * @param ctx 放置上下文
     * @param opt 待测试的放置选项
     * @return true 表示通过，false 表示被过滤掉
     */
    boolean test(PlacementContext ctx, PlacementOption opt);

    /**
     * 与另一个 Filter 组合（逻辑与）
     *
     * @param other 另一个过滤器
     * @return 组合后的新过滤器
     */
    default CandidateFilter and(CandidateFilter other) {
        return (ctx, opt) -> this.test(ctx, opt) && other.test(ctx, opt);
    }

    /**
     * 与另一个 Filter 组合（逻辑或）
     *
     * @param other 另一个过滤器
     * @return 组合后的新过滤器
     */
    default CandidateFilter or(CandidateFilter other) {
        return (ctx, opt) -> this.test(ctx, opt) || other.test(ctx, opt);
    }

    /**
     * 取反
     *
     * @return 逻辑取反后的过滤器
     */
    default CandidateFilter negate() {
        return (ctx, opt) -> !this.test(ctx, opt);
    }

    /**
     * 创建一个始终通过的 Filter
     */
    static CandidateFilter alwaysPass() {
        return (ctx, opt) -> true;
    }

    /**
     * 创建一个始终拒绝的 Filter
     */
    static CandidateFilter alwaysReject() {
        return (ctx, opt) -> false;
    }
}
