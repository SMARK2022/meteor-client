package meteordevelopment.meteorclient.utils.printer;

import meteordevelopment.meteorclient.utils.printer.PlacementOption;
import net.minecraft.block.*;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
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
 *
 * <pre>
 * PlacementResolver resolver = ResolverRegistry.get(blockState.getBlock());
 * PlacementOption opt = resolver.resolve(context);
 * Vec3d hitVec = resolver.calculateHitVec(context, opt);
 * </pre>
 */
public final class ResolverRegistry {

    private ResolverRegistry() {
    } // 禁止实例化

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
            .addFilter(Rules.NO_MISMATCHED_ALIGNMENT)
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
            .addFilter(Rules.NO_MISMATCHED_ALIGNMENT)
            .addFilter(Rules.ROTATION_CHECK_SAME)
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
     * 活板门放置策略
     */
    public static final PlacementResolver TRAPDOOR_RESOLVER = PlacementResolver.create("trapdoor")
            .addSource(Rules.TRAPDOOR_SUPPORT) // 之前的 Source
            .addFilter(Rules.CLICKABLE_NEIGHBOR)

            // [新增] 必须加上对齐检查，防止活板门试图依附在错位的半砖/活板门上
            .addFilter(Rules.NO_MISMATCHED_ALIGNMENT)

            .addFilter(Rules.TRAPDOOR_ROTATION_CHECK)
            .addFilter(Rules.NCP_STRICT)
            .addFilter(Rules.LINE_OF_SIGHT)
            .hitVec(Rules.TRAPDOOR); // 之前的 HitVec

    /**
     * 漏斗放置策略
     */
    public static final PlacementResolver HOPPER_RESOLVER = PlacementResolver.create("hopper")
            .addSource(Rules.HOPPER_SUPPORT)
            .addFilter(Rules.CLICKABLE_NEIGHBOR)
            .addFilter(Rules.HOPPER_CHECK)
            .addFilter(Rules.NCP_STRICT)
            .addFilter(Rules.LINE_OF_SIGHT)
            .hitVec(Rules.CENTER); // 漏斗点中心即可

    /**
     * 延伸方块策略 (潜影盒、末地烛)
     */
    public static final PlacementResolver FACE_EXTEND_RESOLVER = PlacementResolver.create("face_extend")
            .addSource(Rules.FACE_DEPENDENT_SUPPORT)
            .addFilter(Rules.CLICKABLE_NEIGHBOR)
            .addFilter(Rules.FACE_DEPENDENT_CHECK)
            .addFilter(Rules.NCP_STRICT)
            .addFilter(Rules.LINE_OF_SIGHT)
            .hitVec(Rules.CENTER); // 点中心即可

    /**
     * [新增] 严格贴墙策略 (梯子、绊线钩、可可豆)
     * 只能点击侧面，绝对不产生 UP/DOWN 候选。
     */
    public static final PlacementResolver PURE_WALL_RESOLVER = PlacementResolver.create("pure_wall")
            // 来源：只生成水平邻居的候选 (targetFacing 的反方向)
            .addSource(Rules.HORIZONTAL_EXTEND_SUPPORT)
            .addSource(Rules.ALL_VERTICAL)
            // 过滤：
            .addFilter(Rules.CLICKABLE_NEIGHBOR)
            .addFilter(Rules.WALL_DEGENERATE_ROTATION_CHECK) // 侧面看墙，顶面看人
            .addFilter(Rules.NCP_STRICT)
            .addFilter(Rules.LINE_OF_SIGHT)
            .hitVec(Rules.CENTER);

    /**
     * [新增] 地板退化策略 (墙火把、墙牌、墙珊瑚)
     * 允许点击侧面和天花板，但**严格禁止点击地板**。
     */
    public static final PlacementResolver WALL_DEGENERATE_RESOLVER = PlacementResolver.create("wall_degenerate")
            // 来源：水平支撑 (找墙) + 垂直支撑 (找天花板)
            // 注意：这里我们使用 union 组合两个 Source
            .addSource(Rules.HORIZONTAL_EXTEND_SUPPORT)
            .addSource(Rules.ALL_VERTICAL)
            // 过滤：
            .addFilter(Rules.CLICKABLE_NEIGHBOR)
            .addFilter(Rules.BAN_FLOOR_CLICK) // <--- 核心：禁止点地板，防止退化
            .addFilter(Rules.WALL_DEGENERATE_ROTATION_CHECK) // 侧面看墙，顶面看人
            .addFilter(Rules.NCP_STRICT)
            .addFilter(Rules.LINE_OF_SIGHT)
            .hitVec(Rules.CENTER);

