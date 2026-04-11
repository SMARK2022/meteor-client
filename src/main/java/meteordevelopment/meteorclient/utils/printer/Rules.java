package meteordevelopment.meteorclient.utils.printer;

import net.minecraft.block.*;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.block.enums.Orientation; // 必须导入

import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.util.math.MathHelper;

import meteordevelopment.meteorclient.utils.printer.PlacementResolver.CandidateSource;
import meteordevelopment.meteorclient.utils.printer.PlacementResolver.CandidateFilter;
import meteordevelopment.meteorclient.utils.player.Rotations; // 确保这个也在

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
     *
     * [修复] 不再检查 targetState，而是直接检查当前世界状态
     */
    public static final CandidateSource SLAB_SELF_COMPLETE = ctx -> {
        // 检查当前世界中是否已经有单层半砖
        var current = ctx.world().getBlockState(ctx.targetPos());
        if (!(current.getBlock() instanceof SlabBlock)) return Stream.empty();
        if (!current.contains(SlabBlock.TYPE)) return Stream.empty();

        SlabType currentType = current.get(SlabBlock.TYPE);

        // 如果已经是双层，不需要补全
        if (currentType == SlabType.DOUBLE) return Stream.empty();

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

    // ==================== 活板门专用逻辑 ====================

    /**
     * 来源：活板门放置方向
     * 活板门的放置逻辑非常特殊，分为两种情况：
     * 1. 依靠侧面放置（Wall Placement）：
     * - 必须寻找背后的墙。例如 Trapdoor FACING=NORTH，意味着它贴在 SOUTH 面的墙上。
     * - 此时 HALF 由点击位置决定。
     * 2. 依靠地面/天花板放置（Floor/Ceiling Placement）：
     * - 此时 FACING 由玩家视线决定（我们无法通过点击面改变 FACING）。
     * - 此时 HALF 由点击面决定（点地=BOTTOM, 点天=TOP）。
     * * 我们的策略：优先尝试"贴墙放"（因为这样可以精确控制 FACING 和 HALF），
     * 如果不行，再尝试"平放"（此时需要配合玩家旋转）。
     */
    public static final CandidateSource TRAPDOOR_SUPPORT = ctx -> {
        if (!ctx.hasProperty(TrapdoorBlock.FACING) || !ctx.hasProperty(TrapdoorBlock.HALF)) {
            return Stream.empty();
        }

        Direction facing = ctx.getProperty(TrapdoorBlock.FACING);
        BlockHalf half = ctx.getProperty(TrapdoorBlock.HALF);

        // 策略 A: 贴墙放置 (最稳健)
        // 如果活板门朝北 (NORTH)，说明它的铰链在南边，它依附在南边的方块上。
        // 我们需要点击南边方块的北面。
        Direction wallDirection = facing.getOpposite();

        // 策略 B: 地面/天花板放置
        // 如果 HALF=BOTTOM，可以放在地面 (DOWN)
        // 如果 HALF=TOP，可以放在天花板 (UP)
        // 注意：这种放置方式下，FACING 取决于玩家 Yaw，Printer 需要在 executePlacement 时处理旋转
        Direction verticalDir = (half == BlockHalf.BOTTOM) ? Direction.DOWN : Direction.UP;

        return Stream.of(
                PlacementOption.neighbor(wallDirection), // 优先找墙
                PlacementOption.neighbor(verticalDir) // 其次找地面/天花板
        );
    };

    // ==================== 漏斗专用逻辑 ====================

    /**
     * 来源：漏斗放置方向
     * 规则：
     * 1. 如果目标漏斗朝下 (DOWN)：可以点击任何方块的顶面/底面，或者点击下方方块的顶面。
     * 2. 如果目标漏斗朝侧面 (e.g. NORTH)：必须点击北边那个邻居的 SOUTH 面（即把嘴插进北边的方块里）。
     * 注意：漏斗不能朝上 (UP)。
     */
    public static final CandidateSource HOPPER_SUPPORT = ctx -> {
        if (!ctx.hasProperty(HopperBlock.FACING))
            return Stream.empty();
        Direction facing = ctx.getProperty(HopperBlock.FACING);

        // 漏斗嘴不能朝上
        if (facing == Direction.UP)
            return Stream.empty();

        // 情况 A: 漏斗朝下
        // 只要不点击侧面让它变成侧向，点哪里都会默认朝下。
        // 但为了稳定性，我们优先找"上方"或"下方"的邻居，点击它们的垂直面。
        if (facing == Direction.DOWN) {
            return Stream.of(Direction.UP, Direction.DOWN)
                    .map(PlacementOption::neighbor);
        }

        // 情况 B: 漏斗朝向侧面 (e.g. NORTH)
        // 必须依靠在该方向的邻居上。
        // 比如：漏斗要朝北，必须依靠在北边的方块上。
        return Stream.of(PlacementOption.neighbor(facing));
    };

    // ==================== 延伸类 (潜影盒/末地烛) 专用逻辑 ====================

    /**
     * 来源：点击面决定朝向 (Click Face Dependent)
     * 适用：Shulker Box, End Rod, Lightning Rod
     * 规则：目标朝向哪里，就必须去点击那个方向的邻居的相反面。
     * 例如：要放一个朝上的 End Rod，必须点击下方方块的 UP 面。
     */
    public static final CandidateSource FACE_DEPENDENT_SUPPORT = ctx -> {
        // 尝试获取 FACING 属性
        Direction facing = null;
        if (ctx.hasProperty(Properties.FACING))
            facing = ctx.getProperty(Properties.FACING);
        else if (ctx.hasProperty(Properties.HOPPER_FACING))
            facing = ctx.getProperty(Properties.HOPPER_FACING); // 兼容性

        if (facing == null)
            return Stream.empty();

        // 逻辑：如果我要方块朝向 NORTH，我必须依附在 SOUTH 边的方块上（点击它的 NORTH 面）
        // 错了！潜影盒/末地烛的逻辑是：点击面 = 朝向。
        // 所以：如果我要方块朝向 NORTH，我必须找到 SOUTH 边的邻居，点击它的 NORTH 面。
        // 等等，仔细思考：
        // 潜影盒：点击地面(UP) -> 朝上(UP)。
        // 也就是：TargetFacing = ClickedFace。
        // 所以：如果目标是 UP，我需要 ClickedFace = UP。这意味着邻居在 DOWN。
        // 如果目标是 NORTH，我需要 ClickedFace = NORTH。这意味着邻居在 SOUTH。

        return Stream.of(PlacementOption.neighbor(facing.getOpposite()));
    };

    /**
     * 来源：仅水平延伸支持
     * 适用于：梯子、墙火把、墙告示牌、绊线钩等。
     * 逻辑：
     * 这些方块必须依附在墙上。
     * 如果目标是 FACING=NORTH（朝北），说明它背靠南边的墙。
     * 我们必须寻找 SOUTH 边的邻居，并点击它的 NORTH 面。
     * * 严禁生成 UP/DOWN 候选，防止放置成站立变种。
     */
    public static final CandidateSource HORIZONTAL_EXTEND_SUPPORT = ctx -> {
        // 尝试获取 FACING 属性
        // 大多数此类方块使用 Properties.HORIZONTAL_FACING
        if (!ctx.hasProperty(Properties.HORIZONTAL_FACING))
            return Stream.empty();

        Direction facing = ctx.getProperty(Properties.HORIZONTAL_FACING);

        // 逻辑：Facing = 点击面。
        // 要朝北 (NORTH)，必须点击邻居的 NORTH 面。
        // 邻居在哪里？在反方向 (SOUTH)。
        return Stream.of(PlacementOption.neighbor(facing.getOpposite()));
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
     * [修复] 检查当前世界状态而非目标状态
     */
    public static final CandidateFilter VALID_SELF_TARGET = (ctx, opt) -> {
        if (!opt.isSelf()) return true; // 邻居模式不检查这个

        // 检查当前位置的方块是否允许 Self 操作
        var current = ctx.world().getBlockState(ctx.targetPos());
        boolean isSlab = current.getBlock() instanceof SlabBlock;
        // boolean isWaterloggable = ...

        return isSlab;
    };

    /**
     * 过滤器：NCP 方向检查（严格模式）
     * 根据玩家视角位置限制允许的放置方向
     * 只在 strict 模式下生效
     *
     * 【升级】现在基于精确的 hitVec 进行判断，而不仅仅是方块中心。
     * 虽然 NCP 主要检查的是“点击面是否背对玩家”，但拥有精确坐标更保险。
     */
    public static final CandidateFilter NCP_STRICT = (ctx, opt) -> {
        if (!ctx.strict()) return true; // 非严格模式，全部通过

        // 如果 hitVec 已经计算过，使用精确坐标；否则回退到方块中心
        Vec3d targetPos = (opt.hitVec() != null) ? opt.hitVec() : ctx.targetCenter();
        Set<Direction> validDirs = BlockUtilHelper.getPlaceDirectionsNCP(ctx.eyePos(), targetPos);
        // 我们要点击的是 opt.getClickedFace() 面
        return validDirs.contains(opt.getClickedFace());
    };

    /**
     * 过滤器：视线检查（Line of Sight）
     * 确保玩家能够看到要点击的方块面
     * 只在 checkLos 启用时生效
     *
     * 【升级】使用 canSeeFacePoint（基于 OUTLINE ShapeType）替代 canSeePoint（COLLIDER）。
     * 这确保了铁轨、地毯、红石线等薄方块不会被错误地视为可穿透。
     * 同时验证击中的是正确的方块和正确的面。
     */
    public static final CandidateFilter LINE_OF_SIGHT = (ctx, opt) -> {
        if (!ctx.checkLos()) return true; // 未启用视线检查，全部通过

        // 使用基于 OUTLINE 的面可见性检查
        if (opt.hitVec() != null) {
            return BlockUtilHelper.canSeeFacePoint(
                opt.getInteractPos(ctx.targetPos()),
                opt.getClickedFace(),
                opt.hitVec(),
                ctx.world(),
                ctx.player(),
                ctx.targetPos()
            );
        }

        // 回退方案（理论上不应该走到这里，因为 Resolver 已经注入了 hitVec）
        BlockPos clickPos = opt.getInteractPos(ctx.targetPos());
        Direction face = opt.getClickedFace();
        Vec3d faceCenter = Vec3d.ofCenter(clickPos).add(
            face.getOffsetX() * 0.5,
            face.getOffsetY() * 0.5,
            face.getOffsetZ() * 0.5
        );
        return BlockUtilHelper.canSeePoint(faceCenter, ctx.world(), ctx.player());
    };

    /**
     * [新增] 过滤器：Reach 距离检查
     * 确保玩家能够到达要点击的位置（基于 hitVec 而不仅仅是方块中心）
     *
     * 【关键改进】Minecraft 的交互距离是基于 hitVec（点击位置）而不是方块中心。
     * 使用 Printer 中配置的 maxReach 值（从 placeRange 传入）。
     *
     * 计算公式：
     * - maxReach = ctx.maxReach()（从 Printer 的 placeRange 设置传入）
     * - reachDistance = eyePos.distanceTo(hitVec)
     * - 检查：reachDistance <= maxReach + 0.1（加缓冲应对浮点数精度）
     *
     * @param ctx 放置上下文（包含 maxReach）
     * @param opt 候选放置方向
     * @return true 表示在reach范围内，false 表示超出范围
     */
    public static final CandidateFilter REACH_CHECK = (ctx, opt) -> {
        // 获取 hitVec（点击位置），这是由各个 HitVecCalculator 计算的
        Vec3d hitVec = opt.hitVec();
        if (hitVec == null) {
            // 如果 hitVec 未计算，则回退到方块中心
            hitVec = Vec3d.ofCenter(opt.getInteractPos(ctx.targetPos()));
        }

        // 计算从眼部到点击位置的距离
        double reachDistance = ctx.eyePos().distanceTo(hitVec);

        // 使用 PlacementContext 中传入的 maxReach 值（来自 Printer 的 placeRange 设置）
        // 加上小缓冲（0.1）以应对浮点数精度问题
        double maxReach = ctx.maxReach() + 0.1;

        return reachDistance <= maxReach;
    };

    /**
     * [通用过滤器] 垂直几何对齐检查
     * * 作用：防止因高度错位导致的放置失败。
     * 核心逻辑：确保"我需要的点击区域"在"邻居身上"是存在的实体。
     * * 支持方块：
     * - SlabBlock (半砖)
     * - StairsBlock (楼梯)
     * - TrapdoorBlock (活板门) [新增]
     * * 冲突场景：
     * 1. 我是下半截 (Bottom)，邻居是纯上半截 (Top Slab/Trapdoor) -> ❌ 邻居下半部是空的，无法点击
     * 2. 我是上半截 (Top)，邻居是纯下半截 (Bottom Slab/Trapdoor) -> ❌ 邻居上半部是空的
     */
    public static final CandidateFilter NO_MISMATCHED_ALIGNMENT = (ctx, opt) -> {
        // 1. Self 模式直接放行（这是补全操作，几何位置由 HitVec 保证）
        if (opt.isSelf())
            return true;

        // 2. 只检查水平方向（垂直方向依靠由其他 Filter 负责）
        if (!opt.direction().getAxis().isHorizontal())
            return true;

        // --- A. 分析"我"的几何形态 ---
        boolean iAmBottom = false;
        boolean iAmTop = false;

        // 检查半砖
        if (ctx.hasProperty(SlabBlock.TYPE)) {
            SlabType type = ctx.getProperty(SlabBlock.TYPE);
            iAmBottom = (type == SlabType.BOTTOM || type == SlabType.DOUBLE);
            iAmTop = (type == SlabType.TOP || type == SlabType.DOUBLE);
        }
        // 检查楼梯
        else if (ctx.hasProperty(StairsBlock.HALF)) {
            BlockHalf half = ctx.getProperty(StairsBlock.HALF);
            iAmBottom = (half == BlockHalf.BOTTOM);
            iAmTop = (half == BlockHalf.TOP);
        }
        // [新增] 检查活板门
        else if (ctx.hasProperty(TrapdoorBlock.HALF)) {
            BlockHalf half = ctx.getProperty(TrapdoorBlock.HALF);
            iAmBottom = (half == BlockHalf.BOTTOM);
            iAmTop = (half == BlockHalf.TOP);
        } else {
            // 如果我是普通方块（如石头），我需要完整的侧面吗？
            // 通常普通方块可以依附在半砖上，只要 HitVec 算得准。
            // 但为了稳妥，我们可以认为普通方块既需要 Top 也需要 Bottom 的支撑
            // iAmBottom = true; iAmTop = true;
            // 暂时保持宽松策略：非半截方块不检查对齐
            return true;
        }

        // --- B. 分析"邻居"的几何缺陷 ---
        // 我们只关心邻居是不是"纯粹的另一半"，如果是，那就无法依附。

        BlockPos neighborPos = opt.getInteractPos(ctx.targetPos());
        var neighborState = ctx.world().getBlockState(neighborPos);
        Block neighborBlock = neighborState.getBlock();

        boolean neighborIsPureTop = false;
        boolean neighborIsPureBottom = false;

        // 检查邻居半砖
        if (neighborBlock instanceof SlabBlock && neighborState.contains(SlabBlock.TYPE)) {
            SlabType t = neighborState.get(SlabBlock.TYPE);
            neighborIsPureTop = (t == SlabType.TOP); // 只有上，下是空
            neighborIsPureBottom = (t == SlabType.BOTTOM); // 只有下，上是空
        }
        // [新增] 检查邻居活板门 (活板门是很薄的，错位绝对点不到)
        else if (neighborBlock instanceof TrapdoorBlock && neighborState.contains(TrapdoorBlock.HALF)) {
            // 注意：活板门如果是 OPEN 的，它的碰撞箱会贴在侧面，这会让情况变复杂。
            // 但无论是否 Open，它的 Top/Bottom 属性决定了它在 Y 轴上的主体位置。
            BlockHalf h = neighborState.get(TrapdoorBlock.HALF);
            neighborIsPureTop = (h == BlockHalf.TOP);
            neighborIsPureBottom = (h == BlockHalf.BOTTOM);
        }
        // (可选) 检查邻居楼梯：楼梯背面是完整的，但正面是缺的。
        // 为了最大兼容性，通常认为楼梯是"足够厚"的，暂不将其标记为 PureTop/Bottom。

        // --- C. 判定冲突 ---

        // 如果我需要在下方依附，但邻居下方是空的 -> 冲突
        if (iAmBottom && neighborIsPureTop)
            return false;

        // 如果我需要在上方依附，但邻居上方是空的 -> 冲突
        if (iAmTop && neighborIsPureBottom)
            return false;

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
    // ==================== 辅助方法：计算玩家理论朝向 ====================

    /**
     * 计算玩家看向目标方块时的理论水平朝向
     * 解决 Printer 不自动旋转导致无法通过 getHorizontalFacing() 获取正确朝向的问题。
     */
    private static Direction getTheoreticalPlayerFacing(PlacementContext ctx) {
        // 1. 计算看向目标中心的 Yaw
        double yawToTarget = Rotations.getYaw(ctx.targetCenter());

        // 2. 将 Yaw 转换为标准的水平方向 [绝对兼容写法]
        // Minecraft Yaw: 0=South, 90=West, 180=North, 270=East
        int index = MathHelper.floor((yawToTarget / 90.0D) + 0.5D) & 3;

        return switch (index) {
            case 0 -> Direction.SOUTH;
            case 1 -> Direction.WEST;
            case 2 -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    // ==================== 3D 视线计算辅助 ====================

    /**
     * 计算玩家看向目标时的 3D 朝向 (含 UP/DOWN)
     * 用于 6 轴方块 (Piston, Observer, Dropper)
     *
     * <p>Pitch 优先从 opt.hitVec() 计算——这与 Rotations 系统实际发送的
     * 角度一致。若 hitVec 不可用则回退到 targetCenter。
     */
    private static Direction getTheoreticalPlayerLookDirection(PlacementContext ctx, PlacementOption opt) {
        Vec3d eye = ctx.eyePos();
        Vec3d target = (opt != null && opt.hitVec() != null) ? opt.hitVec() : ctx.targetCenter();

        double dX = target.x - eye.x;
        double dY = target.y - eye.y;
        double dZ = target.z - eye.z;
        double dist = Math.sqrt(dX * dX + dZ * dZ);

        // 计算 Pitch (上下角度)
        // Minecraft Pitch: -90 (Up) to 90 (Down)
        double pitch = MathHelper.wrapDegrees((float) (-(MathHelper.atan2(dY, dist) * 57.2957763671875)));

        // 判定阈值：通常 +/- 45 度分界
        if (pitch < -45.0f)
            return Direction.UP;
        if (pitch > 45.0f)
            return Direction.DOWN;

        // 如果是水平，复用之前的水平计算逻辑
        return getTheoreticalPlayerFacing(ctx);
    }

    // ==================== 通用旋转过滤器 ====================

    /**
     * [通用过滤器] 检查：目标朝向 == 玩家视线方向 (Same)
     * 适用方块：楼梯 (Stairs)、活塞 (Piston)、侦测器 (Observer)、发射器 等
     * 逻辑：如果玩家看着北边，放出来的方块也是朝北的。
     */
    public static final CandidateFilter ROTATION_CHECK_SAME = (ctx, opt) -> {
        // 楼梯的 FACING 属性
        if (!ctx.hasProperty(StairsBlock.FACING))
            return true; // 通用兼容：如果不是楼梯则尝试其他属性或跳过
        // 注：如果是 Piston 等其他方块，这里需要适配属性 key，或者写成泛型

        Direction targetFacing = ctx.getProperty(StairsBlock.FACING);

        // 楼梯的放置不论点击哪个面（除了特殊的半截判定），其水平朝向总是由玩家视线决定
        Direction playerFacing = getTheoreticalPlayerFacing(ctx);

        return playerFacing == targetFacing;
    };

    /**
     * [通用过滤器] 检查：目标朝向 == 玩家视线反方向 (Opposite)
     * 适用方块：活板门 (平放时)、栅栏门、箱子、熔炉、梯子 (地面放置时) 等
     * 逻辑：如果玩家看着北边，放出来的方块是朝南的（背对玩家）。
     */
    public static final CandidateFilter ROTATION_CHECK_OPPOSITE = (ctx, opt) -> {
        // 这里以活板门为例，通用化时可修改为 ctx.getProperty(Properties.HORIZONTAL_FACING)
        if (!ctx.hasProperty(TrapdoorBlock.FACING))
            return true;

        Direction targetFacing = ctx.getProperty(TrapdoorBlock.FACING);
        Direction playerFacing = getTheoreticalPlayerFacing(ctx);

        return playerFacing.getOpposite() == targetFacing;
    };

    // ==================== 具体的方块过滤器实现 ====================

    /**
     * 活板门方向检查
     * 混合逻辑：
     * 1. 贴墙 (Horizontal Click) -> 由点击面决定 (Face Dependent)
     * 2. 平放 (Vertical Click) -> 由玩家视线反向决定 (Player Opposite)
     */
    public static final CandidateFilter TRAPDOOR_ROTATION_CHECK = (ctx, opt) -> {
        if (!ctx.hasProperty(TrapdoorBlock.FACING))
            return true;

        Direction targetFacing = ctx.getProperty(TrapdoorBlock.FACING);
        Direction clickedFace = opt.getClickedFace();

        // --- A. 贴墙放置 ---
        if (clickedFace.getAxis().isHorizontal()) {
            // 必须贴在正确的墙上 (例如目标朝北，必须贴在南面墙的北面上)
            return clickedFace == targetFacing;
        }

        // --- B. 平放 (地面/天花板) ---
        // 复用通用的反向检查
        return ROTATION_CHECK_OPPOSITE.test(ctx, opt);
    };

    /**
     * 过滤器：漏斗方向检查
     */
    public static final CandidateFilter HOPPER_CHECK = (ctx, opt) -> {
        if (!ctx.hasProperty(HopperBlock.FACING))
            return true;
        Direction targetFacing = ctx.getProperty(HopperBlock.FACING);
        Direction clickedFace = opt.getClickedFace();

        // 1. 如果目标是朝下
        if (targetFacing == Direction.DOWN) {
            // 只要不点击侧面即可（点击侧面会让漏斗横向）
            // 必须点击 UP 或 DOWN 面
            return clickedFace.getAxis().isVertical();
        }

        // 2. 如果目标是朝侧面 (e.g. NORTH)
        // 必须点击该方向邻居的相反面 (e.g. 点击北边方块的 SOUTH 面)
        // 也就是：点击的面必须与目标朝向相反
        return clickedFace == targetFacing.getOpposite();
    };

    /**
     * 过滤器：点击面一致性检查
     * 确保点击的面 (ClickedFace) 等于目标朝向 (TargetFacing)
     */
    public static final CandidateFilter FACE_DEPENDENT_CHECK = (ctx, opt) -> {
        Direction facing = null;
        if (ctx.hasProperty(Properties.FACING))
            facing = ctx.getProperty(Properties.FACING);

        if (facing == null)
            return true;

        // 规则简单粗暴：点击哪个面，方块就朝向哪个面
        return opt.getClickedFace() == facing;
    };

    /**
     * [新增] 过滤器：禁止点击地板 (UP 面)
     * 用于防止 WallTorch 等方块在点击地面时退化为 Standing 变种。
     * 允许点击侧面（正常贴墙）和天花板（DOWN 面）。
     */
    public static final CandidateFilter BAN_FLOOR_CLICK = (ctx, opt) -> {
        // 如果点击的是 UP 面 (即点击了地上的方块)，拒绝
        return opt.getClickedFace() != Direction.UP;
    };

    /**
     * [新增] 过滤器：仅允许水平面点击
     * 用于 WallTorch、WallRedstoneTorch、WallSign 等严格贴墙方块。
     *
     * 核心逻辑：
     * - 点击地板 (UP面) → 游戏会退化成 Standing 变种 → 拒绝
     * - 点击天花板 (DOWN面) → 游戏可能退化成 Standing 变种或放置失败 → 拒绝
     * - 只有点击侧面 (水平面) 才能正确放置墙上变种
     *
     * 这是对 BAN_FLOOR_CLICK 的加强版，BAN_FLOOR_CLICK 仅禁止 UP 面，
     * HORIZONTAL_CLICK_ONLY 同时禁止 UP 和 DOWN 面。
     */
    public static final CandidateFilter HORIZONTAL_CLICK_ONLY = (ctx, opt) -> {
        return opt.getClickedFace().getAxis().isHorizontal();
    };

    /**
     * [新增] 混合旋转检查 (墙面/视线)
     * 适用于 WallTorch, WallSign 等。
     * 逻辑：
     * 1. 如果点击侧面 (贴墙)：方向由点击面决定 (Face Dependent)。
     * 2. 如果点击垂直面 (天花板)：方向由玩家视线决定 (Player Rotation)。
     */
    public static final CandidateFilter WALL_DEGENERATE_ROTATION_CHECK = (ctx, opt) -> {
        // 尝试获取水平朝向属性
        if (!ctx.hasProperty(Properties.HORIZONTAL_FACING))
            return true;
        Direction targetFacing = ctx.getProperty(Properties.HORIZONTAL_FACING);

        Direction clickedFace = opt.getClickedFace();

        // 情况 A: 贴墙放置
        if (clickedFace.getAxis().isHorizontal()) {
            // 必须贴在正确的墙上 (点击面 == 目标朝向)
            return clickedFace == targetFacing;
        }

        // 情况 B: 点击天花板/地面 (虽然 BAN_FLOOR_CLICK 会过滤掉地面，但逻辑上通用)
        // 此时依赖玩家视线
        return ROTATION_CHECK_OPPOSITE.test(ctx, opt);
    };

    // ==================== 6 轴旋转过滤器 ====================

    /**
     * [通用] 6轴同向检查
     * 适用：发射器、投掷器、侦测器
     * 逻辑：TargetFacing == PlayerLook (视线往哪看，口就朝哪开)
     */
    public static final CandidateFilter ROTATION_CHECK_6_SAME = (ctx, opt) -> {
        if (!ctx.hasProperty(Properties.FACING))
            return true;
        Direction target = ctx.getProperty(Properties.FACING);
        Direction playerLook = getTheoreticalPlayerLookDirection(ctx, opt);
        return target == playerLook;
    };

    /**
     * [通用] 6轴反向检查
     * 适用：活塞 (Piston)
     * 逻辑：TargetFacing == PlayerLook.Opposite
     * (例如：你向下看(Look=Down)，活塞头朝上(Facing=Up))
     */
    public static final CandidateFilter ROTATION_CHECK_6_OPPOSITE = (ctx, opt) -> {
        if (!ctx.hasProperty(Properties.FACING))
            return true;
        Direction target = ctx.getProperty(Properties.FACING);
        Direction playerLook = getTheoreticalPlayerLookDirection(ctx, opt);
        return target == playerLook.getOpposite();
    };

    // ==================== 附着面 (FaceAttached) 复杂逻辑 ====================

    /**
     * 附着面方块检查 (拉杆、按钮、砂轮)
     * 属性：FACE (Floor/Wall/Ceiling) + FACING (Horizontal)
     * * 逻辑矩阵：
     * 1. FACE = FLOOR:
     * - 必须点击 UP 面。
     * - FACING 由玩家水平视线决定 (通常是 Same 或 Opposite，视方块而定)。
     * (注：拉杆/按钮通常是 Same，即点地时，顶端指向视线方向)
     * 2. FACE = CEILING:
     * - 必须点击 DOWN 面。
     * - FACING 由玩家水平视线决定。
     * 3. FACE = WALL:
     * - 必须点击侧面。
     * - FACING 必须等于 ClickedFace (贴墙逻辑)。
     */
    public static final CandidateFilter FACE_ATTACHED_CHECK = (ctx, opt) -> {
        // 必须拥有两个属性
        if (!ctx.hasProperty(Properties.BLOCK_FACE) ||
                !ctx.hasProperty(Properties.HORIZONTAL_FACING))
            return true;

        var targetFace = ctx.getProperty(Properties.BLOCK_FACE);
        var targetFacing = ctx.getProperty(Properties.HORIZONTAL_FACING);
        var clickedFace = opt.getClickedFace();

        switch (targetFace) {
            case FLOOR -> {
                // 必须点地板 (UP)
                if (clickedFace != Direction.UP)
                    return false;
                // 检查水平朝向 (拉杆/按钮点地时，FACING = 玩家视线)
                // 注意：这里可能因方块而异，如果不准，可能需要拆分 Same/Opposite
                // 大多数 FaceAttachedBlock 是 "Same" (如拉杆柄朝向玩家视线)
                return getTheoreticalPlayerFacing(ctx) == targetFacing;
            }
            case CEILING -> {
                // 必须点天花板 (DOWN)
                if (clickedFace != Direction.DOWN)
                    return false;
                // 检查水平朝向
                return getTheoreticalPlayerFacing(ctx) == targetFacing;
            }
            case WALL -> {
                // 必须点击侧面
                if (!clickedFace.getAxis().isHorizontal())
                    return false;
                // 贴墙逻辑：点击面必须等于目标朝向
                return clickedFace == targetFacing;
            }
        }
        return true;
    };

    /**
     * 合成器 (Crafter) 专用检查
     * 属性：ORIENTATION (包含 Facing 和 Rotation)
     * * 逻辑：
     * 1. 检查主朝向 (Facing): 必须与玩家 3D 视线相反 (Piston Logic)。
     * 2. 检查顶部朝向 (Rotation):
     * - 如果主朝向是水平的：顶部必须是 UP (Minecraft 强制)。
     * - 如果主朝向是垂直的 (UP/DOWN)：顶部必须指向玩家 (Player Horizontal Opposite)。
     */
    public static final CandidateFilter CRAFTER_CHECK = (ctx, opt) -> {
        if (!ctx.hasProperty(Properties.ORIENTATION))
            return true;

        // 1. 解析目标状态
        Orientation targetOrientation = ctx.getProperty(Properties.ORIENTATION);
        Direction targetFacing = targetOrientation.getFacing(); // 喷口朝向
        Direction targetTop = targetOrientation.getRotation(); // 顶部材质朝向

        // 2. 检查主朝向 (Facing) - 必须背对玩家视线
        // 例如：目标朝 UP，玩家必须看 DOWN
        Direction playerLook3D = getTheoreticalPlayerLookDirection(ctx, opt);
        if (targetFacing != playerLook3D.getOpposite()) {
            return false;
        }

        // 3. 检查顶部朝向 (Rotation)
        if (targetFacing.getAxis().isVertical()) {
            // [垂直模式] (UP_EAST, DOWN_NORTH 等)
            // 规则：顶部材质朝向 = 玩家水平视线的反方向 (即顶部指向玩家)
            // 我们需要计算玩家水平看着哪里
            Direction playerHorizontal = getTheoreticalPlayerFacing(ctx);

            // 目标顶部朝向 必须等于 玩家水平朝向的相反方向
            // 例如：Target=UP_EAST (顶朝东)，意味着玩家站在东边面向西放置，或者站在西边面向东？
            // 原版逻辑：放置在地上时，Top 指向玩家。
            // 所以如果 Top=EAST，玩家应该在 WEST 看着 EAST 吗？不对。
            // 正确逻辑：Top=EAST，意味着玩家面向 WEST (视线)，Top (EAST) 指向玩家背部?
            // 让我们用最稳的 Opposite 逻辑：Top Points to Player.
            // Player Facing (Look) = WEST -> Top = EAST.
            return targetTop == playerHorizontal.getOpposite();
        } else {
            // [水平模式] (EAST_UP, NORTH_UP 等)
            // 规则：顶部必须朝上 (UP)。这是 Minecraft 强制的。
            // 只要目标状态是合法的 (例如没有 EAST_DOWN)，这一步通常自动通过。
            // 但为了严谨，我们检查一下 Schematic 是否合法。
            return targetTop == Direction.UP;
        }
    };

    /**
     * [核心新增] 放置合法性检查
     * 调用 Minecraft 原生的 canPlaceAt 逻辑，检查目标位置是否允许存在该方块。
     * 解决：红石粉、地毯、中继器等悬空放置被服务器拒绝的问题。
     * * 逻辑：
     * 即使我们可以点击邻居的侧面，但如果目标位置本身不满足生存条件（例如底部无支撑），
     * canPlaceAt 会返回 false，从而过滤掉这个无效方案。
     */
    public static final CandidateFilter PLACEABILITY_CHECK = (ctx, opt) -> {
        // 使用目标状态和位置，询问世界：我能放在这吗？
        return ctx.targetState().canPlaceAt(ctx.world(), ctx.targetPos());
    };

    /**
     * [新增] 过滤器：高度合规性检查 (HEIGHT_COMPLIANCE_CHECK)
     *
     * 作用：验证计算出的 hitVec 的相对高度是否能形成预期的半砖/楼梯顶底状态。
     *
     * 核心逻辑：
     * - TOP 半砖/楼梯：hitVec.y - targetPos.y 必须 > 0.5（表示点击在上半部分）
     * - BOTTOM 半砖/楼梯：hitVec.y - targetPos.y 必须 <= 0.5（表示点击在下半部分）
     *
     * 应用场景：防止"点击矮方块导致无法形成特定朝向"的失败。
     * 例如：地板上的按钮（高度仅 0.1），无论如何都无法通过侧面点击形成 TOP 半砖。
     */
    public static final CandidateFilter HEIGHT_COMPLIANCE_CHECK = (ctx, opt) -> {
        // 1. 获取点击位置（由 HitVecCalculator 注入）
        Vec3d hitVec = opt.hitVec();
        if (hitVec == null) {
            // 如果尚未计算 hitVec，暂时放行（会在后续 Resolver 中被计算）
            return true;
        }

        // 2. 计算相对高度（相对于目标方块坐标）
        double relativeY = hitVec.getY() - ctx.targetPos().getY();

        // 3. 只针对有 TOP/BOTTOM 属性的方块进行检查
        boolean isSlabOrStairOrTrapdoor =
            ctx.hasProperty(SlabBlock.TYPE) ||
            ctx.hasProperty(StairsBlock.HALF) ||
            ctx.hasProperty(TrapdoorBlock.HALF);

        if (!isSlabOrStairOrTrapdoor) {
            // 普通方块无特殊高度要求
            return true;
        }

        // 4. 根据方块类型检查高度
        if (ctx.hasProperty(SlabBlock.TYPE)) {
            SlabType type = ctx.getProperty(SlabBlock.TYPE);
            if (type == SlabType.TOP) {
                // TOP 半砖必须在上半部分
                return relativeY > 0.5;
            } else if (type == SlabType.BOTTOM) {
                // BOTTOM 半砖必须在下半部分
                return relativeY <= 0.5;
            }
            // DOUBLE 类型无特殊高度要求
            return true;
        }

        if (ctx.hasProperty(StairsBlock.HALF)) {
            BlockHalf half = ctx.getProperty(StairsBlock.HALF);
            if (half == BlockHalf.TOP) {
                return relativeY > 0.5;
            } else {
                return relativeY <= 0.5;
            }
        }

        if (ctx.hasProperty(TrapdoorBlock.HALF)) {
            BlockHalf half = ctx.getProperty(TrapdoorBlock.HALF);
            if (half == BlockHalf.TOP) {
                return relativeY > 0.5;
            } else {
                return relativeY <= 0.5;
            }
        }

        return true;
    };

    // ==================== 多格方块扩展检查 ====================

    /**
     * 过滤器：门放置扩展检查 (DOOR_EXPANSION_CHECK)
     *
     * 门是两格高方块，放置时：
     * - lower 锚点需要下方有实心支撑（已由 canPlaceAt 检查）
     * - upper（锚点上方一格）必须可替换（空气/流体等）
     *
     * 原版 DoorBlock.getPlacementState() 中的检查：
     *   blockPos.getY() < world.getTopYInclusive() &&
     *   world.getBlockState(blockPos.up()).canReplace(ctx)
     *
     * 此 filter 模拟该检查，确保上方一格不被占用。
     */
    public static final CandidateFilter DOOR_EXPANSION_CHECK = (ctx, opt) -> {
        BlockPos targetPos = ctx.targetPos();
        BlockPos upperPos = targetPos.up();

        // 检查高度上限
        if (targetPos.getY() >= ctx.world().getTopYInclusive()) return false;

        // 检查上方一格是否可替换（空气、流体等可被覆盖的方块）
        BlockState upperState = ctx.world().getBlockState(upperPos);
        return upperState.isReplaceable();
    };

    /**
     * 过滤器：床放置扩展检查 (BED_EXPANSION_CHECK)
     *
     * 床是两格长方块，放置时：
     * - foot 在选中位置（placementPos）
     * - head 在玩家朝向（FACING 属性）前方一格
     * - head 位置必须可替换
     *
     * 原版 BedBlock.getPlacementState() 中的检查：
     *   world.getBlockState(blockPos2).canReplace(ctx) &&
     *   world.getWorldBorder().contains(blockPos2)
     * 其中 blockPos2 = blockPos.offset(playerFacing)
     *
     * 此 filter 模拟该检查，确保 head 位置不被占用。
     * 注意：FACING 属性指向床头方向，即玩家放置时的朝向。
     */
    public static final CandidateFilter BED_EXPANSION_CHECK = (ctx, opt) -> {
        if (!ctx.hasProperty(BedBlock.FACING)) return true;

        Direction facing = ctx.getProperty(BedBlock.FACING);
        BlockPos headPos = ctx.targetPos().offset(facing);

        // 检查 head 位置是否可替换
        BlockState headState = ctx.world().getBlockState(headPos);
        if (!headState.isReplaceable()) return false;

        // 检查世界边界（原版也做了这个检查）
        return ctx.world().getWorldBorder().contains(headPos);
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
        return HitVecCalculator.getShapeHitVec(ctx.world(), pos, face);
    };


    /**
     * 计算器：半砖点击位置
     * 根据目标类型（TOP/BOTTOM）在邻居形状的最佳位置进行点击
     *
     * [升级逻辑]
     * - Self 模式：直接点中心（双层补全）
     * - TOP 半砖：期望高度 0.8（尽量靠上）
     * - BOTTOM 半砖：期望高度 0.2（尽量靠下）
     * - 垂直点击：直接用形状中心
     *
     * getExtremeHitVec 会尝试达到期望高度，如果邻居太矮会自动吸附到最高点。
     * 后续 HEIGHT_COMPLIANCE_CHECK 会检查是否达标。
     */
    public static final HitVecCalculator SLAB = (ctx, opt) -> {
        BlockPos pos = opt.getInteractPos(ctx.targetPos());
        Direction face = opt.getClickedFace();

        // Self 模式：点击自身，用中心即可
        if (opt.isSelf()) {
            return HitVecCalculator.getShapeHitVec(ctx.world(), pos, face);
        }

        // 没有 Slab.TYPE 属性的方块，回退到通用计算
        if (!ctx.hasProperty(SlabBlock.TYPE)) {
            return CENTER.calculate(ctx, opt);
        }

        SlabType type = ctx.getProperty(SlabBlock.TYPE);

        // 垂直点击（UP/DOWN）：直接用形状中心
        if (face.getAxis().isVertical()) {
            return HitVecCalculator.getShapeHitVec(ctx.world(), pos, face);
        }

        // 水平点击（侧面）：根据目标类型选择期望高度
        // TOP 半砖期望在 0.8，BOTTOM 半砖期望在 0.2
        double desiredHeight = (type == SlabType.TOP) ? 0.8 : 0.2;
        return HitVecCalculator.getExtremeHitVec(ctx.world(), pos, face, desiredHeight);
    };

    /**
     * 计算器：楼梯点击位置
     * 根据目标朝向（TOP/BOTTOM）在邻居形状的最佳位置进行点击
     */
    public static final HitVecCalculator STAIR = (ctx, opt) -> {
        BlockPos pos = opt.getInteractPos(ctx.targetPos());
        Direction face = opt.getClickedFace();

        if (!ctx.hasProperty(StairsBlock.HALF)) {
            return CENTER.calculate(ctx, opt);
        }

        BlockHalf half = ctx.getProperty(StairsBlock.HALF);

        // 水平点击：根据 HALF 选择期望高度
        if (face.getAxis().isHorizontal()) {
            double desiredHeight = (half == BlockHalf.TOP) ? 0.8 : 0.2;
            return HitVecCalculator.getExtremeHitVec(ctx.world(), pos, face, desiredHeight);
        }

        // 垂直点击：用形状中心
        return HitVecCalculator.getShapeHitVec(ctx.world(), pos, face);
    };

        /**
     * 计算器：活板门点击位置
     * 根据 HALF 属性在邻居形状的最佳位置进行点击
     *
     * 核心逻辑：
     * 1. 侧面点击：根据 HALF 选择期望高度（0.8 或 0.2）
     * 2. 垂直点击（地板/天花板）：用形状中心
     */
    public static final HitVecCalculator TRAPDOOR = (ctx, opt) -> {
        BlockPos pos = opt.getInteractPos(ctx.targetPos());
        Direction face = opt.getClickedFace();

        if (!ctx.hasProperty(TrapdoorBlock.HALF)) {
            return CENTER.calculate(ctx, opt);
        }

        BlockHalf half = ctx.getProperty(TrapdoorBlock.HALF);

        // 侧面点击：根据 HALF 选择期望高度
        if (face.getAxis().isHorizontal()) {
            double desiredHeight = (half == BlockHalf.TOP) ? 0.8 : 0.2;
            return HitVecCalculator.getExtremeHitVec(ctx.world(), pos, face, desiredHeight);
        }

        // 垂直点击：用形状中心
        return HitVecCalculator.getShapeHitVec(ctx.world(), pos, face);
    };
}