package meteordevelopment.meteorclient.utils.printer;

import net.minecraft.block.*;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * BlockUtilHelper - 方块放置底层工具类
 *
 * 本类提供方块放置所需的底层工具方法，包括：
 * 1. hitVec计算 - 确定方块朝向的点击位置
 * 2. 支撑检查 - 判断方块是否可被点击/作为支撑
 * 3. NCP方向检查 - 反作弊视角方向验证
 * 4. 视线检查 - 射线检测方块可见性
 *
 * 架构说明：
 * 本类是规则引擎架构的底层支撑，提供原子级操作。
 * 高级放置逻辑由以下组件实现：
 * - {@link PlacementContext} - 上下文封装
 * - {@link CandidateSource} - 候选方向来源
 * - {@link CandidateFilter} - 候选过滤器
 * - {@link HitVecCalculator} - 点击位置计算器
 * - {@link Rules} - 规则定义库
 * - {@link PlacementResolver} - 策略组装器
 * - {@link ResolverRegistry} - 策略注册表
 *
 * 修改日志：
 * - [Refactor] 重构为规则引擎架构的底层工具类
 * - [Feature] 新增 Axis 轴向方块逻辑：原木、石英柱、锁链等根据目标轴向自动选择点击面
 * - [Fix] isClickable 修复：允许含水方块作为支撑
 * - [Fix] 楼梯/半砖逻辑优化
 */
public class BlockUtilHelper {

    /**
     * 需要潜行才能交互的方块集合
     * 包含所有容器、功能性方块、红石元件、门、活板门等
     * 采用动态注册表扫描 + 硬编码列表的混合方案，适配 Minecraft 1.21.4+
     */
    public static final Set<Block> SNEAK_BLOCKS = new HashSet<>();

    static {
        // 1. 基础硬编码列表 (那些没有特定通用类或容易被遗漏的方块)
        SNEAK_BLOCKS.addAll(Arrays.asList(
                // 工作台类
                Blocks.CRAFTING_TABLE, Blocks.STONECUTTER, Blocks.CARTOGRAPHY_TABLE,
                Blocks.SMITHING_TABLE, Blocks.GRINDSTONE, Blocks.LOOM,

                // 附魔与炼药
                Blocks.ENCHANTING_TABLE, Blocks.BREWING_STAND,

                // 特殊功能
                Blocks.BEACON, Blocks.JUKEBOX, Blocks.BELL,
                Blocks.COMPOSTER, // 堆肥桶
                Blocks.RESPAWN_ANCHOR, // 重生锚
                Blocks.LODESTONE, // 磁石 (可以用指南针交互)
                Blocks.CAKE, // 蛋糕 (右键会吃掉)
                Blocks.CANDLE_CAKE,
                Blocks.DRAGON_EGG, // 龙蛋 (右键瞬移)
                Blocks.SWEET_BERRY_BUSH, // 甜浆果丛 (右键采集)
                Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT, // 发光浆果
                Blocks.PUMPKIN, // 南瓜 (虽通常需要剪刀，但潜行更安全)
                Blocks.BEEHIVE, Blocks.BEE_NEST, // 蜂箱
                Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK, Blocks.REPEATING_COMMAND_BLOCK,
                Blocks.STRUCTURE_BLOCK, Blocks.JIGSAW,

                // 1.21+ 新增重要交互方块
                Blocks.CRAFTER, // 自动合成器 (有GUI)
                Blocks.TRIAL_SPAWNER, // 试炼刷怪笼 (互动调整)
                Blocks.VAULT // 宝库 (钥匙互动)
        ));

        // 2. 动态扫描注册表 (这是适配 1.21.4 的核心，自动覆盖所有变种)
        for (Block block : Registries.BLOCK) {
            // 如果已经包含了就不重复处理
            if (SNEAK_BLOCKS.contains(block))
                continue;

            // --- 容器与存储类 ---
            if (block instanceof AbstractChestBlock || // 箱子、陷阱箱、末影箱
                    block instanceof ShulkerBoxBlock || // 所有颜色的潜影盒
                    block instanceof BarrelBlock || // 桶
                    block instanceof DispenserBlock || // 发射器
                    block instanceof DropperBlock || // 投掷器
                    block instanceof HopperBlock || // 漏斗
                    block instanceof ChiseledBookshelfBlock) { // 雕纹书架 (1.20+)
                SNEAK_BLOCKS.add(block);
            }

            // --- 熔炉与加工类 ---
            else if (block instanceof AbstractFurnaceBlock || // 熔炉、高炉、烟熏炉
                    block instanceof AnvilBlock) { // 所有铁砧
                SNEAK_BLOCKS.add(block);
            }

            // --- 红石与开关类 ---
            else if (block instanceof ButtonBlock || // 所有木/石/铜按钮
                    block instanceof LeverBlock || // 拉杆
                    block instanceof TrapdoorBlock || // 所有材质活板门 (含铜)
                    block instanceof FenceGateBlock || // 所有栅栏门
                    block instanceof DoorBlock || // 所有门 (含铁门)
                    block instanceof NoteBlock || // 音符盒
                    block instanceof AbstractRedstoneGateBlock || // 中继器、比较器
                    block instanceof RedstoneWireBlock || // 红石粉 (右键切换连接形态)
                    block instanceof DaylightDetectorBlock || // 阳光传感器
                    block instanceof SculkSensorBlock || // 幽匿感测体
                    block instanceof CalibratedSculkSensorBlock) { // 校准幽匿感测体
                SNEAK_BLOCKS.add(block);
            }

            // --- 其他交互类 ---
            else if (block instanceof BedBlock || // 所有颜色的床
                    block instanceof AbstractSignBlock || // 所有告示牌 (包括挂牌)
                    block instanceof CandleBlock || // 蜡烛
                    block instanceof CampfireBlock || // 营火 (右键烤肉)
                    block instanceof FlowerPotBlock || // 花盆
                    block instanceof DecoratedPotBlock) { // 饰纹陶罐 (1.20+)
                SNEAK_BLOCKS.add(block);
            }

            // --- 1.21 特有 ---
            // 检查铜灯 (Copper Bulb) - 类名可能变动，用模糊匹配
            else if (block.getClass().getSimpleName().contains("BulbBlock") ||
                    block.getClass().getSimpleName().contains("CopperBulb")) {
                SNEAK_BLOCKS.add(block);
            }
        }
    }