            // ==================== 水平朝向策略 ====================

    /**
     * 水平反向策略 (Opposite)
     * 适用：箱子、熔炉、栅栏门、门、南瓜、织布机、中继器、比较器等。
     * 逻辑：
     * 1. 来源：六个方向均可点击 (ALL_DIRECTIONS)。
     * 2. 旋转：方块朝向 = 玩家视线反方向 (背对玩家)。
     */
    public static final PlacementResolver HORIZONTAL_OPPOSITE_RESOLVER = PlacementResolver.create("horizontal_opposite")
            .addSource(Rules.ALL_DIRECTIONS)
            .addFilter(Rules.CLICKABLE_NEIGHBOR)
            .addFilter(Rules.ROTATION_CHECK_OPPOSITE) // 核心：反向检查
            .addFilter(Rules.NCP_STRICT)
            .addFilter(Rules.LINE_OF_SIGHT)
            .hitVec(Rules.CENTER);

    /**
     * 水平同向策略 (Same)
     * 适用：铁砧、床等。
     * 逻辑：
     * 1. 来源：六个方向均可点击 (ALL_DIRECTIONS)。
     * (虽然中继器通常点地，但逻辑上允许尝试其他面，只要 canPlace 通过)
     * 2. 旋转：方块朝向 = 玩家视线方向 (同向)。
     */
    public static final PlacementResolver HORIZONTAL_SAME_RESOLVER = PlacementResolver.create("horizontal_same")
            .addSource(Rules.ALL_DIRECTIONS)
            .addFilter(Rules.CLICKABLE_NEIGHBOR)
            .addFilter(Rules.ROTATION_CHECK_SAME) // 核心：同向检查
            .addFilter(Rules.NCP_STRICT)
            .addFilter(Rules.LINE_OF_SIGHT)
            .hitVec(Rules.CENTER);

    /**
     * 6轴同向策略 (发射器、侦测器)
     */
    public static final PlacementResolver LOOK_6_SAME_RESOLVER = PlacementResolver.create("look_6_same")
            .addSource(Rules.ALL_DIRECTIONS)
            .addFilter(Rules.CLICKABLE_NEIGHBOR)
            .addFilter(Rules.ROTATION_CHECK_6_SAME) // 视线同向
            .addFilter(Rules.NCP_STRICT)
            .addFilter(Rules.LINE_OF_SIGHT)
            .hitVec(Rules.CENTER);

    /**
     * 6轴反向策略 (活塞)
     */
    public static final PlacementResolver LOOK_6_OPPOSITE_RESOLVER = PlacementResolver.create("look_6_opposite")
            .addSource(Rules.ALL_DIRECTIONS)
            .addFilter(Rules.CLICKABLE_NEIGHBOR)
            .addFilter(Rules.ROTATION_CHECK_6_OPPOSITE) // 视线反向
            .addFilter(Rules.NCP_STRICT)
            .addFilter(Rules.LINE_OF_SIGHT)
            .hitVec(Rules.CENTER);

    /**
     * 附着面策略 (拉杆、按钮) - 12种状态
     */
    public static final PlacementResolver FACE_ATTACHED_RESOLVER = PlacementResolver.create("face_attached")
            .addSource(Rules.ALL_DIRECTIONS)
            .addFilter(Rules.CLICKABLE_NEIGHBOR)
            .addFilter(Rules.FACE_ATTACHED_CHECK) // 核心复杂逻辑
            .addFilter(Rules.NCP_STRICT)
            .addFilter(Rules.LINE_OF_SIGHT)
            .hitVec(Rules.CENTER);


            /**
     * 合成器放置策略
     * 逻辑：6轴反向 + 垂直时的旋转判定
     */
    public static final PlacementResolver CRAFTER_RESOLVER = PlacementResolver.create("crafter")
            .addSource(Rules.ALL_DIRECTIONS)
            .addFilter(Rules.CLICKABLE_NEIGHBOR)
            .addFilter(Rules.CRAFTER_CHECK) // 核心专用检查
            .addFilter(Rules.NCP_STRICT)
            .addFilter(Rules.LINE_OF_SIGHT)
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

