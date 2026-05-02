package meteordevelopment.meteorclient.utils.printer;

import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.entity.EntityType;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import net.minecraft.world.SpawnHelper;
import net.minecraft.world.World;

/**
 * 自包含的 hostile mob 生成判定工具 — 不依赖 MiniHUD。
 *
 * <p>模拟 vanilla {@link SpawnHelper} / MiniHUD 的 canSpawnAt 核心逻辑：
 * <ol>
 *   <li>y-1  地面 {@code allowsSpawning(CREEPER)}</li>
 *   <li>y    身体 {@code isClearForSpawn(ZOMBIE)}</li>
 *   <li>y+1  头部 {@code isClearForSpawn(ZOMBIE)}</li>
     *   <li>block light == 0（忽略天光，只按方块光计算防刷需求）</li>
 * </ol>
 *
 * <p>CREEPER 作为地面刷怪参照实体（标准 hostile 地面检查），
 * ZOMBIE 作为 2 格高空间参照实体。这与 MiniHUD 的选择一致。
 */
public final class SpawnCheckHelper {
    private SpawnCheckHelper() {}

    /**
     * 标准 hostile 陆地生成检查（2 格高生物）。
     *
     * @param world 当前世界
     * @param pos   怪物脚部位置（生成判定的基准坐标）
     * @return true = 该位置可自然生成 hostile mob（需要防护）
     */
    public static boolean canHostileSpawnAt(World world, BlockPos pos) {
        if (!isGeometricSpawnable(world, pos)) return false;
        return world.getLightLevel(LightType.BLOCK, pos) == 0;
    }

    /**
     * 纯几何刷怪面判定（不考虑光照）。
     *
     * <p>仅检查地面/身体/头部的方块结构条件。
     * 适用于 SLAB/BUTTON 等几何阻刷模式 — 即使当前有光照，
     * 光源被移除后该位置仍会变为可刷怪面，所以必须预防。
     */
    public static boolean isGeometricSpawnable(World world, BlockPos pos) {
        // ── 1. 地面：y-1 必须允许生成（实心、非透明） ──
        BlockPos below = pos.down();
        BlockState ground = world.getBlockState(below);
        if (!ground.allowsSpawning(world, below, EntityType.CREEPER)) return false;

        // ── 2. 身体空间：y 必须可通行 ──
        BlockState body = world.getBlockState(pos);
        FluidState bodyFluid = body.getFluidState();
        if (!SpawnHelper.isClearForSpawn(world, pos, body, bodyFluid, EntityType.ZOMBIE)) return false;
        // 额外碰撞检查：非完整方块（下半砖/箱子/台阶等）仍有碰撞体积，阻止实际生成
        if (!body.getCollisionShape(world, pos).isEmpty()) return false;

        // ── 3. 头部空间：y+1 必须可通行 ──
        BlockPos above = pos.up();
        BlockState head = world.getBlockState(above);
        FluidState headFluid = head.getFluidState();
        if (!SpawnHelper.isClearForSpawn(world, above, head, headFluid, EntityType.ZOMBIE)) return false;
        if (!head.getCollisionShape(world, above).isEmpty()) return false;

        return true;
    }

    /**
     * 快速预过滤：地面不是实体方块的位置不可能刷怪。
     * 用于扫描循环中跳过 90%+ 的空气/流体位置，避免完整 spawn check。
     */
    public static boolean quickRejectNotSpawnable(World world, BlockPos pos) {
        BlockState body = world.getBlockState(pos);
        if (!body.isAir()) return true;  // 实心方块不刷怪
        BlockState ground = world.getBlockState(pos.down());
        return ground.isAir() || ground.getBlock() instanceof FluidBlock;
    }

    /** 反向检查：该位置是否已安全（不可刷怪） */
    public static boolean isSpawnSafe(World world, BlockPos pos) {
        return !canHostileSpawnAt(world, pos);
    }

    /** 方块光（0-15）。block light == 0 意味着夜间可刷怪。 */
    public static int getBlockLight(World world, BlockPos pos) {
        return world.getLightLevel(LightType.BLOCK, pos);
    }

    /** 天空光（0-15）。 */
    public static int getSkyLight(World world, BlockPos pos) {
        return world.getLightLevel(LightType.SKY, pos);
    }
}