    // ==================== hitVec计算方法 ====================

    /**
     * 计算标准方块的hitVec（点击位置）
     * 点击邻居方块的指定面的中心
     */
    public static Vec3d getHitVec(BlockPos neighborPos, Direction clickedSide) {
        return Vec3d.ofCenter(neighborPos).add(Vec3d.of(clickedSide.getVector()).multiply(0.5));
    }

    /**
     * 计算半砖的hitVec
     * 水平点击：通过Y偏移决定上/下半砖
     * 垂直点击：直接使用面中心
     */
    public static Vec3d getHitVecForSlab(BlockPos neighborPos, Direction clickedSide, SlabType targetSlabType) {
        if (clickedSide.getAxis().isHorizontal()) {
            // 水平方向：调整Y坐标
            // TOP: Y + 0.25 (点击上半部分)
            // BOTTOM: Y - 0.25 (点击下半部分)
            double yOffset = (targetSlabType == SlabType.TOP) ? 0.25 : -0.25;
            return new Vec3d(
                    neighborPos.getX() + 0.5,
                    neighborPos.getY() + 0.5 + yOffset,
                    neighborPos.getZ() + 0.5).add(Vec3d.of(clickedSide.getVector()).multiply(0.5));
        }
        // 垂直方向：直接使用面中心
        return Vec3d.ofCenter(neighborPos).add(Vec3d.of(clickedSide.getVector()).multiply(0.5));
    }

