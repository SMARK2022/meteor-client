package meteordevelopment.meteorclient.utils.world;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * BlockUtilHelper - 提供与GGBoy兼容的方块放置工具函数
 * 根据方向计算正确的hitVec和block面，以确保方块正确的朝向
 */
public class BlockUtilHelper {

    /**
     * 需要潜行才能交互的方块列表
     */
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

    /**
     * 根据方向计算hitVec（点击位置）
     * 这是关键：hitVec决定了玩家点击方块的哪个面，从而决定方块的朝向
     *
     * @param blockPos 方块位置
     * @param direction 交互方向（即点击的是哪个面的相邻方块）
     * @return 点击位置（从方块中心沿方向偏移0.5）
     */
    public static Vec3d getHitVec(BlockPos blockPos, Direction direction) {
        Vec3d blockCenter = Vec3d.ofCenter(blockPos);

        // 关键：沿着方向向量偏移0.5，这样就相当于点击了该面的中心
        return blockCenter.add(
            direction.getOffsetX() * 0.5,
            direction.getOffsetY() * 0.5,
            direction.getOffsetZ() * 0.5
        );
    }

    /**
     * 计算上半砖的hitVec
     * 对于上半砖，要点击方块的上面，所以Y方向要有特殊处理
     *
     * @param blockPos 方块位置
     * @param direction 交互方向
     * @return 上半砖的点击位置（Y值偏移到上半部分）
     */
    public static Vec3d getHitVecForUpperSlab(BlockPos blockPos, Direction direction) {
        Vec3d blockCenter = Vec3d.ofCenter(blockPos);
        // 上半砖：Y向上移动0.5（即点击上面）
        return blockCenter.add(0.0, 0.5, 0.0);
    }

    /**
     * 计算下半砖的hitVec
     * 对于下半砖，要点击方块的下面
     *
     * @param blockPos 方块位置
     * @param direction 交互方向
     * @return 下半砖的点击位置（Y值偏移到下半部分）
     */
    public static Vec3d getHitVecForLowerSlab(BlockPos blockPos, Direction direction) {
        Vec3d blockCenter = Vec3d.ofCenter(blockPos);
        // 下半砖：Y向下移动0.5（即点击下面）
        return blockCenter.add(0.0, -0.5, 0.0);
    }

    /**
     * 获取可以放置方块的交互方向
     *
     * 关键概念：
     * - 我们要在pos位置放置方块
     * - 需要找到pos周围哪个相邻方块是solid的（可以点击）
     * - 返回的direction表示"solid方块相对于目标pos的方向"
     *
     * 例如：
     * - 如果pos的右边(East)有solid方块，返回EAST
     * - 这意味着我们要点击East方向的那个方块，从而在pos放置新方块
     *
     * @param blockPos 目标位置（要放置方块的地方）
     * @param world 世界对象
     * @param eyePos 玩家眼睛位置
     * @param strictDirection 是否使用NCP风格的严格方向检查
     * @return 可以交互的方向，如果没有则返回null
     */
    public static Direction getInteractDirection(BlockPos blockPos, net.minecraft.world.World world, Vec3d eyePos, boolean strictDirection) {
        Direction resultDirection = null;

        // 遍历所有方向，找到可以点击的方向
        for (Direction direction : Direction.values()) {
            // 获取这个方向的相邻方块位置
            BlockPos neighborPos = blockPos.offset(direction);
            net.minecraft.block.BlockState state = world.getBlockState(neighborPos);

            // 检查这个方向的相邻方块是否为solid（可以点击）
            if (!state.isAir() && state.getFluidState().isEmpty() && state.isSolidBlock(world, neighborPos)) {
                // 如果是严格模式，还需要检查NCP方向是否允许
                if (strictDirection) {
                    // NCP check should be against the neighbor block that is being clicked.
                    Set<Direction> ncpDirections = getPlaceDirectionsNCP(eyePos, Vec3d.ofCenter(neighborPos));
                    // GGBoy逻辑：检查方向的相反方向
                    // The side of the neighbor we are clicking is direction.getOpposite().
                    // This direction must be one of the valid NCP directions.
                    if (ncpDirections.contains(direction.getOpposite())) {
                        resultDirection = direction;
                        break;
                    }
                } else {
                    resultDirection = direction;
                    break;
                }
            }
        }

        // 如果严格模式没找到，则进行非严格的fallback搜索
        if (resultDirection == null && !strictDirection) {
            for (Direction direction : Direction.values()) {
                BlockPos neighborPos = blockPos.offset(direction);
                net.minecraft.block.BlockState state = world.getBlockState(neighborPos);
                if (!state.isAir() && state.isSolidBlock(world, neighborPos)) {
                    resultDirection = direction;
                    break;
                }
            }
        }
        // If strict mode is on and we didn't find a direction, we don't fallback.
        // The original implementation had a fallback loop that ignored strictness, which could cause issues.

        return resultDirection;
    }

