package meteordevelopment.meteorclient.utils.world;

import net.minecraft.block.*;
import net.minecraft.block.enums.SlabType;
import net.minecraft.registry.Registries;
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
 * BlockUtilHelper - 方块放置工具，支持半砖与反作弊验证
 *
 * 核心功能：
 * 1. hitVec计算 - 确定方块朝向（半砖Y偏移）
 * 2. 方向判定 - 水平优先，支持NCP检查
 * 3. 支撑检查 - 包括玻璃等完整方块
 * 4. 可放置性 - 上下半砖支撑规则
 * 5. 视线检查 - 反作弊线性检查
 *
 * 修改日志：
 * - 修复了半砖无法在异种半砖（如 Top Slab 上放 Bottom Slab）上放置的问题。
 * - 修复了楼梯无法作为支撑方块的问题。
 * - 优化了 isClickable 判定，不再强制要求完整方块，只要有碰撞箱即可。
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
                neighborPos.getZ() + 0.5
            ).add(Vec3d.of(clickedSide.getVector()).multiply(0.5));
        }
        // 垂直方向：直接使用面中心
        return Vec3d.ofCenter(neighborPos).add(Vec3d.of(clickedSide.getVector()).multiply(0.5));
    }

    // ==================== 核心判定逻辑修正 ====================

    /**
     * 判断一个方块是否可以被点击/作为支撑
     * 修改：不再要求必须是完整方块。只要有碰撞箱且不可替换（如草丛）即可。
     * 这解决了楼梯、半砖无法作为支撑的问题。
     */
    private static boolean isClickable(BlockState state, World world, BlockPos pos) {
        if (state.isAir() || !state.getFluidState().isEmpty()) return false;

        // 如果是可替换方块（如草、雪片），则不能依靠
        if (state.isReplaceable()) return false;

        // 只要有碰撞箱，就可以点击
        // 这囊括了楼梯、半砖、栅栏等非完整方块
        return !state.getCollisionShape(world, pos).isEmpty();
    }

    /**
     * 检查水平方向邻居是否能支撑半砖
     * 规则：只要邻居是可点击的实体方块即可。
     */
    private static boolean canSupportSlabHorizontal(BlockState neighbor, SlabType targetType, World world, BlockPos neighborPos) {
        return isClickable(neighbor, world, neighborPos);
    }

    /**
     * [关键修复] 检查垂直方向邻居是否能支撑半砖
     *
     * 场景 A: 想放 BOTTOM Slab (占 Y=0~0.5)
     * - 需要下方 (DOWN) 的方块提供一个实体顶面。
     * - 支持：完整方块、TOP Slab (Y=0.5~1)、Double Slab、Stairs(绝大多数情况)。
     * - 不支持：BOTTOM Slab (因为它 Y=0.5~1 是空的)。
     *
     * 场景 B: 想放 TOP Slab (占 Y=0.5~1)
     * - 需要上方 (UP) 的方块提供一个实体底面。
     * - 支持：完整方块、BOTTOM Slab (Y=0~0.5)、Double Slab、Stairs(绝大多数情况)。
     * - 不支持：TOP Slab (因为它 Y=0~0.5 是空的)。
     */
    private static boolean canSupportSlabVertical(BlockState neighbor, SlabType targetType, World world, BlockPos neighborPos) {
        // 首先必须是个实体方块
        if (!isClickable(neighbor, world, neighborPos)) return false;

        // 如果邻居是半砖，需要进行几何判断
        if (neighbor.contains(SlabBlock.TYPE)) {
            SlabType neighborType = neighbor.get(SlabBlock.TYPE);

            if (neighborType == SlabType.DOUBLE) return true; // 双层半砖等于完整方块

            if (targetType == SlabType.BOTTOM) {
                // 我们要放 BOTTOM (在上方)，依靠下方方块
                // 下方方块必须是 TOP 类型（即它的上半部分是实体的，顶面平整）
                return neighborType == SlabType.TOP;
            }
            else if (targetType == SlabType.TOP) {
                // 我们要放 TOP (在下方)，依靠上方方块
                // 上方方块必须是 BOTTOM 类型（即它的下半部分是实体的，底面平整）
                return neighborType == SlabType.BOTTOM;
            }
        }

        // 如果邻居是楼梯，通常都可以作为支撑
        if (neighbor.getBlock() instanceof StairsBlock) {
            return true;
        }

        // 对于其他方块，只要它是可点击的（isClickable 已检查），通常都可以作为垂直支撑
        return true;
    }

    // ==================== 方向判定方法 ====================

    /**
     * 获取普通方块的所有可交互方向
     * 逻辑：不再区分优先级，一次性返回所有由实体方块支撑且符合NCP方向要求的面
     */
    public static List<Direction> getInteractDirections(BlockPos blockPos, World world, Vec3d eyePos, boolean strictDir) {
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
     * 获取第一个可交互方向
     */
    public static Direction getInteractDirection(BlockPos blockPos, World world, Vec3d eyePos, boolean strictDir) {
        List<Direction> dirs = getInteractDirections(blockPos, world, eyePos, strictDir);
        return dirs.isEmpty() ? null : dirs.get(0);
    }

    /**
     * 核心修复逻辑：寻找最佳的可交互方向
     * 遍历所有结构上可行的方向，而不仅仅是第一个。
     * 如果开启了 Strict 模式且开启了视线检查，会逐个检查视线，返回第一个“既可行又可见”的方向。
     */
    public static Direction findBestInteractDirection(BlockPos pos, BlockState requiredState, World world, net.minecraft.entity.player.PlayerEntity player, boolean strict, boolean checkLos) {
        Vec3d eyePos = player.getEyePos();

        // 1. 获取所有结构上可行的候选方向
        List<Direction> candidates;

        // 特殊处理半砖：半砖的放置面有特定要求（Top/Bottom）
        if (requiredState.getBlock() instanceof SlabBlock && requiredState.contains(SlabBlock.TYPE)) {
            SlabType type = requiredState.get(SlabBlock.TYPE);
            candidates = getSlabPlaceDirections(pos, world, type, strict, eyePos);
        } else {
            // 普通方块
            candidates = getInteractDirections(pos, world, eyePos, strict);
        }

        // 2. 遍历候选列表，寻找满足条件的最优解
        for (Direction dir : candidates) {
            // 如果不需要检查视线，直接返回第一个结构可行的方向
            if (!checkLos) return dir;

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
     * 获取半砖的可交互方向（优先水平）
     */
    public static Direction getInteractDirectionForSlab(BlockPos blockPos, World world, Vec3d eyePos, boolean strictDir) {
        List<Direction> dirs = getInteractDirections(blockPos, world, eyePos, strictDir);
        return dirs.isEmpty() ? null : dirs.get(0);
    }

    /**
     * 获取半砖的所有放置方向
     * 逻辑：完全废除“优先水平”逻辑。同时检查水平和垂直方向。
     * 只要几何上能形成目标半砖类型，就加入列表。
     */
    public static List<Direction> getSlabPlaceDirections(BlockPos blockPos, World world, SlabType targetType, boolean strictDir, Vec3d eyePos) {
        // 如果是双层半砖，逻辑等同于普通方块（只需要找个面贴上去即可）
        if (targetType == SlabType.DOUBLE) {
            return getInteractDirections(blockPos, world, eyePos, strictDir);
        }

        List<Direction> result = new ArrayList<>();
        Set<Direction> ncpDirs = strictDir ? getPlaceDirectionsNCP(eyePos, Vec3d.ofCenter(blockPos)) : null;

        // --- 1. 检查水平方向 (四周) ---
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            if (strictDir && ncpDirs != null && !ncpDirs.contains(dir.getOpposite())) continue;

            BlockPos neighborPos = blockPos.offset(dir);
            BlockState neighbor = world.getBlockState(neighborPos);

            if (canSupportSlabHorizontal(neighbor, targetType, world, neighborPos)) {
                result.add(dir);
            }
        }

        // --- 2. 检查垂直方向 (上下) ---
        Direction verticalDir = (targetType == SlabType.TOP) ? Direction.UP : Direction.DOWN;

        if (verticalDir != null) {
            boolean validNcp = !strictDir || (ncpDirs != null && ncpDirs.contains(verticalDir.getOpposite()));
            if (validNcp) {
                BlockPos neighborPos = blockPos.offset(verticalDir);
                BlockState neighbor = world.getBlockState(neighborPos);

                if (canSupportSlabVertical(neighbor, targetType, world, neighborPos)) {
                    result.add(verticalDir);
                }
            }
        }

        return result;
    }

    // ==================== 可放置性检查方法 ====================

    /**
     * 检查上半砖(TOP)是否可放置
     * 逻辑更新：使用 isClickable 替代 isCompleteBlock
     */
    public static boolean canPlaceTopSlab(BlockPos blockPos, World world) {
        // 1. 检查上方是否能作为悬挂点
        BlockPos upPos = blockPos.up();
        BlockState upState = world.getBlockState(upPos);
        if (canSupportSlabVertical(upState, SlabType.TOP, world, upPos)) {
            return true;
        }

        // 2. 检查四周是否有依附点
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            BlockPos neighborPos = blockPos.offset(dir);
            BlockState neighbor = world.getBlockState(neighborPos);
            if (canSupportSlabHorizontal(neighbor, SlabType.TOP, world, neighborPos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查下半砖(BOTTOM)是否可放置
     * 逻辑更新：使用 isClickable 替代 isCompleteBlock
     */
    public static boolean canPlaceBottomSlab(BlockPos blockPos, World world) {
        // 1. 检查下方是否能作为支撑点
        BlockPos downPos = blockPos.down();
        BlockState downState = world.getBlockState(downPos);
        if (canSupportSlabVertical(downState, SlabType.BOTTOM, world, downPos)) {
            return true;
        }

        // 2. 检查四周是否有依附点
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            BlockPos neighborPos = blockPos.offset(dir);
            BlockState neighbor = world.getBlockState(neighborPos);
            if (canSupportSlabHorizontal(neighbor, SlabType.BOTTOM, world, neighborPos)) {
                return true;
            }
        }
        return false;
    }

    public static boolean canPlaceSlab(BlockPos blockPos, World world, SlabType slabType) {
        return switch (slabType) {
            case TOP -> canPlaceTopSlab(blockPos, world);
            case BOTTOM -> canPlaceBottomSlab(blockPos, world);
            case DOUBLE -> canPlaceTopSlab(blockPos, world) || canPlaceBottomSlab(blockPos, world);
        };
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