    /**
     * 计算楼梯的点击位置
     * 核心修复：
     * - 如果要放倒置楼梯 (TOP)，必须点击侧面的上半部分 (Y + 0.25)。
     * - 如果要放正置楼梯 (BOTTOM)，点击侧面下半部分 (Y - 0.25)。
     */
    public static Vec3d getHitVecForStairs(BlockPos neighborPos, Direction clickedSide,
            net.minecraft.block.enums.BlockHalf targetHalf) {
        // 如果点击的是水平侧面 (东南西北)
        if (clickedSide.getAxis().isHorizontal()) {
            // TOP(倒置) -> 向上偏移 0.25
            // BOTTOM(正置) -> 向下偏移 0.25
            double yOffset = (targetHalf == net.minecraft.block.enums.BlockHalf.TOP) ? 0.25 : -0.25;

            return new Vec3d(
                    neighborPos.getX() + 0.5,
                    neighborPos.getY() + 0.5 + yOffset, // <--- 关键修正
                    neighborPos.getZ() + 0.5).add(Vec3d.of(clickedSide.getVector()).multiply(0.5));
        }

        // 如果点击的是上下底面，直接点中心即可（Minecraft 机制保证：点底面必倒置，点顶面必正置）
        return Vec3d.ofCenter(neighborPos).add(Vec3d.of(clickedSide.getVector()).multiply(0.5));
    }

    // ==================== 支撑与判定核心逻辑 ====================

    /**
     * 判断一个方块是否可以被点击/作为支撑
     *
     * [Fix] 之前的逻辑直接排除了所有含 fluid 的方块，导致水下建筑无法进行。
     * 现在我们只排除 FluidBlock (纯水/岩浆源)，并检查是否有选取框 (OutlineShape)。
     * 只要方块有选取框（鼠标能指到），即使含水（如水下楼梯），也是合法的支撑。
     */
    public static boolean isClickable(BlockState state, World world, BlockPos pos) {
        if (state.isAir())
            return false;

        // 如果是纯流体方块（水/岩浆），则不能依靠
        // 注意：含水方块（Waterlogged Stairs）不是 FluidBlock，所以不会被这里拦截
        if (state.getBlock() instanceof FluidBlock)
            return false;

        // 如果是可替换方块（如草、雪片），视为不可依靠
        if (state.isReplaceable())
            return false;

        // 核心检查：只要有选取轮廓箱，就可以点击
        return !state.getOutlineShape(world, pos).isEmpty();
    }

    /**
     * 检查水平方向的邻居是否合法
     * [修复]：严格检查半砖类型匹配，防止异种半砖（Top靠Bottom）放置。
     */
    private static boolean canSupportSlabHorizontal(BlockState neighbor, SlabType targetType, World world,
            BlockPos neighborPos) {
        if (!isClickable(neighbor, world, neighborPos))
            return false;

        // 如果邻居是半砖，必须保证同层高有实体面
        if (neighbor.contains(SlabBlock.TYPE)) {
            SlabType neighborType = neighbor.get(SlabBlock.TYPE);

            // 双层半砖等于完整方块，哪里都能依附
            if (neighborType == SlabType.DOUBLE)
                return true;

            // 单层半砖必须类型一致
            // 目标 BOTTOM (0~0.5) <-> 邻居 BOTTOM (0~0.5) : OK
            // 目标 TOP (0.5~1) <-> 邻居 TOP (0.5~1) : OK
            // 异种 : NO
            return neighborType == targetType;
        }

        // 楼梯、完整方块等只要 isClickable 通过即可
        return true;
    }

    // ==================== 方向获取逻辑 (兼容层 - 委托给规则引擎) ====================

    /**
     * 获取普通方块的所有可交互方向
     *
     * @deprecated 推荐使用 {@link ResolverRegistry#get(Block)} 获取策略后调用 resolveAll()
     */
    @Deprecated
    public static List<Direction> getInteractDirections(BlockPos blockPos, World world, Vec3d eyePos,
            boolean strictDir) {
        List<Direction> result = new ArrayList<>();
        // 获取符合 NCP 视角要求的方向集合（如果未开启 strictDir 则为 null）
        Set<Direction> ncpDirs = strictDir ? getPlaceDirectionsNCP(eyePos, Vec3d.ofCenter(blockPos)) : null;

        // 遍历所有 6 个方向
        for (Direction dir : Direction.values()) {
            // 1. NCP 方向检查
            if (strictDir && ncpDirs != null && !ncpDirs.contains(dir.getOpposite())) {
                continue;
            }

            // 2. 物理支撑检查：邻居方块必须是可点击的（实体/完整）
            BlockPos neighborPos = blockPos.offset(dir);
            if (isClickable(world.getBlockState(neighborPos), world, neighborPos)) {
                result.add(dir);
            }
        }
        return result;
    }