        // 在 get(Block block) 方法中添加：
        if (block instanceof TrapdoorBlock) {
            return TRAPDOOR_RESOLVER;
        }

        // 在 get(Block block) 中注册：
        if (block instanceof HopperBlock) {
            return HOPPER_RESOLVER;
        }
        if (block instanceof ShulkerBoxBlock || block instanceof EndRodBlock || block instanceof LightningRodBlock
                || block instanceof AmethystClusterBlock) {
            return FACE_EXTEND_RESOLVER;
        }

        // 1. 严格贴墙类 (Pure Wall)
        if (block instanceof LadderBlock
                || block instanceof TripwireHookBlock
                || block instanceof CocoaBlock) {
            return PURE_WALL_RESOLVER;
        }

        // 2. 地板退化类 (Degenerate on Floor)
        // 这些方块在墙上正常，点地板会变身，点天花板(可能)走视线逻辑
        if (block instanceof WallTorchBlock // 墙上火把
                || block instanceof WallSignBlock // 墙上告示牌
                || block instanceof WallBannerBlock // 墙上旗帜
                || block instanceof WallSkullBlock // 墙上头颅
                || block instanceof DeadCoralWallFanBlock // 墙上死珊瑚
                || block instanceof CoralWallFanBlock // 墙上活珊瑚
                // 1.20+ 新增
                || block instanceof WallHangingSignBlock) {
            return WALL_DEGENERATE_RESOLVER;
        }

        // ==================== 1. 水平反向类 (Opposite) ====================
        // 特征：FACING 属性，且放置时背对玩家 (Face towards player)
        if (block instanceof AbstractChestBlock // 箱子, 陷阱箱, 末影箱
                || block instanceof RepeaterBlock // 红石中继器
                || block instanceof ComparatorBlock // 红石比较器
                || block instanceof AbstractFurnaceBlock // 熔炉, 高炉, 烟熏炉
                || block instanceof FenceGateBlock // 栅栏门
                || block instanceof DoorBlock // 门 (虽有多重属性，但水平逻辑一致)
                || block instanceof CarvedPumpkinBlock // 雕刻南瓜, 南瓜灯 (Jack o Lantern)
                || block instanceof BeehiveBlock // 蜂箱, 蜂巢
                || block instanceof LoomBlock // 织布机
                || block instanceof BarrelBlock // 木桶 (注：木桶其实是6面的，但很多人当箱子用。如果你的木桶是6面逻辑，移到
                                                // FACE_EXTEND_RESOLVER；如果是强制水平，放这里。原版木桶是6面的，建议移走，或者这里只处理水平情况)
                // *修正*：原版 Barrel 是 6 面朝向 (Look-based)，不属于
                // HorizontalFacingBlock。它应该归类到"活塞/发射器"类(Look Based)，或者 FACE_EXTEND。
                // 这里我们先不放 Barrel。

                || block instanceof LecternBlock // 讲台
                || block instanceof StonecutterBlock // 切石机
                || block instanceof CampfireBlock // 营火
                || block instanceof GlazedTerracottaBlock // 带釉陶瓦
                || block instanceof DecoratedPotBlock // 饰纹陶罐 (1.20+)
                || block instanceof ChiseledBookshelfBlock // 雕纹书架 (1.20+)
        ) {
            return HORIZONTAL_OPPOSITE_RESOLVER;
        }

        // ==================== 2. 水平同向类 (Same) ====================
        // 特征：FACING 属性，且放置时面向玩家视线 (Face with player)
        if (block instanceof AnvilBlock // 铁砧 (所有损坏程度)
                || block instanceof BedBlock // 床
                || block instanceof GrindstoneBlock // 砂轮 (注：砂轮有 Wall/Floor/Ceiling 状态，但水平逻辑是 Same)
                || block instanceof BellBlock // 钟 (同上)
        ) {
            return HORIZONTAL_SAME_RESOLVER;
        }

