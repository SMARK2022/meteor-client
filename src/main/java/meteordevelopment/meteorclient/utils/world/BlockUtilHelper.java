package meteordevelopment.meteorclient.utils.world;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.enums.SlabType;
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
 */
public class BlockUtilHelper {

    /** 需要潜行才能交互的方块 */
    public static final Set<Block> SNEAK_BLOCKS = new HashSet<>(Arrays.asList(
        Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.ENDER_CHEST,
        Blocks.CRAFTING_TABLE, Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER,
        Blocks.BREWING_STAND, Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL,
        Blocks.ENCHANTING_TABLE, Blocks.GRINDSTONE, Blocks.STONECUTTER, Blocks.LOOM,
        Blocks.CARTOGRAPHY_TABLE, Blocks.SMITHING_TABLE, Blocks.BARREL,
        Blocks.DISPENSER, Blocks.DROPPER, Blocks.HOPPER,
        Blocks.LEVER, Blocks.REPEATER, Blocks.COMPARATOR, Blocks.NOTE_BLOCK,
        Blocks.JUKEBOX, Blocks.BEACON, Blocks.BELL
    ));

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

    // ==================== 支撑检查方法 ====================

    /**
     * 检查方块是否是完整的可作为支撑
     * 包括：实体方块、完整方块（如玻璃）、DOUBLE半砖
     * 排除：空气、流体、可替换方块
     */
    private static boolean isCompleteBlock(BlockState state, World world, BlockPos pos) {
        // 排除空气和流体
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        // DOUBLE半砖视为完整方块
        if (state.contains(SlabBlock.TYPE) && state.get(SlabBlock.TYPE) == SlabType.DOUBLE) {
            return true;
        }
        // 实体方块或不可替换的完整方块（如玻璃）
        return state.isSolidBlock(world, pos) || !state.isReplaceable();
    }

    /**
     * 检查水平方向邻居是否能支撑半砖
     * 规则：完整方块 || (半砖 && 类型相同)
     */
    private static boolean canSupportSlabHorizontal(BlockState neighbor, SlabType targetType, World world, BlockPos neighborPos) {
        // 检查是否是完整方块
        if (isCompleteBlock(neighbor, world, neighborPos)) {
            // DOUBLE或普通实体方块都可以
            if (neighbor.contains(SlabBlock.TYPE)) {
                SlabType nType = neighbor.get(SlabBlock.TYPE);
                return nType == SlabType.DOUBLE || nType == targetType;
            }
            return true;
        }
        return false;
    }

    /**
     * 检查垂直方向邻居是否能支撑半砖
     * 规则：完整方块 || (半砖 && 类型不同)
     * 原因：同类型会合并成DOUBLE
     */
    private static boolean canSupportSlabVertical(BlockState neighbor, SlabType targetType, World world, BlockPos neighborPos) {
        // 检查是否是完整方块
        if (isCompleteBlock(neighbor, world, neighborPos)) {
            if (neighbor.contains(SlabBlock.TYPE)) {
                SlabType nType = neighbor.get(SlabBlock.TYPE);
                return nType == SlabType.DOUBLE || nType != targetType;
            }
            return true;
        }
        return false;
    }

    // ==================== 方向判定方法 ====================

    /**
     * 检查邻居是否可点击（为放置提供支撑）
     * 对于一般方块：必须是完整方块
     */
    private static boolean isClickable(BlockState state, World world, BlockPos pos) {
        return !state.isAir() && state.getFluidState().isEmpty()
            && (state.isSolidBlock(world, pos) || !state.isReplaceable());
    }

    /**
     * 获取所有可交互方向，优先水平后垂直
     */
    public static List<Direction> getInteractDirections(BlockPos blockPos, World world, Vec3d eyePos, boolean strictDir) {
        List<Direction> result = new ArrayList<>();
        Set<Direction> ncpDirs = strictDir ? getPlaceDirectionsNCP(eyePos, Vec3d.ofCenter(blockPos)) : null;

        // 优先：水平方向
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            BlockPos neighborPos = blockPos.offset(dir);
            if (isClickable(world.getBlockState(neighborPos), world, neighborPos)) {
                if (!strictDir || (ncpDirs != null && ncpDirs.contains(dir.getOpposite()))) {
                    result.add(dir);
                }
            }
        }

