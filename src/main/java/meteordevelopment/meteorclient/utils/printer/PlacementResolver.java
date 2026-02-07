package meteordevelopment.meteorclient.utils.printer;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * PlacementResolver - 放置策略解析器（组装器）
 *
 * 将 Source、Filter、HitVecCalculator 组合成完整的放置策略。
 * 通过链式调用构建，实现声明式的逻辑定义。
 *
 * 核心执行流程：
 * 1. 从所有 Source 获取候选选项（取并集）
 * 2. 去重
 * 3. 依次应用所有 Filter（取交集）
 * 4. 返回第一个通过所有检查的选项
 *
 * 使用示例：
 * <pre>
 * PlacementResolver resolver = new PlacementResolver()
 *     .addSource(Rules.ALL_HORIZONTAL)
 *     .addSource(Rules.SLAB_VERTICAL_SUPPORT)
 *     .addFilter(Rules.CLICKABLE_NEIGHBOR)
 *     .addFilter(Rules.NO_MISMATCHED_SLABS)
 *     .addFilter(Rules.NCP_STRICT)
 *     .addFilter(Rules.LINE_OF_SIGHT)
 *     .hitVec(Rules.SLAB);
 *
 * PlacementOption opt = resolver.resolve(context);
 * </pre>
 */
public class PlacementResolver {

    /** 候选来源列表 */
    private final List<CandidateSource> sources = new ArrayList<>();

    /** 过滤器列表 */
    private final List<CandidateFilter> filters = new ArrayList<>();

    /** 点击位置计算器 */
    private HitVecCalculator hitVecCalculator = Rules.CENTER;

    /** 策略名称（用于调试） */
    private String name = "unnamed";

    // ==================== 构建器方法（链式调用） ====================

    /**
     * 添加候选来源
     */
    public PlacementResolver addSource(CandidateSource source) {
        this.sources.add(source);
        return this;
    }

    /**
     * 添加过滤器
     */
    public PlacementResolver addFilter(CandidateFilter filter) {
        this.filters.add(filter);
        return this;
    }

    /**
     * 设置点击位置计算器
     */
    public PlacementResolver hitVec(HitVecCalculator calculator) {
        this.hitVecCalculator = calculator;
        return this;
    }

    /**
     * 设置策略名称
     */
    public PlacementResolver name(String name) {
        this.name = name;
        return this;
    }

    // ==================== 核心解析方法 ====================

    /**
     * 解析最佳放置选项
     *
     * 执行流程：
     * 1. 合并所有 Source 的候选选项（并集）
     * 2. 去重
     * 3. 应用所有 Filter（交集）
     * 4. 返回第一个通过的选项
     *
     * @param ctx 放置上下文
     * @return 最佳选项，如果没有可行选项则返回 null
     */
    public PlacementOption resolve(PlacementContext ctx) {
        return resolveOptional(ctx).orElse(null);
    }

    /**
     * 解析最佳放置选项（Optional 版本）
     */
    public Optional<PlacementOption> resolveOptional(PlacementContext ctx) {
        return getCandidateStream(ctx)
            .filter(opt -> passAllFilters(ctx, opt))
            .findFirst();
    }

    /**
     * 获取所有通过过滤的选项列表
     * 用于需要多个候选的场景
     */
    public List<PlacementOption> resolveAll(PlacementContext ctx) {
        return getCandidateStream(ctx)
            .filter(opt -> passAllFilters(ctx, opt))
            .toList();
    }

    /**
     * 检查是否存在至少一个可行选项
     */
    public boolean canResolve(PlacementContext ctx) {
        return resolveOptional(ctx).isPresent();
    }

    // ==================== HitVec 计算 ====================

    /**
     * 计算点击位置
     *
     * @param ctx 放置上下文
     * @param opt 放置选项
     * @return 精确的点击坐标
     */
    public Vec3d calculateHitVec(PlacementContext ctx, PlacementOption opt) {
        return hitVecCalculator.calculate(ctx, opt);
    }

    /**
     * @deprecated 请使用 calculateHitVec(PlacementContext, PlacementOption)
     */
    @Deprecated
    public Vec3d calculateHitVec(PlacementContext ctx, BlockPos neighborPos, Direction clickedSide) {
        // 为了兼容性保留，但在新的架构下，最好总是使用 PlacementOption
        // 如果我们必须构造一个 PlacementOption...
        // 这里无法准确重建 isSelf，只能假设是 neighbor
        // 实际上这不应该被调用了，除非遗留代码
        return calculateHitVec(ctx, PlacementOption.neighbor(clickedSide.getOpposite()));
    }
    
    /**
     * @deprecated 请使用 calculateHitVec(PlacementContext, PlacementOption)
     */
    @Deprecated
    public Vec3d calculateHitVec(PlacementContext ctx, Direction dir) {
         return calculateHitVec(ctx, PlacementOption.neighbor(dir));
    }

    // ==================== 内部方法 ====================

    /**
     * 获取合并后的候选选项流（去重）
     */
    private Stream<PlacementOption> getCandidateStream(PlacementContext ctx) {
        if (sources.isEmpty()) {
            return Stream.empty();
        }

        return sources.stream()
            .flatMap(source -> source.getCandidates(ctx))
            .distinct();
    }

    /**
     * 检查选项是否通过所有过滤器
     */
    private boolean passAllFilters(PlacementContext ctx, PlacementOption opt) {
        for (CandidateFilter filter : filters) {
            if (!filter.test(ctx, opt)) {
                return false;
            }
        }
        return true;
    }

    // ==================== 调试与信息 ====================

    /**
     * 获取策略名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取策略描述
     */
    @Override
    public String toString() {
        return String.format("PlacementResolver[%s](sources=%d, filters=%d)",
            name, sources.size(), filters.size());
    }

    // ==================== 静态工厂方法 ====================

    /**
     * 创建一个新的空解析器
     */
    public static PlacementResolver create() {
        return new PlacementResolver();
    }

    /**
     * 创建一个带名称的空解析器
     */
    public static PlacementResolver create(String name) {
        return new PlacementResolver().name(name);
    }

    /**
     * 复制现有解析器（用于派生新策略）
     */
    public PlacementResolver copy() {
        PlacementResolver copy = new PlacementResolver();
        copy.sources.addAll(this.sources);
        copy.filters.addAll(this.filters);
        copy.hitVecCalculator = this.hitVecCalculator;
        copy.name = this.name + "_copy";
        return copy;
    }
}