    /**
     * 寻找最佳交互方向 (对外入口)
     * 支持视线检查与半砖逻辑
     *
     * 核心修复逻辑：寻找最佳的可交互方向
     * 遍历所有结构上可行的方向，而不仅仅是第一个。
     * 如果开启了 Strict 模式且开启了视线检查，会逐个检查视线，返回第一个“既可行又可见”的方向。
     *
     * @deprecated 此方法已重构，现在委托给规则引擎 {@link ResolverRegistry}
     */
    @Deprecated
    public static Direction findBestInteractDirectionLegacy(BlockPos pos, BlockState requiredState, World world,
            net.minecraft.entity.player.PlayerEntity player, boolean strict, boolean checkLos) {
        Vec3d eyePos = player.getEyePos();

        // 1. 获取所有结构上可行的候选方向
        List<Direction> candidates;

        // 特殊处理半砖：半砖的放置面有特定要求（Top/Bottom）
        if (requiredState.getBlock() instanceof SlabBlock && requiredState.contains(SlabBlock.TYPE)) {
            SlabType type = requiredState.get(SlabBlock.TYPE);
            candidates = getSlabPlaceDirections(pos, world, type, strict, eyePos);
        }
        // 2. 楼梯逻辑
        else if (requiredState.getBlock() instanceof StairsBlock && requiredState.contains(StairsBlock.HALF)) {
            BlockHalf half = requiredState.get(StairsBlock.HALF);
            candidates = getStairsPlaceDirections(pos, world, half, strict, eyePos);
        }
        // 3. 轴向方块逻辑 (原木、柱子、干草块、锁链等)
        // 使用 Properties.AXIS 进行通用判断，只要包含这个属性就适用
        else if (requiredState.contains(Properties.AXIS)) {
            Direction.Axis axis = requiredState.get(Properties.AXIS);
            candidates = getAxisPlaceDirections(pos, world, axis, strict, eyePos);
        }
        // 4. 默认逻辑
        else {
            candidates = getInteractDirections(pos, world, eyePos, strict);
        }

        // 2. 遍历候选列表，寻找满足条件的最优解
        for (Direction dir : candidates) {
            // 如果不需要检查视线，直接返回第一个结构可行的方向
            if (!checkLos)
                return dir;

            // 视线检查 (Raycast)
            BlockPos neighborPos = pos.offset(dir);
            Direction side = dir.getOpposite(); // 我们点击的是邻居的这个面

            if (canSeeBlock(neighborPos, side, world, player)) {
                return dir; // 找到了！既有依靠，又能看见
            }
        }

        return null; // 所有方向都不可行或被遮挡
    }

    /**
     * 寻找最佳交互方向 (对外入口 - 委托给规则引擎)
     *
     * 此方法是规则引擎架构的主入口点。
     * 内部委托给 {@link ResolverRegistry} 实现。
     *
     * @param pos           目标位置
     * @param requiredState 目标方块状态
     * @param world         游戏世界
     * @param player        玩家实体
     * @param strict        是否启用严格模式（NCP检查）
     * @param checkLos      是否检查视线
     * @return 最佳放置方向，如果无法放置则返回 null
     */
    public static Direction findBestInteractDirection(BlockPos pos, BlockState requiredState, World world,
            PlayerEntity player, boolean strict, boolean checkLos) {
        // 创建上下文
        PlacementContext ctx = PlacementContext.of(world, pos, requiredState, player, strict, checkLos);

        // 委托给规则引擎
        return ResolverRegistry.resolve(ctx);
    }

    /**
     * @deprecated 推荐使用 {@link #findBestInteractDirection}
     */
    @Deprecated
    public static Direction getInteractDirection(BlockPos blockPos, World world, Vec3d eyePos, boolean strictDir) {
        List<Direction> dirs = getInteractDirections(blockPos, world, eyePos, strictDir);
        return dirs.isEmpty() ? null : dirs.get(0);
    }

    /**
     * @deprecated 推荐使用 {@link #findBestInteractDirection}
     */
    @Deprecated
    public static Direction getInteractDirectionForSlab(BlockPos blockPos, World world, Vec3d eyePos, boolean strictDir) {
        return getInteractDirection(blockPos, world, eyePos, strictDir);
    }

