package meteordevelopment.meteorclient.utils.printer;

import net.minecraft.block.Block;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Set;
import java.util.stream.Stream;

/**
 * Rules - 规则定义库
 *
 * 包含所有可复用的 CandidateSource、CandidateFilter 和 HitVecCalculator 实现。
 * 这些规则像"积木"一样可以自由组合，构建不同方块类型的放置策略。
 *
 * 组织结构：
 * 1. Sources（来源）：产生候选方向
 *    - HORIZONTAL_CLICKABLES：水平方向可点击邻居
 *    - VERTICAL_CLICKABLES：垂直方向可点击邻居
 *    - SLAB_VERTICAL_SUPPORT：半砖特定垂直支撑
 *    - STAIR_VERTICAL_SUPPORT：楼梯特定垂直支撑
 *    - AXIS_SPECIFIC：轴向方块特定方向
 *
 * 2. Filters（过滤器）：剔除无效方向
 *    - CLICKABLE_NEIGHBOR：邻居可点击检查
 *    - NCP_STRICT：NCP方向检查
 *    - LINE_OF_SIGHT：视线检查
 *    - NO_MISMATCHED_SLABS：异种半砖检查
 *    - SLAB_VERTICAL_FACE：半砖垂直面检查
 *
 * 3. HitVecCalculators（点击位置）：计算精确点击坐标
 *    - CENTER：中心点击
 *    - SLAB：半砖专用
 *    - STAIR：楼梯专用
 */
public final class Rules {

    private Rules() {} // 禁止实例化

    // ==================== Sources (候选来源) ====================

