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