    // ==================== 专用放置逻辑 (遗留方法 - 被规则引擎内部使用) ====================

    /**
     * [新增] 轴向方块放置逻辑 (Logs, Pillars, Hay Bales, Chain)
     * Rules:
     * - Y轴 (Vertical): Must click UP or DOWN face of neighbor.
     * - X轴 (East/West): Must click EAST or WEST face of neighbor.
     * - Z轴 (North/South): Must click NORTH or SOUTH face of neighbor.
     */
    public static List<Direction> getAxisPlaceDirections(BlockPos blockPos, World world, Direction.Axis targetAxis, boolean strictDir, Vec3d eyePos) {
        List<Direction> result = new ArrayList<>();
        Set<Direction> ncpDirs = strictDir ? getPlaceDirectionsNCP(eyePos, Vec3d.ofCenter(blockPos)) : null;

        // 根据目标轴向，确定允许寻找邻居的方向
        // 例如：想放 X 轴的原木，必须找到位于我 东边(EAST) 或 西边(WEST) 的邻居，并点击它们的 WEST 或 EAST 面。
        Direction[] searchDirs;
        switch (targetAxis) {
            case X -> searchDirs = new Direction[]{Direction.EAST, Direction.WEST};
            case Z -> searchDirs = new Direction[]{Direction.NORTH, Direction.SOUTH};
            default -> searchDirs = new Direction[]{Direction.UP, Direction.DOWN}; // Axis.Y
        }

        for (Direction dir : searchDirs) {
            // NCP 检查：检查我们要点击的那个面（即 dir.getOpposite()）是否在允许范围内
            if (strictDir && ncpDirs != null && !ncpDirs.contains(dir.getOpposite())) continue;

            BlockPos neighborPos = blockPos.offset(dir);

            // 只要邻居可点击即可，轴向方块通常不挑剔邻居的具体形态
            if (isClickable(world.getBlockState(neighborPos), world, neighborPos)) {
                result.add(dir);
            }
        }

        return result;
    }

