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
 * 1. 从所有 Source 获取候选方向（取并集）
 * 2. 去重
 * 3. 依次应用所有 Filter（取交集）
 * 4. 返回第一个通过所有检查的方向
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
 * Direction dir = resolver.resolve(context);
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
     * 解析最佳放置方向
     *
     * 执行流程：
     * 1. 合并所有 Source 的候选方向（并集）
     * 2. 去重
     * 3. 应用所有 Filter（交集）
     * 4. 返回第一个通过的方向
     *
     * @param ctx 放置上下文
     * @return 最佳方向，如果没有可行方向则返回 null
     */
    public Direction resolve(PlacementContext ctx) {
        return resolveOptional(ctx).orElse(null);
    }

    /**
     * 解析最佳放置方向（Optional 版本）
     */
    public Optional<Direction> resolveOptional(PlacementContext ctx) {
        return getCandidateStream(ctx)
            .filter(dir -> passAllFilters(ctx, dir))
            .findFirst();
    }

    /**
     * 获取所有通过过滤的方向列表
     * 用于需要多个候选的场景
     */
    public List<Direction> resolveAll(PlacementContext ctx) {
        return getCandidateStream(ctx)
            .filter(dir -> passAllFilters(ctx, dir))
            .toList();
    }

    /**
     * 检查是否存在至少一个可行方向
     */
    public boolean canResolve(PlacementContext ctx) {
        return resolveOptional(ctx).isPresent();
    }

    // ==================== HitVec 计算 ====================

    /**
     * 计算点击位置
     *
     * @param ctx         放置上下文
     * @param neighborPos 邻居方块位置
     * @param clickedSide 点击的面
     * @return 精确的点击坐标
     */
    public Vec3d calculateHitVec(PlacementContext ctx, BlockPos neighborPos, Direction clickedSide) {
        return hitVecCalculator.calculate(ctx, neighborPos, clickedSide);
    }

    /**
     * 根据解析结果计算点击位置
     *
     * @param ctx 放置上下文
     * @param dir 解析得到的方向
     * @return 精确的点击坐标
     */
    public Vec3d calculateHitVec(PlacementContext ctx, Direction dir) {
        BlockPos neighborPos = ctx.neighborPos(dir);
        Direction clickedSide = dir.getOpposite();
        return calculateHitVec(ctx, neighborPos, clickedSide);
    }

    // ==================== 内部方法 ====================

    /**
     * 获取合并后的候选方向流（去重）
     */
    private Stream<Direction> getCandidateStream(PlacementContext ctx) {
        if (sources.isEmpty()) {
            return Stream.empty();
        }

        return sources.stream()
            .flatMap(source -> source.getCandidates(ctx))
            .distinct();
    }

    /**
     * 检查方向是否通过所有过滤器
     */
    private boolean passAllFilters(PlacementContext ctx, Direction dir) {
        for (CandidateFilter filter : filters) {
            if (!filter.test(ctx, dir)) {
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
