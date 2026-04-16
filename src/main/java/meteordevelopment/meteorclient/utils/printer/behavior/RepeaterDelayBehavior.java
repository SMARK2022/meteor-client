package meteordevelopment.meteorclient.utils.printer.behavior;

import net.minecraft.block.Block;
import net.minecraft.block.RepeaterBlock;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;

/**
 * RepeaterDelayBehavior - 中继器延迟修正行为
 *
 * 当蓝图要求的 repeater delay 与世界中不一致时，
 * 通过右键交互循环 delay 到目标值。
 *
 * 设计要点：
 * - 每 tick 只做一次 click，不维护内部计数器
 * - 依赖世界状态驱动：下一 tick 重新检查，不够再点
 * - stillNeedsAction 谓词防止 stale-plan（执行前再次检查 delay 是否仍需修正）
 *
 * 属性处理：
 * - DELAY:   可通过右键修正（目标属性）
 * - FACING:  不可在位修改（必须方向一致才处理）
 * - POWERED: 运行时属性，忽略
 * - LOCKED:  运行时属性，忽略
 */
public class RepeaterDelayBehavior extends PropertyToggleBehavior {

    @Override public Group group() { return Group.REDSTONE; }
    @Override public Key key() { return Key.REPEATER_DELAY; }

    @Override protected boolean isTargetBlock(Block b) { return b instanceof RepeaterBlock; }
    @Override protected Property<?> targetProperty() { return Properties.DELAY; }

    private static final Property<?>[] INVARIANTS = { Properties.HORIZONTAL_FACING };
    @Override protected Property<?>[] invariantProperties() { return INVARIANTS; }
}