        // ==================== 9. 六轴同向类 (Look 6 Same) ====================
        // 特征：6面 FACING，朝向 = 玩家视线
        if (block instanceof ObserverBlock // 侦测器 (输出端朝向玩家视线)
                || block instanceof CommandBlock // 命令方块
                || block instanceof BarrelBlock // 木桶 (通常是6轴 Look-based，虽然很多人以为是箱子)
        ) {
            return LOOK_6_SAME_RESOLVER;
        }

        // ==================== 10. 六轴反向类 (Look 6 Opposite) ====================
        // 特征：6面 FACING，朝向 = 玩家视线反向 (头对着玩家)
        if (block instanceof DispenserBlock // 发射器
                || block instanceof DropperBlock // 投掷器
                || block instanceof PistonBlock // 活塞 (普通 & 粘性)
        // 注：EndPortalFrame 是水平反向，已在 Horizontal_Opposite 处理
        ) {
            return LOOK_6_OPPOSITE_RESOLVER;
        }

        // ==================== 11. 附着面类 (Face Attached / 12-Direction)
        // ====================
        // 特征：有 FACE (Wall/Floor/Ceiling) 和 HORIZONTAL_FACING 属性
        if (block instanceof ButtonBlock // 所有按钮 (木/石/黑石/铜)
                || block instanceof LeverBlock // 拉杆
                || block instanceof GrindstoneBlock // 砂轮
                || block instanceof BellBlock // 钟 (注：钟的放置逻辑与此类似)
        // || block instanceof SwitchBlock // (如果模组有类似 Switch 的类)
        ) {
            return FACE_ATTACHED_RESOLVER;
        }

