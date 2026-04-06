package meteordevelopment.meteorclient.utils.printer;

import net.minecraft.block.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
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
     *
     * @deprecated 推荐使用 canSeePoint 进行更精确的检查
     */
    @Deprecated
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

        if (hitResult == null || hitResult.getType() == net.minecraft.util.hit.HitResult.Type.MISS) {
            return true;
        }

        if (hitResult.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
            // 如果击中的是目标方块本身，且击中的面与目标面一致，则视为可见
            return hitResult.getBlockPos().equals(blockPos) && hitResult.getSide() == direction;
        }

        return false;
    }

    /**
     * 【新增】检查视线是否能直达某个精确坐标
     * 用于解决半砖/楼梯点击特定部位的可见性问题
     *
     * 判定逻辑：
     * 1. MISS：没有被任何方块阻挡 -> 可见
     * 2. HIT：击中了某个方块
     *    - 通常 Raycast 会正好击中目标方块的表面
     *    - 检查击中点与目标点的距离是否在误差范围内
     *
     * @param targetPoint 目标坐标（精确的点击位置）
     * @param world 游戏世界
     * @param player 玩家实体
     * @return 是否可见
     */
    public static boolean canSeePoint(Vec3d targetPoint, World world, net.minecraft.entity.player.PlayerEntity player) {
        if (targetPoint == null || world == null || player == null) return false;

        Vec3d eyePos = player.getEyePos();

        // 发射射线
        net.minecraft.util.hit.BlockHitResult hitResult = world.raycast(new net.minecraft.world.RaycastContext(
            eyePos,
            targetPoint,
            net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
            net.minecraft.world.RaycastContext.FluidHandling.NONE,
            player
        ));

        // 判定逻辑：
        // 1. MISS: 没有被任何方块阻挡 -> 可见
        if (hitResult.getType() == net.minecraft.util.hit.HitResult.Type.MISS) return true;

        // 2. 如果击中了，检查击中点与目标点的距离
        // 由于浮点数精度，targetPoint 刚好在面上，raycast 可能会判定为击中该面
        // 距离小于 0.01（1cm）视为同一个点
        return hitResult.getPos().squaredDistanceTo(targetPoint) < 0.0001;
    }

    /**
     * 【旧版兼容】基于 OUTLINE 的面可见性检查
     * 委托给带 placementTargetPos 的新版本，placementTargetPos 传 null（不做 replaceable 豁免）。
     *
     * @param interactPos 要点击的方块位置
     * @param face        要点击的方块面
     * @param targetPoint 精确的点击坐标
     * @param world       游戏世界
     * @param player      玩家实体
     * @return 是否能看到该方块面上的目标点
     */
    public static boolean canSeeFacePoint(BlockPos interactPos, Direction face, Vec3d targetPoint,
                                           World world, PlayerEntity player) {
        return canSeeFacePoint(interactPos, face, targetPoint, world, player, null);
    }

    /**
     * 【放置专用 LOS】基于 OUTLINE 的面可见性检查（支持 replaceable 豁免）
     *
     * 以 OUTLINE 为基础进行自定义射线检测：
     * - 铁轨、地毯、红石线等薄 outline 方块正常阻挡（不可穿透）
     * - 仅 placementTargetPos 上的可替换方块（草、小花、雪层等）做透明化处理，
     *   允许"压在草上放方块"的合法交互
     * - 真正要点击的 interactPos 永远不会被忽略（保证半砖自我补全等场景不误穿）
     *
     * @param interactPos         要点击的方块位置
     * @param face                要点击的方块面
     * @param targetPoint         精确的点击坐标
     * @param world               游戏世界
     * @param player              玩家实体
     * @param placementTargetPos  放置目标位置（其上的 replaceable 方块将被忽略），可为 null
     * @return 是否能看到该方块面上的目标点
     */
    public static boolean canSeeFacePoint(BlockPos interactPos, Direction face, Vec3d targetPoint,
                                           World world, PlayerEntity player,
                                           BlockPos placementTargetPos) {
        if (targetPoint == null || world == null || player == null) return false;

        // 关键：向被点击方块内部轻微缩进，避免"刚好在面上"导致 MISS
        final double EPS = 1.0e-3;
        Vec3d start = player.getEyePos();
        Vec3d end = targetPoint.add(
            -face.getOffsetX() * EPS,
            -face.getOffsetY() * EPS,
            -face.getOffsetZ() * EPS
        );

        // 使用 BlockView.raycast 自定义逐方块射线检测：
        // - 对每个经过的 BlockPos 查询 outline shape
        // - 若该方块应被"透明化"（targetPos 上的 replaceable），返回 null 继续射线
        // - 否则正常做 shape.raycast
        BlockHitResult hit = BlockView.raycast(
            start,
            end,
            null,
            (ignored, pos) -> {
                BlockState state = world.getBlockState(pos);

                // 只忽略"目标位上的可替换遮挡物"，且绝不忽略真正要点击的 interactPos
                if (shouldIgnoreForPlacementLos(state, pos, interactPos, placementTargetPos)) {
                    return null;
                }

                VoxelShape shape = state.getOutlineShape(world, pos);
                if (shape.isEmpty()) return null;

                return shape.raycast(start, end, pos);
            },
            ignored -> null
        );

        // 必须命中
        if (hit == null) return false;
        // 必须击中正确的方块
        if (!hit.getBlockPos().equals(interactPos)) return false;
        // 必须击中正确的面
        if (hit.getSide() != face) return false;

        // 击中点应该接近目标点（用 targetPoint 判断更直观）
        return hit.getPos().squaredDistanceTo(targetPoint) < 0.0001;
    }

    /**
     * 判断射线经过某位置时，是否应将其视为"透明"而忽略。
     *
     * 语义边界：
     * - interactPos（真正要点击的方块）永远不忽略
     * - 仅 placementTargetPos 上的可替换/空气/纯流体方块允许忽略
     * - 其余方块（包括不在 targetPos 上的 replaceable 植物）一律阻挡
     *
     * @param state              该位置的方块状态
     * @param pos                该位置坐标
     * @param interactPos        真正要点击的方块位置
     * @param placementTargetPos 放置目标位置（可为 null）
     * @return true 表示应忽略此方块，射线继续前进
     */
    private static boolean shouldIgnoreForPlacementLos(
        BlockState state, BlockPos pos,
        BlockPos interactPos, BlockPos placementTargetPos
    ) {
        // 真正要点击的那个方块，永远不能忽略
        if (pos.equals(interactPos)) return false;

        // 只有目标位才允许"透明化"
        if (placementTargetPos == null || !pos.equals(placementTargetPos)) return false;

        // 目标位上的空气/replaceable/纯流体可以忽略
        if (state.isAir()) return true;
        if (state.getBlock() instanceof FluidBlock) return true;
        return state.isReplaceable();
    }

    // ==================== 点击点可行性验证 ====================

    /**
     * 验证点击点是否满足 reach / NCP / LOS 约束。
     *
     * 此方法统一了 InteractionPlanner（自身交互）和 HitVecCalculator（放置交互）
     * 中相同的三重检查逻辑：
     * 1. 距离检查：眼睛到点击点的距离不超过最大交互距离
     * 2. NCP 方向检查（strict 模式）：反作弊方向验证
     * 3. LOS 视线检查：射线检测目标面上的目标点是否可见
     *
     * @param hitVec           点击坐标
     * @param face             点击面
     * @param interactPos      要交互的方块位置
     * @param eyePos           玩家眼睛位置
     * @param world            游戏世界
     * @param player           玩家实体
     * @param strict           是否启用 NCP 方向检查
     * @param checkLos         是否检查视线
     * @param maxReach         最大交互距离
     * @param placementTargetPos 放置目标位置（LOS 豁免 replaceable 方块），自身交互传 null
     * @return 是否所有约束都满足
     */
    public static boolean isPointValid(
        Vec3d hitVec, Direction face, BlockPos interactPos,
        Vec3d eyePos, World world, PlayerEntity player,
        boolean strict, boolean checkLos, double maxReach,
        BlockPos placementTargetPos
    ) {
        // 距离检查
        if (eyePos.distanceTo(hitVec) > maxReach + 0.1) return false;

        // NCP 方向检查
        if (strict) {
            Set<Direction> validDirs = getPlaceDirectionsNCP(eyePos, hitVec);
            if (!validDirs.contains(face)) return false;
        }

        // 视线检查
        if (checkLos && !canSeeFacePoint(interactPos, face, hitVec, world, player, placementTargetPos)) {
            return false;
        }

        return true;
    }

    // ==================== 属性比较工具 ====================

    /**
     * 比较 PrinterTask 中 desiredState 和 currentState 的指定属性值是否一致。
     *
     * 如果任一状态不包含该属性，返回 false。
     * 此方法被多个行为使用（如 Trapdoor、Door、FenceGate 等），统一在此定义以避免重复。
     *
     * @param task 打印任务
     * @param prop 要比较的方块属性
     * @param <T>  属性值类型
     * @return 两个状态中该属性值是否相等
     */
    public static <T extends Comparable<T>> boolean propertiesMatch(PrinterTask task, net.minecraft.state.property.Property<T> prop) {
        if (!task.desiredState().contains(prop) || !task.currentState().contains(prop)) return false;
        return task.desiredState().get(prop).equals(task.currentState().get(prop));
    }

    // ==================== 潜行策略判定 ====================

    /**
     * 根据交互方块确定潜行策略。
     *
     * 如果交互目标是 SNEAK_BLOCKS 中的方块（容器、按钮、门等），
     * 则需要潜行来绕过方块自身的交互行为。
     * 此逻辑被 BlockPlacementBehavior 和 WaterBehavior 共用。
     *
     * @param interactState 要交互的方块状态
     * @return REQUIRE_SNEAK 或 KEEP_CURRENT
     */
    public static ActionPlan.SneakPolicy determineSneakPolicy(net.minecraft.block.BlockState interactState) {
        return SNEAK_BLOCKS.contains(interactState.getBlock())
            ? ActionPlan.SneakPolicy.REQUIRE_SNEAK
            : ActionPlan.SneakPolicy.KEEP_CURRENT;
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