    /**
     * 针对半砖的交互方向获取（排除上下方向）
     *
     * @param blockPos 目标位置
     * @param world 世界对象
     * @param eyePos 玩家眼睛位置
     * @param strictDirection 是否使用严格方向检查
     * @return 可以交互的方向（不包括UP/DOWN）
     */
    public static Direction getInteractDirectionForSlab(BlockPos blockPos, net.minecraft.world.World world, Vec3d eyePos, boolean strictDirection) {
        Set<Direction> ncpDirections = strictDirection ? getPlaceDirectionsNCP(eyePos, Vec3d.ofCenter(blockPos)) : null;
        Direction resultDirection = null;

        for (Direction direction : Direction.values()) {
            // 对于半砖，跳过上下方向
            if (direction == Direction.UP || direction == Direction.DOWN) {
                continue;
            }

            BlockPos neighborPos = blockPos.offset(direction);
            net.minecraft.block.BlockState state = world.getBlockState(neighborPos);

            if (!state.isAir() && state.getFluidState().isEmpty() && state.isSolidBlock(world, neighborPos)) {
                if (strictDirection) {
                    // 半砖也使用相同的NCP检查逻辑：检查direction.getOpposite()
                    if (ncpDirections != null && ncpDirections.contains(direction.getOpposite())) {
                        resultDirection = direction;
                        break;
                    }
                } else {
                    resultDirection = direction;
                    break;
                }
            }
        }

        if (resultDirection == null) {
            for (Direction direction : Direction.values()) {
                if (direction == Direction.UP || direction == Direction.DOWN) {
                    continue;
                }
                BlockPos neighborPos = blockPos.offset(direction);
                net.minecraft.block.BlockState state = world.getBlockState(neighborPos);
                if (!state.isAir() && state.isSolidBlock(world, neighborPos)) {
                    resultDirection = direction;
                    break;
                }
            }
        }

        return resultDirection;
    }

    /**
     * 获取NCP风格的放置方向集合
     * 根据玩家眼睛位置相对于方块位置来判断可以从哪些方向放置方块
     *
     * @param eyePos 玩家眼睛位置
     * @param blockPos 方块中心位置
     * @return 允许放置的方向集合
     */
    public static Set<Direction> getPlaceDirectionsNCP(Vec3d eyePos, Vec3d blockPos) {
        double xDiff = eyePos.x - blockPos.x;
        double yDiff = eyePos.y - blockPos.y;
        double zDiff = eyePos.z - blockPos.z;

        Set<Direction> directions = new HashSet<>(6);

        // Y轴判断（上下）
        if (yDiff > 0.5) {
            directions.add(Direction.UP);
        } else if (yDiff < -0.5) {
            directions.add(Direction.DOWN);
        } else {
            directions.add(Direction.UP);
            directions.add(Direction.DOWN);
        }

        // X轴判断（东西）
        if (xDiff > 0.5) {
            directions.add(Direction.EAST);
        } else if (xDiff < -0.5) {
            directions.add(Direction.WEST);
        } else {
            directions.add(Direction.EAST);
            directions.add(Direction.WEST);
        }

        // Z轴判断（南北）
        if (zDiff > 0.5) {
            directions.add(Direction.SOUTH);
        } else if (zDiff < -0.5) {
            directions.add(Direction.NORTH);
        } else {
            directions.add(Direction.SOUTH);
            directions.add(Direction.NORTH);
        }

        return directions;
    }

    /**
     * 检查方块面是否对玩家可见（用于线性视距检查）
     *
     * @param blockPos 方块位置
     * @param direction 要检查的面
     * @param world 世界对象
     * @param player 玩家对象
     * @return 如果方块面对玩家可见，返回true
     */
    public static boolean canSeeBlock(BlockPos blockPos, Direction direction, net.minecraft.world.World world, net.minecraft.entity.player.PlayerEntity player) {
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
     * 获取可放置方块的球形范围
     *
     * @param range 范围半径
     * @param center 中心位置
     * @return 范围内的所有方块位置
     */
    public static java.util.List<BlockPos> getSphere(int range, Vec3d center) {
        java.util.List<BlockPos> list = new java.util.ArrayList<>();

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