        // [新增] 合成器 (1.21+)
        if (block instanceof CrafterBlock) {
            return CRAFTER_RESOLVER;
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

        // 在 get(Block block) 方法中添加：
        if (block instanceof TrapdoorBlock) {
            return TRAPDOOR_RESOLVER;
        }

        // 在 get(Block block) 中注册：
        if (block instanceof HopperBlock) {
            return HOPPER_RESOLVER;
        }
        if (block instanceof ShulkerBoxBlock || block instanceof EndRodBlock || block instanceof LightningRodBlock
                || block instanceof AmethystClusterBlock) {
            return FACE_EXTEND_RESOLVER;
        }

        // 1. 严格贴墙类 (Pure Wall)
        if (block instanceof LadderBlock
                || block instanceof TripwireHookBlock
                || block instanceof CocoaBlock) {
            return PURE_WALL_RESOLVER;
        }

        // 2. 地板退化类 (Degenerate on Floor)
        // 这些方块在墙上正常，点地板会变身，点天花板(可能)走视线逻辑
        if (block instanceof WallTorchBlock // 墙上火把
                || block instanceof WallSignBlock // 墙上告示牌
                || block instanceof WallBannerBlock // 墙上旗帜
                || block instanceof WallSkullBlock // 墙上头颅
                || block instanceof DeadCoralWallFanBlock // 墙上死珊瑚
                || block instanceof CoralWallFanBlock // 墙上活珊瑚
        // 1.20+ 新增
        || block instanceof WallHangingSignBlock
        ) {
            return WALL_DEGENERATE_RESOLVER;
        }

        // ==================== 1. 水平反向类 (Opposite) ====================
        // 特征：FACING 属性，且放置时背对玩家 (Face towards player)
        if (block instanceof AbstractChestBlock // 箱子, 陷阱箱, 末影箱
                || block instanceof RepeaterBlock // 红石中继器
                || block instanceof ComparatorBlock // 红石比较器
                || block instanceof AbstractFurnaceBlock // 熔炉, 高炉, 烟熏炉
                || block instanceof FenceGateBlock // 栅栏门
                || block instanceof DoorBlock // 门 (虽有多重属性，但水平逻辑一致)
                || block instanceof CarvedPumpkinBlock // 雕刻南瓜, 南瓜灯 (Jack o Lantern)
                || block instanceof BeehiveBlock // 蜂箱, 蜂巢
                || block instanceof LoomBlock // 织布机
                || block instanceof BarrelBlock // 木桶 (注：木桶其实是6面的，但很多人当箱子用。如果你的木桶是6面逻辑，移到
                                                // FACE_EXTEND_RESOLVER；如果是强制水平，放这里。原版木桶是6面的，建议移走，或者这里只处理水平情况)
                // *修正*：原版 Barrel 是 6 面朝向 (Look-based)，不属于
                // HorizontalFacingBlock。它应该归类到"活塞/发射器"类(Look Based)，或者 FACE_EXTEND。
                // 这里我们先不放 Barrel。

                || block instanceof LecternBlock // 讲台
                || block instanceof StonecutterBlock // 切石机
                || block instanceof CampfireBlock // 营火
                || block instanceof GlazedTerracottaBlock // 带釉陶瓦
                || block instanceof DecoratedPotBlock // 饰纹陶罐 (1.20+)
                || block instanceof ChiseledBookshelfBlock // 雕纹书架 (1.20+)
        ) {
            return HORIZONTAL_OPPOSITE_RESOLVER;
        }

        // ==================== 2. 水平同向类 (Same) ====================
        // 特征：FACING 属性，且放置时面向玩家视线 (Face with player)
        if (block instanceof AnvilBlock // 铁砧 (所有损坏程度)
                || block instanceof BedBlock // 床
                || block instanceof GrindstoneBlock // 砂轮 (注：砂轮有 Wall/Floor/Ceiling 状态，但水平逻辑是 Same)
                || block instanceof BellBlock // 钟 (同上)
        ) {
            return HORIZONTAL_SAME_RESOLVER;
        }

        // ==================== 9. 六轴同向类 (Look 6 Same) ====================
        // 特征：6面 FACING，朝向 = 玩家视线
        if (block instanceof DispenserBlock // 发射器
                || block instanceof DropperBlock // 投掷器
                || block instanceof ObserverBlock // 侦测器 (输出端朝向玩家视线)
                || block instanceof CommandBlock // 命令方块
                || block instanceof BarrelBlock // 木桶 (通常是6轴 Look-based，虽然很多人以为是箱子)
        ) {
            return LOOK_6_SAME_RESOLVER;
        }

        // ==================== 10. 六轴反向类 (Look 6 Opposite) ====================
        // 特征：6面 FACING，朝向 = 玩家视线反向 (头对着玩家)
        if (block instanceof PistonBlock // 活塞 (普通 & 粘性)
        // 注：EndPortalFrame 是水平反向，已在 Horizontal_Opposite 处理
        ) {
            return LOOK_6_OPPOSITE_RESOLVER;
        }

        // ==================== 11. 附着面类 (Face Attached / 12-Direction)
        // ====================
        // 特征：有 FACE (Wall/Floor/Ceiling) 和 HORIZONTAL_FACING 属性
        if (block instanceof ButtonBlock // 所有按钮 (木/石/黑石/铜)
                || block instanceof LeverBlock // 拉杆
                || block instanceof GrindstoneBlock // 砂轮
                || block instanceof BellBlock // 钟 (注：钟的放置逻辑与此类似)
        // || block instanceof SwitchBlock // (如果模组有类似 Switch 的类)
        ) {
            return FACE_ATTACHED_RESOLVER;
        }

        // [新增] 合成器 (1.21+)
        if (block instanceof CrafterBlock) {
            return CRAFTER_RESOLVER;
        }

        // 4. 默认
        return DEFAULT_RESOLVER;
    }

    // ==================== 便捷解析方法 ====================

