package meteordevelopment.meteorclient.utils.printer;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * PlacementContext - 方块放置上下文
 *
 * 封装所有与方块放置相关的环境参数，避免函数传参爆炸。
 * 作为规则引擎的核心数据载体，在 Source、Filter、HitVecCalculator 之间传递。
 *
 * 设计理念：
 * - 不可变性：使用 record 确保线程安全和数据一致性
 * - 完整性：包含放置决策所需的所有信息
 * - 便捷性：提供常用的派生属性访问方法
 */
public record PlacementContext(
    /** 当前游戏世界 */
    World world,

    /** 目标放置位置 */
    BlockPos targetPos,

    /** 目标方块状态（包含朝向、类型等属性） */
    BlockState targetState,

    /** 玩家眼睛位置（用于视线和NCP检查） */
    Vec3d eyePos,

    /** 玩家实体引用（用于视线射线检测） */
    PlayerEntity player,

    /** 是否启用严格模式（NCP方向检查） */
    boolean strict,

    /** 是否检查视线（Line of Sight） */
    boolean checkLos,

    /** 最大交互距离（Reach Distance，从 Printer 的 placeRange 传入） */
    double maxReach
) {
    /**
     * 获取目标位置的中心坐标
     */
    public Vec3d targetCenter() {
        return Vec3d.ofCenter(targetPos);
    }

    /**
     * 获取目标方块类型
     */
    public net.minecraft.block.Block targetBlock() {
        return targetState.getBlock();
    }

    /**
     * 检查目标方块是否包含指定属性
     */
    public <T extends Comparable<T>> boolean hasProperty(net.minecraft.state.property.Property<T> property) {
        return targetState.contains(property);
    }

    /**
     * 获取目标方块的指定属性值
     */
    public <T extends Comparable<T>> T getProperty(net.minecraft.state.property.Property<T> property) {
        return targetState.get(property);
    }

    /**
     * 获取指定方向的邻居位置
     */
    public BlockPos neighborPos(net.minecraft.util.math.Direction dir) {
        return targetPos.offset(dir);
    }

    /**
     * 获取指定方向的邻居方块状态
     */
    public BlockState neighborState(net.minecraft.util.math.Direction dir) {
        return world.getBlockState(neighborPos(dir));
    }

    /**
     * 获取当前位置的方块状态（世界中实际存在的，非目标状态）
     */
    public BlockState currentState() {
        return world.getBlockState(targetPos);
    }

    /**
     * 创建一个修改了目标状态的新上下文（用于双层半砖等递归场景）
     */
    public PlacementContext withTargetState(BlockState newState) {
        return new PlacementContext(world, targetPos, newState, eyePos, player, strict, checkLos, maxReach);
    }

    /**
     * 静态工厂方法：从基础参数创建上下文
     */
    public static PlacementContext of(World world, BlockPos pos, BlockState state,
                                       PlayerEntity player, boolean strict, boolean checkLos, double maxReach) {
        return new PlacementContext(
            world,
            pos,
            state,
            player.getEyePos(),
            player,
            strict,
            checkLos,
            maxReach
        );
    }
}