    /**
     * 来源：所有水平方向（东南西北）
     * 仅产生方向，不做任何过滤
     */
    public static final CandidateSource ALL_HORIZONTAL = ctx ->
        Stream.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)
              .map(PlacementOption::neighbor);

    /**
     * 来源：所有垂直方向（上下）
     */
    public static final CandidateSource ALL_VERTICAL = ctx ->
        Stream.of(Direction.UP, Direction.DOWN)
              .map(PlacementOption::neighbor);

    /**
     * 来源：所有六个方向
     */
    public static final CandidateSource ALL_DIRECTIONS = ctx ->
        Stream.of(Direction.values())
              .map(PlacementOption::neighbor);

    /**
     * [新增] 半砖自我补全来源
     * 专门用于处理双层半砖。
     * - 如果当前是 BOTTOM，产生 Self(UP) -> 点击顶面
     * - 如果当前是 TOP，产生 Self(DOWN) -> 点击底面
     */
    public static final CandidateSource SLAB_SELF_COMPLETE = ctx -> {
        // 必须要是双层半砖的目标，且当前方块已经是单层半砖
        if (!ctx.hasProperty(SlabBlock.TYPE)) return Stream.empty();
        if (ctx.getProperty(SlabBlock.TYPE) != SlabType.DOUBLE) return Stream.empty();

        var current = ctx.world().getBlockState(ctx.targetPos());
        if (!(current.getBlock() instanceof SlabBlock)) return Stream.empty();
        
        SlabType currentType = current.get(SlabBlock.TYPE);

        if (currentType == SlabType.BOTTOM) {
            // 当前是下半，补上半 -> 点击自身的 UP 面
            return Stream.of(PlacementOption.self(Direction.UP));
        }
        if (currentType == SlabType.TOP) {
            // 当前是上半，补下半 -> 点击自身的 DOWN 面
            return Stream.of(PlacementOption.self(Direction.DOWN));
        }
        
        return Stream.empty();
    };

    /**
     * [未来扩展] 含水方块来源
     * 如果目标含水，尝试点击自身的 UP 面 (通常倒水都是点上面)
     */
    public static final CandidateSource WATERLOG_SELF = ctx -> {
        // 假设 context 中有判断是否需要放水桶的逻辑
        // return Stream.of(PlacementOption.self(Direction.UP));
        return Stream.empty(); 
    };

    /**
     * 来源：半砖垂直支撑方向
     * 根据半砖类型（TOP/BOTTOM）返回对应的垂直方向
     * - BOTTOM 半砖：只能从下方 (DOWN) 获得支撑
     * - TOP 半砖：只能从上方 (UP) 获得支撑
     * - DOUBLE 半砖：返回空（由外部逻辑处理，通常走 SLAB_SELF_COMPLETE 或 NEIGHBOR）
     */
    public static final CandidateSource SLAB_VERTICAL_SUPPORT = ctx -> {
        if (!ctx.hasProperty(SlabBlock.TYPE)) return Stream.empty();

        SlabType type = ctx.getProperty(SlabBlock.TYPE);
        if (type == SlabType.DOUBLE) return Stream.empty();

        // BOTTOM -> 依靠下方 (点击下方的 UP 面)
        // TOP -> 依靠上方 (点击上方的 DOWN 面)
        Direction dir = (type == SlabType.BOTTOM) ? Direction.DOWN : Direction.UP;
        return Stream.of(PlacementOption.neighbor(dir));
    };

    /**
     * 来源：楼梯垂直支撑方向
     * 根据楼梯朝向（正置/倒置）返回对应的垂直方向
     * - BOTTOM（正置）：从下方 (DOWN) 获得支撑
     * - TOP（倒置）：从上方 (UP) 获得支撑
     */
    public static final CandidateSource STAIR_VERTICAL_SUPPORT = ctx -> {
        if (!ctx.hasProperty(StairsBlock.HALF)) return Stream.empty();

        BlockHalf half = ctx.getProperty(StairsBlock.HALF);
        Direction dir = (half == BlockHalf.BOTTOM) ? Direction.DOWN : Direction.UP;
        return Stream.of(PlacementOption.neighbor(dir));
    };

    /**
     * 来源：轴向方块特定方向
     * 根据目标轴向返回对应的放置方向
     * - X轴：东/西方向
     * - Y轴：上/下方向
     * - Z轴：南/北方向
     */
    public static final CandidateSource AXIS_SPECIFIC = ctx -> {
        if (!ctx.hasProperty(Properties.AXIS)) return Stream.empty();

        Direction.Axis axis = ctx.getProperty(Properties.AXIS);
        return switch (axis) {
            case X -> Stream.of(Direction.EAST, Direction.WEST).map(PlacementOption::neighbor);
            case Z -> Stream.of(Direction.NORTH, Direction.SOUTH).map(PlacementOption::neighbor);
            default -> Stream.of(Direction.UP, Direction.DOWN).map(PlacementOption::neighbor); // Y轴
        };
    };

    // ==================== Filters (过滤器) ====================

    /**
     * 过滤器：邻居方块可点击检查
     * 确保指定方向的邻居是可以被点击的实体方块
     * 自动适配 Neighbor 和 Self 模式
     */
    public static final CandidateFilter CLICKABLE_NEIGHBOR = (ctx, opt) -> {
        // 获取我们要点击的那个方块（可能是邻居，也可能是自己）
        BlockPos clickPos = opt.getInteractPos(ctx.targetPos());
        var clickState = ctx.world().getBlockState(clickPos);
        
        // 只要这个方块有轮廓，就可以点
        return BlockUtilHelper.isClickable(clickState, ctx.world(), clickPos);
    };
    
    /**
     * [新增] 限制 Self 操作只针对半砖/特定方块
     * 防止普通方块错误地尝试点自己
     */
    public static final CandidateFilter VALID_SELF_TARGET = (ctx, opt) -> {
        if (!opt.isSelf()) return true; // 邻居模式不检查这个

        // 只有特定的方块允许 Self 操作
        boolean isSlab = ctx.targetState().getBlock() instanceof SlabBlock;
        // boolean isWaterloggable = ...
        
        return isSlab; 
    };

    /**
     * 过滤器：NCP 方向检查（严格模式）
     * 根据玩家视角位置限制允许的放置方向
     * 只在 strict 模式下生效
     */
    public static final CandidateFilter NCP_STRICT = (ctx, opt) -> {
        if (!ctx.strict()) return true; // 非严格模式，全部通过

        Set<Direction> validDirs = BlockUtilHelper.getPlaceDirectionsNCP(ctx.eyePos(), ctx.targetCenter());
        // 我们要点击的是 opt.getClickedFace() 面
        return validDirs.contains(opt.getClickedFace());
    };

    /**
     * 过滤器：视线检查（Line of Sight）
     * 确保玩家能够看到要点击的方块面
     * 只在 checkLos 启用时生效
     */
    public static final CandidateFilter LINE_OF_SIGHT = (ctx, opt) -> {
        if (!ctx.checkLos()) return true; // 未启用视线检查，全部通过

        BlockPos clickPos = opt.getInteractPos(ctx.targetPos());
        Direction face = opt.getClickedFace();
        
        return BlockUtilHelper.canSeeBlock(clickPos, face, ctx.world(), ctx.player());
    };

    /**
     * 过滤器：拒绝异种半砖支撑
     * 防止 BOTTOM 半砖依靠 TOP 半砖（它们之间有空隙）
     * 双层半砖视为完整方块，允许依靠
     */
    public static final CandidateFilter NO_MISMATCHED_SLABS = (ctx, opt) -> {
        // 1. 如果是点自己 (Self)，说明正在进行合法的补全操作，直接放行
        if (opt.isSelf()) return true;
        
        // 只对半砖生效
        if (!ctx.hasProperty(SlabBlock.TYPE)) return true;

        // 只检查水平方向（垂直方向由专门的 Filter 处理）
        if (!opt.direction().getAxis().isHorizontal()) return true;

        SlabType myType = ctx.getProperty(SlabBlock.TYPE);
        if (myType == SlabType.DOUBLE) return true; // 双层半砖不挑剔

        // 检查邻居
        BlockPos neighborPos = opt.getInteractPos(ctx.targetPos());
        var neighbor = ctx.world().getBlockState(neighborPos);
        if (!(neighbor.getBlock() instanceof SlabBlock)) return true;
        if (!neighbor.contains(SlabBlock.TYPE)) return true;

        SlabType neighborType = neighbor.get(SlabBlock.TYPE);
        if (neighborType == SlabType.DOUBLE) return true; // 双层半砖可以支撑任何

        // 单层半砖必须类型一致
        return neighborType == myType;
    };

    /**
     * 过滤器：楼梯水平邻居半砖检查
     * 防止楼梯依靠不匹配的半砖（它们之间有空隙）
     * - BOTTOM（正置）楼梯：不能依靠 TOP 单层半砖（上半部分悬空）
     * - TOP（倒置）楼梯：不能依靠 BOTTOM 单层半砖（下半部分悬空）
     * 双层半砖视为完整方块，允许依靠
     */
    public static final CandidateFilter NO_MISMATCHED_STAIR_SLAB = (ctx, opt) -> {
        // 1. Self 模式暂不适用于楼梯（除非将来有楼梯补全），放行
        if (opt.isSelf()) return true;

        // 只对楼梯生效
        if (!ctx.hasProperty(StairsBlock.HALF)) return true;

        // 只检查水平方向
        if (!opt.direction().getAxis().isHorizontal()) return true;

        BlockHalf myHalf = ctx.getProperty(StairsBlock.HALF);

        // 检查邻居是否是半砖
        BlockPos neighborPos = opt.getInteractPos(ctx.targetPos());
        var neighbor = ctx.world().getBlockState(neighborPos);
        if (!(neighbor.getBlock() instanceof SlabBlock)) return true;
        if (!neighbor.contains(SlabBlock.TYPE)) return true;

        SlabType neighborType = neighbor.get(SlabBlock.TYPE);
        if (neighborType == SlabType.DOUBLE) return true; // 双层半砖可以支撑任何

        // BOTTOM（正置）楼梯占据下半空间 (0~0.5)
        // 如果邻居是 TOP 半砖 (0.5~1)，它们之间有空隙，不能依靠
        if (myHalf == BlockHalf.BOTTOM && neighborType == SlabType.TOP) {
            return false;
        }

        // TOP（倒置）楼梯占据上半空间 (0.5~1)
        // 如果邻居是 BOTTOM 半砖 (0~0.5)，它们之间有空隙，不能依靠
        if (myHalf == BlockHalf.TOP && neighborType == SlabType.BOTTOM) {
            return false;
        }

        return true;
    };

    /**
     * 过滤器：半砖垂直面检查
     * 确保垂直方向的邻居提供了有效的接触面
     * - 放置 BOTTOM 半砖：下方邻居必须有顶面（不能是 BOTTOM 单层半砖）
     * - 放置 TOP 半砖：上方邻居必须有底面（不能是 TOP 单层半砖）
     */
    public static final CandidateFilter SLAB_VERTICAL_FACE = (ctx, opt) -> {
        if (opt.isSelf()) return true;
        
        // 只对半砖生效
        if (!ctx.hasProperty(SlabBlock.TYPE)) return true;

        // 只检查垂直方向
        if (opt.direction().getAxis().isHorizontal()) return true;

        SlabType myType = ctx.getProperty(SlabBlock.TYPE);
        if (myType == SlabType.DOUBLE) return true;

        BlockPos neighborPos = opt.getInteractPos(ctx.targetPos());
        var neighbor = ctx.world().getBlockState(neighborPos);
        if (!(neighbor.getBlock() instanceof SlabBlock)) return true;
        if (!neighbor.contains(SlabBlock.TYPE)) return true;

        SlabType neighborType = neighbor.get(SlabBlock.TYPE);
        if (neighborType == SlabType.DOUBLE) return true;

        // BOTTOM 半砖从 DOWN 方向依靠：下方不能是 BOTTOM（没有顶面）
        if (myType == SlabType.BOTTOM && opt.direction() == Direction.DOWN) {
            return neighborType != SlabType.BOTTOM;
        }

        // TOP 半砖从 UP 方向依靠：上方不能是 TOP（没有底面）
        if (myType == SlabType.TOP && opt.direction() == Direction.UP) {
            return neighborType != SlabType.TOP;
        }

        return true;
    };

    // ==================== HitVecCalculators (点击位置计算器) ====================

    /**
     * 计算器：中心点击
     * 点击邻居方块指定面的中心位置
     * 适用于大多数普通方块
     */
    public static final HitVecCalculator CENTER = (ctx, opt) -> {
        BlockPos pos = opt.getInteractPos(ctx.targetPos());
        Direction face = opt.getClickedFace();
        return HitVecCalculator.getHitVec(pos, face);
    };

    /**
     * 计算器：半砖点击位置
     * 水平面点击时根据目标类型（TOP/BOTTOM）调整 Y 坐标偏移
     * 垂直面点击时使用中心
     *
     * 注意：ctx 中的 targetState 应该已经由 ResolverRegistry 调整为单层（BOTTOM/TOP）
     * 不处理双层（DOUBLE）的逻辑，那由 ResolverRegistry.resolve() 负责
     */
    public static final HitVecCalculator SLAB = (ctx, opt) -> {
        BlockPos pos = opt.getInteractPos(ctx.targetPos());
        
        // [修复] 如果是 Self 模式（双层补全），直接点中心
        // 对于半砖补全，无论是从下补上(UP面)，还是从上补下(DOWN面)，交界处都在 0.5 (即中心)
        // 使用通用逻辑会算到 y=1.0 或 y=0.0，那是空气位置
        if (opt.isSelf()) {
            return Vec3d.ofCenter(pos);
        }
        
        Direction face = opt.getClickedFace();
        
        if (!ctx.hasProperty(SlabBlock.TYPE)) {
            return CENTER.calculate(ctx, opt);
        }

        SlabType type = ctx.getProperty(SlabBlock.TYPE);
        return HitVecCalculator.getHitVecForSlab(pos, face, type);
    };

    /**
     * 计算器：楼梯点击位置
     * 水平面点击时根据目标朝向（正置/倒置）调整 Y 坐标偏移
     * 垂直面点击时使用中心
     */
    public static final HitVecCalculator STAIR = (ctx, opt) -> {
        BlockPos pos = opt.getInteractPos(ctx.targetPos());
        Direction face = opt.getClickedFace();
        
        if (!ctx.hasProperty(StairsBlock.HALF)) {
            return CENTER.calculate(ctx, opt);
        }

        BlockHalf half = ctx.getProperty(StairsBlock.HALF);
        return HitVecCalculator.getHitVecForStairs(pos, face, half);
    };

    // ==================== 组合过滤器（便捷方法） ====================

    /**
     * 创建基础过滤器链：可点击 + NCP + 视线
     * 这是所有方块类型共用的基础检查
     */
    public static CandidateFilter baseFilters() {
        return CLICKABLE_NEIGHBOR.and(NCP_STRICT).and(LINE_OF_SIGHT);
    }

    /**
     * 创建半砖专用过滤器链
     */
    public static CandidateFilter slabFilters() {
        return baseFilters().and(NO_MISMATCHED_SLABS).and(SLAB_VERTICAL_FACE).and(VALID_SELF_TARGET);
    }

    /**
     * 创建楼梯专用过滤器链（与基础相同，楼梯不挑剔水平邻居）
     */
    public static CandidateFilter stairFilters() {
        return baseFilters();
    }

    /**
     * 创建轴向方块专用过滤器链（与基础相同）
     */
    public static CandidateFilter axisFilters() {
        return baseFilters();
    }
}