    /**
     * [重构] 获取半砖放置方向
     * 逻辑：
     * 1. 如果是 DOUBLE，根据当前状态决定是补 TOP 还是补 BOTTOM。
     * 2. 水平扫描：检查同类型支撑。
     * 3. 垂直扫描：严格检查上下邻居的接触面。
     */
    public static List<Direction> getSlabPlaceDirections(BlockPos blockPos, World world, SlabType targetType,
            boolean strictDir, Vec3d eyePos) {

        // --- 1. 双层半砖特殊逻辑 ---
        // 双层半砖不是一步到位的，需要根据当前世界状态决定放置哪一半
        if (targetType == SlabType.DOUBLE) {
            BlockState current = world.getBlockState(blockPos);

            // 如果当前位置已经是半砖，我们需要填补剩下的一半
            if (current.getBlock() instanceof SlabBlock && current.contains(SlabBlock.TYPE)) {
                SlabType currentType = current.get(SlabBlock.TYPE);

                if (currentType == SlabType.DOUBLE) {
                    // 已经是双层了，不需要放置 (或作为普通方块处理)
                    return new ArrayList<>();
                } else if (currentType == SlabType.BOTTOM) {
                    // 当前是下半砖，我们需要放置 TOP 半砖来合成双层
                    return getSlabPlaceDirections(blockPos, world, SlabType.TOP, strictDir, eyePos);
                } else if (currentType == SlabType.TOP) {
                    // 当前是上半砖，我们需要放置 BOTTOM 半砖来合成双层
                    return getSlabPlaceDirections(blockPos, world, SlabType.BOTTOM, strictDir, eyePos);
                }
            } else {
                // 如果当前是空气/可替换，我们随便放哪一半都可以开始
                // 为了最大化成功率，我们把放 Top 和放 Bottom 的可能性都加进去
                List<Direction> directions = new ArrayList<>();
                directions.addAll(getSlabPlaceDirections(blockPos, world, SlabType.TOP, strictDir, eyePos));
                directions.addAll(getSlabPlaceDirections(blockPos, world, SlabType.BOTTOM, strictDir, eyePos));
                return directions;
            }
        }

        // --- 单层半砖逻辑 (TOP / BOTTOM) ---
        List<Direction> result = new ArrayList<>();
        Set<Direction> ncpDirs = strictDir ? getPlaceDirectionsNCP(eyePos, Vec3d.ofCenter(blockPos)) : null;

        // 2. 水平扫描 (North, South, East, West)
        for (Direction dir : new Direction[] { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST }) {
            if (strictDir && ncpDirs != null && !ncpDirs.contains(dir.getOpposite()))
                continue;

            BlockPos neighborPos = blockPos.offset(dir);
            BlockState neighbor = world.getBlockState(neighborPos);

            if (canSupportSlabHorizontal(neighbor, targetType, world, neighborPos)) {
                result.add(dir);
            }
        }

        // 3. 垂直扫描 (Up / Down)
        // 这里必须严格判断：我们想放的半砖，需要依靠哪个方向的哪个面

        if (targetType == SlabType.BOTTOM) {
            // 目标：放置 BOTTOM 半砖 (占据当前格 Y=0~0.5)
            // 依靠：下方邻居 (DOWN) 的顶面
            Direction dir = Direction.DOWN;
            if (!strictDir || (ncpDirs == null || ncpDirs.contains(dir.getOpposite()))) {
                BlockPos neighborPos = blockPos.offset(dir);
                BlockState neighbor = world.getBlockState(neighborPos);

                if (isClickable(neighbor, world, neighborPos)) {
                    // 检查下方邻居是否有实体顶面
                    // 下方是 BOTTOM 半砖 (0~0.5)，它的顶面 (Y=0.5) 接触不到我们的底面 (Y=0)
                    // 下方必须是 TOP (0.5~1) 或 DOUBLE
                    boolean hasTopFace = true;
                    if (neighbor.contains(SlabBlock.TYPE) && neighbor.get(SlabBlock.TYPE) == SlabType.BOTTOM) hasTopFace = false;
                    if (hasTopFace) result.add(dir);
                }
            }
        }
        else if (targetType == SlabType.TOP) {
            // 目标：放置 TOP 半砖 (占据当前格 Y=0.5~1)
            // 依靠：上方邻居 (UP) 的底面
            Direction dir = Direction.UP;
            if (!strictDir || (ncpDirs == null || ncpDirs.contains(dir.getOpposite()))) {
                BlockPos neighborPos = blockPos.offset(dir);
                BlockState neighbor = world.getBlockState(neighborPos);
                if (isClickable(neighbor, world, neighborPos)) {
                    // 检查上方邻居是否有实体底面
                    // 上方是 TOP 半砖 (0.5~1)，它的底面 (Y=0.5) 接触不到我们的顶面 (Y=1)
                    // 上方必须是 BOTTOM (0~0.5) 或 DOUBLE
                    boolean hasBottomFace = true;
                    if (neighbor.contains(SlabBlock.TYPE) && neighbor.get(SlabBlock.TYPE) == SlabType.TOP) hasBottomFace = false;
                    if (hasBottomFace) result.add(dir);
                }
            }
        }
        return result;
    }

    // ==================== [新增] 楼梯专用逻辑 ====================



    /**
     * 获取楼梯的可行放置方向
     * 逻辑：
     * 1. 水平方向：楼梯可以依附在任何实体方块的侧面。
     * 2. 垂直方向：严格限制。正置只能依靠下方，倒置只能依靠上方。
     */
    public static List<Direction> getStairsPlaceDirections(BlockPos blockPos, World world,
            net.minecraft.block.enums.BlockHalf targetHalf, boolean strictDir, Vec3d eyePos) {
        List<Direction> result = new ArrayList<>();
        Set<Direction> ncpDirs = strictDir ? getPlaceDirectionsNCP(eyePos, Vec3d.ofCenter(blockPos)) : null;

        // 1. 水平扫描 (依靠墙壁)
        for (Direction dir : new Direction[] { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST }) {
            if (strictDir && ncpDirs != null && !ncpDirs.contains(dir.getOpposite()))
                continue;

            BlockPos neighborPos = blockPos.offset(dir);
            if (isClickable(world.getBlockState(neighborPos), world, neighborPos)) {
                result.add(dir);
            }
        }

        // 2. 垂直扫描 (依靠地面或天花板)
        if (targetHalf == net.minecraft.block.enums.BlockHalf.BOTTOM) {
            // 正置楼梯：可以依靠下方的方块 (DOWN)
            Direction dir = Direction.DOWN;
            if (!strictDir || (ncpDirs == null || ncpDirs.contains(dir.getOpposite()))) {
                BlockPos neighborPos = blockPos.offset(dir);
                // 只要下方方块可点击且不是倒置楼梯/Slab(避免虚空接触)，通常都可以
                if (isClickable(world.getBlockState(neighborPos), world, neighborPos)) {
                    result.add(dir);
                }
            }
        } else if (targetHalf == net.minecraft.block.enums.BlockHalf.TOP) {
            // 倒置楼梯：可以依靠上方的方块 (UP)
            Direction dir = Direction.UP;
            if (!strictDir || (ncpDirs == null || ncpDirs.contains(dir.getOpposite()))) {
                BlockPos neighborPos = blockPos.offset(dir);
                if (isClickable(world.getBlockState(neighborPos), world, neighborPos)) {
                    result.add(dir);
                }
            }
        }

        return result;
    }

