package meteordevelopment.meteorclient.utils.printer.behavior;

import meteordevelopment.meteorclient.utils.printer.*;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;

import java.util.function.Predicate;

/**
 * PropertyToggleBehavior - 属性切换类行为的抽象基类
 *
 * <p>统一处理"方块类型已正确，但某个可交互属性不一致"的场景。
 * 通过右键交互循环/切换属性值到目标状态。
 *
 * <p>子类只需声明：
 * <ul>
 *   <li>{@link #isTargetBlock} — 方块类型判定</li>
 *   <li>{@link #targetProperty} — 需要修正的目标属性</li>
 *   <li>{@link #invariantProperties} — 放置时决定的不变量属性（不一致则无法在位修正）</li>
 * </ul>
 *
 * <p>可选覆写点：
 * <ul>
 *   <li>{@link #isExcluded} — 排除不可手动交互的变体（铁门/铁活板门）</li>
 *   <li>{@link #canInteract} — plan 阶段额外可行性检查（音符盒需上方为空气）</li>
 *   <li>{@link #interactionPositions} — 多格方块的交互位置候选（门需尝试上下半）</li>
 * </ul>
 *
 * <p>涵盖行为：
 * RepeaterDelay / ComparatorMode / Trapdoor / Door / FenceGate / Lever / DaylightDetector / NoteBlock
 */
public abstract class PropertyToggleBehavior implements PrinterBehavior {

    private static final Property<?>[] EMPTY_PROPS = new Property<?>[0];

    // ==================== 子类必须实现 ====================

    /** 目标方块类型判定（desired 和 current 都必须满足） */
    protected abstract boolean isTargetBlock(Block block);

    /** 可通过右键修正的目标属性 */
    protected abstract Property<?> targetProperty();

    // ==================== 子类可选覆写 ====================

    /** 放置时决定的不变量属性列表（不一致说明需要 break-and-replace，本行为不处理） */
    protected Property<?>[] invariantProperties() { return EMPTY_PROPS; }

    /** 是否排除不可手动交互的特定方块变体（如铁门、铁活板门） */
    protected boolean isExcluded(Block block) { return false; }

    /** plan 阶段额外可行性检查（返回 false 则放弃本次 plan） */
    protected boolean canInteract(PrinterTask task, MinecraftClient mc) { return true; }

    /** 交互位置候选列表（按优先级排列，取第一个 reachable 的位置） */
    protected BlockPos[] interactionPositions(PrinterTask task) {
        return new BlockPos[]{ task.pos() };
    }

    // ==================== 统一实现 ====================

    @Override
    public boolean supports(PrinterTask task) {
        if (!isTargetBlock(task.desiredState().getBlock())) return false;
        if (!isTargetBlock(task.currentState().getBlock())) return false;
        if (isExcluded(task.currentState().getBlock())) return false;
        for (Property<?> inv : invariantProperties()) {
            if (!BlockUtilHelper.propertiesMatch(task, inv)) return false;
        }
        return !BlockUtilHelper.propertiesMatch(task, targetProperty());
    }

    @Override
    public boolean isSatisfied(PrinterTask task) {
        if (!isTargetBlock(task.currentState().getBlock())) return false;
        return BlockUtilHelper.propertiesMatch(task, targetProperty());
    }

    @Override
    public ActionPlan plan(PrinterTask task, MinecraftClient mc, boolean strict, boolean checkLos, double maxReach) {
        if (!canInteract(task, mc)) return null;

        // 在候选位置中寻找第一个可达的交互点
        ActionPlan.Interaction interaction = null;
        BlockPos interactPos = null;
        for (BlockPos pos : interactionPositions(task)) {
            interaction = InteractionPlanner.planSelfInteraction(mc, pos, strict, checkLos, maxReach);
            if (interaction != null) { interactPos = pos; break; }
        }
        if (interaction == null) return null;

        return new ActionPlan.UseBlock(
            interactPos,
            task.desiredState(),
            interaction,
            null,
            ActionPlan.SneakPolicy.REQUIRE_NOT_SNEAK,
            ActionPlan.HandPolicy.PREFER_MAIN_NO_SWITCH,
            buildStillNeedsAction(targetProperty(), task.desiredState())
        );
    }

    /**
     * 构建 stillNeedsAction 谓词：属性值尚未达到目标时返回 true。
     * 使用 .equals() 安全比较，兼容所有 Comparable 类型（Integer/Boolean/Enum）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Predicate<BlockState> buildStillNeedsAction(Property prop, BlockState desired) {
        Object targetValue = desired.get(prop);
        return state -> state.contains(prop) && !state.get(prop).equals(targetValue);
    }
}