        // 备选：垂直方向
        if (result.isEmpty()) {
            for (Direction dir : new Direction[]{Direction.UP, Direction.DOWN}) {
                BlockPos neighborPos = blockPos.offset(dir);
                if (isClickable(world.getBlockState(neighborPos), world, neighborPos)) {
                    if (!strictDir || (ncpDirs != null && ncpDirs.contains(dir.getOpposite()))) {
                        result.add(dir);
                    }
                }
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
     * 获取半砖的可交互方向（优先水平）
     */
    public static Direction getInteractDirectionForSlab(BlockPos blockPos, World world, Vec3d eyePos, boolean strictDir) {
        Set<Direction> ncpDirs = strictDir ? getPlaceDirectionsNCP(eyePos, Vec3d.ofCenter(blockPos)) : null;

        // 优先：水平方向
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            BlockPos neighborPos = blockPos.offset(dir);
            if (isClickable(world.getBlockState(neighborPos), world, neighborPos)) {
                if (!strictDir || (ncpDirs != null && ncpDirs.contains(dir.getOpposite()))) {
                    return dir;
                }
            }
        }

        // 备选：垂直方向
        for (Direction dir : new Direction[]{Direction.UP, Direction.DOWN}) {
            BlockPos neighborPos = blockPos.offset(dir);
            if (isClickable(world.getBlockState(neighborPos), world, neighborPos)) {
                if (!strictDir || (ncpDirs != null && ncpDirs.contains(dir.getOpposite()))) {
                    return dir;
                }
            }
        }

        return null;
    }

    /**
     * 获取半砖的所有放置方向（优先水平，然后垂直）
     */
    public static List<Direction> getSlabPlaceDirections(BlockPos blockPos, World world, SlabType targetType, boolean strictDir, Vec3d eyePos) {
        List<Direction> result = new ArrayList<>();
        if (targetType == SlabType.DOUBLE) {
            return getInteractDirections(blockPos, world, eyePos, strictDir);
        }

        Set<Direction> ncpDirs = strictDir ? getPlaceDirectionsNCP(eyePos, Vec3d.ofCenter(blockPos)) : null;

        // 优先：水平方向（更稳定）
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            BlockPos neighborPos = blockPos.offset(dir);
            BlockState neighbor = world.getBlockState(neighborPos);
            if (canSupportSlabHorizontal(neighbor, targetType, world, neighborPos)) {
                if (!strictDir || (ncpDirs != null && ncpDirs.contains(dir.getOpposite()))) {
                    result.add(dir);
                }
            }
        }

        // 备选：垂直方向
        if (result.isEmpty()) {
            Direction vertDir = (targetType == SlabType.TOP) ? Direction.UP : Direction.DOWN;
            BlockPos neighborPos = blockPos.offset(vertDir);
            BlockState neighbor = world.getBlockState(neighborPos);
            if (canSupportSlabVertical(neighbor, targetType, world, neighborPos)) {
                if (!strictDir || (ncpDirs != null && ncpDirs.contains(vertDir.getOpposite()))) {
                    result.add(vertDir);
                }
            }
        }

        return result;
    }

    // ==================== 可放置性检查方法 ====================

    /**
     * 检查上半砖(TOP)是否可放置
     * 优先级：上方完整块 > 水平完整块/TOP > 无法放置
     */
    public static boolean canPlaceTopSlab(BlockPos blockPos, World world) {
        // 优先：上方完整块
        BlockPos upPos = blockPos.up();
        if (isCompleteBlock(world.getBlockState(upPos), world, upPos)) {
            return true;
        }

        // 其次：水平方向的完整块或TOP/DOUBLE
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            BlockPos neighborPos = blockPos.offset(dir);
            BlockState neighbor = world.getBlockState(neighborPos);
            if (isCompleteBlock(neighbor, world, neighborPos)) {
                if (neighbor.contains(SlabBlock.TYPE)) {
                    SlabType type = neighbor.get(SlabBlock.TYPE);
                    if (type == SlabType.DOUBLE || type == SlabType.TOP) return true;
                } else {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 检查下半砖(BOTTOM)是否可放置
     * 优先级：下方完整块 > 水平完整块/BOTTOM > 无法放置
     */
    public static boolean canPlaceBottomSlab(BlockPos blockPos, World world) {
        // 优先：下方完整块
        BlockPos downPos = blockPos.down();
        if (isCompleteBlock(world.getBlockState(downPos), world, downPos)) {
            return true;
        }

        // 其次：水平方向的完整块或BOTTOM/DOUBLE
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            BlockPos neighborPos = blockPos.offset(dir);
            BlockState neighbor = world.getBlockState(neighborPos);
            if (isCompleteBlock(neighbor, world, neighborPos)) {
                if (neighbor.contains(SlabBlock.TYPE)) {
                    SlabType type = neighbor.get(SlabBlock.TYPE);
                    if (type == SlabType.DOUBLE || type == SlabType.BOTTOM) return true;
                } else {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 检查指定半砖类型是否可放置
     */
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