    // ==================== 可放置性预检查 (用于 Printer 过滤) ====================

    /**
     * 检查是否可放置 Slab (Wrapper)
     */
    public static boolean canPlaceSlab(BlockPos blockPos, World world, SlabType slabType) {
        // 直接复用 getSlabPlaceDirections 的逻辑判断列表是否为空
        // 传入 strictDir=false, eyePos=Zero 因为我们只关心物理可行性，不关心反作弊
        return !getSlabPlaceDirections(blockPos, world, slabType, false, Vec3d.ZERO).isEmpty();
    }

    // ==================== 工具方法 ====================

    /**
     * 获取NCP风格的放置方向（基于视点相对位置）
     * 限制玩家只能从特定方向放置方块
     */
    public static Set<Direction> getPlaceDirectionsNCP(Vec3d eyePos, Vec3d blockCenter) {
        double dx = eyePos.x - blockCenter.x;
        double dy = eyePos.y - blockCenter.y;
        double dz = eyePos.z - blockCenter.z;

        Set<Direction> dirs = new HashSet<>(6);

        // Y轴
        if (dy > 0.5) dirs.add(Direction.UP);
        else if (dy < -0.5) dirs.add(Direction.DOWN);
        else { dirs.add(Direction.UP); dirs.add(Direction.DOWN); }

        // X轴
        if (dx > 0.5) dirs.add(Direction.EAST);
        else if (dx < -0.5) dirs.add(Direction.WEST);
        else { dirs.add(Direction.EAST); dirs.add(Direction.WEST); }

        // Z轴
        if (dz > 0.5) dirs.add(Direction.SOUTH);
        else if (dz < -0.5) dirs.add(Direction.NORTH);
        else { dirs.add(Direction.SOUTH); dirs.add(Direction.NORTH); }

        return dirs;
    }

    /**
     * 视线检查 - 方块面对玩家是否可见
     */
    public static boolean canSeeBlock(BlockPos blockPos, Direction direction, World world, net.minecraft.entity.player.PlayerEntity player) {
        if (direction == null || world == null) return false;

        Vec3d testVec = Vec3d.ofCenter(blockPos).add(
            direction.getOffsetX() * 0.5,
            direction.getOffsetY() * 0.5,
            direction.getOffsetZ() * 0.5
        );

        net.minecraft.util.hit.BlockHitResult hitResult = world.raycast(new net.minecraft.world.RaycastContext(
            player.getEyePos(),
            testVec,
            net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
            net.minecraft.world.RaycastContext.FluidHandling.NONE,
            player
        ));

        return hitResult == null || hitResult.getType() == net.minecraft.util.hit.HitResult.Type.MISS;
    }

    /**
     * 获取球形范围内的所有方块位置
     */
    public static List<BlockPos> getSphere(int range, Vec3d center) {
        List<BlockPos> list = new ArrayList<>();
        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos pos = BlockPos.ofFloored(center.x + x, center.y + y, center.z + z);
                    if (Vec3d.ofCenter(pos).distanceTo(center) <= range) {
                        list.add(pos);
                    }
                }
            }
        }
        return list;
    }
}