package meteordevelopment.meteorclient.utils.printer;

import net.minecraft.util.math.Direction;

import java.util.stream.Stream;

/**
 * CandidateSource - 候选方向来源接口（生产者）
 *
 * 定义了一种产生候选放置方向的策略。
 * 多个 Source 可以组合使用，通过取并集来扩大候选范围。
 *
 * 设计理念：
 * - 单一职责：每个 Source 只负责产生一类候选方向
 * - 组合性：多个 Source 可以通过 flatMap 合并
 * - 惰性求值：使用 Stream 实现惰性计算，提高性能
 *
 * 使用示例：
 * <pre>
 * // 组合多个来源
 * Stream<Direction> allCandidates = Stream.of(source1, source2, source3)
 *     .flatMap(s -> s.getCandidates(ctx))
 *     .distinct();
 * </pre>
 */
@FunctionalInterface
public interface CandidateSource {

    /**
     * 获取候选方向流
     *
     * @param ctx 放置上下文，包含所有环境信息
     * @return 候选放置选项的 Stream（可能为空）
     */
    Stream<PlacementOption> getCandidates(PlacementContext ctx);

    /**
     * 组合两个 Source，返回两者候选的并集
     *
     * @param other 另一个来源
     * @return 组合后的新来源
     */
    default CandidateSource union(CandidateSource other) {
        return ctx -> Stream.concat(this.getCandidates(ctx), other.getCandidates(ctx));
    }

    /**
     * 创建一个空的 Source（不产生任何候选）
     */
    static CandidateSource empty() {
        return ctx -> Stream.empty();
    }

    /**
     * 从固定方向数组创建 Source (默认为 Neighbor 模式)
     *
     * @param directions 方向数组
     * @return 产生这些方向的 Source
     */
    static CandidateSource of(Direction... directions) {
        return ctx -> Stream.of(directions).map(PlacementOption::neighbor);
    }
}