    /**
     * 一站式解析方法：自动选择策略并解析方向
     *
     * [重要修复] 处理双层半砖的逻辑：
     * 1. 如果当前位置已有单层半砖，优先尝试自我补全（保持原始 DOUBLE 状态）
     * 2. 如果当前是空气，尝试新放置 BOTTOM 或 TOP
     * 3. 如果已经是双层半砖，返回 null（无需放置）
     *
     * @param ctx 放置上下文
     * @return 最佳放置选项，如果无法放置则返回 null
     */
    public static PlacementOption resolve(PlacementContext ctx) {
        // 特殊处理半砖
        if (ctx.hasProperty(SlabBlock.TYPE)) {
            var currentState = ctx.currentState();
            boolean isCurrentSlab = currentState.getBlock() instanceof SlabBlock;

            // 情况1：当前已有单层半砖，只能自我补全
            if (isCurrentSlab && currentState.contains(SlabBlock.TYPE)) {
                SlabType currentType = currentState.get(SlabBlock.TYPE);

                // 如果已经是双层，无需放置
                if (currentType == SlabType.DOUBLE) {
                    return null;
                }

                // 只有目标是 DOUBLE 时才尝试自我补全
                // 如果目标是 BOTTOM 或 TOP，但当前已经有半砖了，那就无法放置
                if (ctx.getProperty(SlabBlock.TYPE) != SlabType.DOUBLE) {
                    return null; // 目标与当前不匹配，无法放置
                }

                // 保持原始的 DOUBLE 状态，让 SLAB_SELF_COMPLETE 正常工作
                PlacementResolver resolver = get(ctx.targetState());
                return resolver.resolve(ctx);
            }

            // 情况2：当前是空气，目标是双层半砖，尝试新放置
            if (!isCurrentSlab && ctx.getProperty(SlabBlock.TYPE) == SlabType.DOUBLE) {
                // 先尝试放 BOTTOM
                BlockState bottomState = ctx.targetState().with(SlabBlock.TYPE, SlabType.BOTTOM);
                PlacementContext bottomCtx = ctx.withTargetState(bottomState);
                PlacementResolver resolver = get(bottomCtx.targetState());
                PlacementOption result = resolver.resolve(bottomCtx);
                if (result != null) {
                    // 【修复】在返回时，将实际选择的目标状态（BOTTOM）和 hitVec 包含在 PlacementOption 中
                    return new PlacementOption(result.direction(), result.isSelf(), bottomState, result.hitVec());
                }

                // 再尝试放 TOP
                BlockState topState = ctx.targetState().with(SlabBlock.TYPE, SlabType.TOP);
                PlacementContext topCtx = ctx.withTargetState(topState);
                resolver = get(topCtx.targetState());
                result = resolver.resolve(topCtx);
                if (result != null) {
                    // 【修复】在返回时，将实际选择的目标状态（TOP）和 hitVec 包含在 PlacementOption 中
                    return new PlacementOption(result.direction(), result.isSelf(), topState, result.hitVec());
                }

                // 两种都不行则返回 null
                return null;
            }
        }

        // 其他方块类型或单层半砖目标：直接解析
        PlacementResolver resolver = get(ctx.targetState());
        return resolver.resolve(ctx);
    }

    /**
     * 检查是否可以放置
     *
     * [修复] 与 resolve() 保持一致的逻辑
     *
     * @param ctx 放置上下文
     * @return true 表示存在可行的放置方向
     */
    public static boolean canPlace(PlacementContext ctx) {
        // 特殊处理半砖
        if (ctx.hasProperty(SlabBlock.TYPE)) {
            var currentState = ctx.currentState();
            boolean isCurrentSlab = currentState.getBlock() instanceof SlabBlock;

            // 情况1：当前已有单层半砖，只能自我补全
            if (isCurrentSlab && currentState.contains(SlabBlock.TYPE)) {
                SlabType currentType = currentState.get(SlabBlock.TYPE);

                // 如果已经是双层，无法放置
                if (currentType == SlabType.DOUBLE) {
                    return false;
                }

                // 只有目标是 DOUBLE 时才能补全
                if (ctx.getProperty(SlabBlock.TYPE) != SlabType.DOUBLE) {
                    return false;
                }

                // 保持原始的 DOUBLE 状态检查
                PlacementResolver resolver = get(ctx.targetState());
                return resolver.canResolve(ctx);
            }

            // 情况2：当前是空气，目标是双层半砖
            if (!isCurrentSlab && ctx.getProperty(SlabBlock.TYPE) == SlabType.DOUBLE) {
                // 检查放 BOTTOM 是否可行
                PlacementContext bottomCtx = ctx
                        .withTargetState(ctx.targetState().with(SlabBlock.TYPE, SlabType.BOTTOM));
                PlacementResolver resolver = get(bottomCtx.targetState());
                if (resolver.canResolve(bottomCtx)) {
                    return true;
                }

                // 检查放 TOP 是否可行
                PlacementContext topCtx = ctx.withTargetState(ctx.targetState().with(SlabBlock.TYPE, SlabType.TOP));
                resolver = get(topCtx.targetState());
                return resolver.canResolve(topCtx);
            }
        }

        // 其他方块类型：直接检查
        PlacementResolver resolver = get(ctx.targetState());
        return resolver.canResolve(ctx);
    }
}