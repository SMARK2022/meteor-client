package meteordevelopment.meteorclient.utils.printer.behavior;

import net.minecraft.block.Block;
import net.minecraft.block.ComparatorBlock;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;

/**
 * ComparatorModeBehavior - 比较器模式修正行为
 *
 * 当蓝图要求的比较器模式（比较/减法）与世界中不一致时，
 * 通过右键交互切换模式。
 *
 * 属性处理：
 * - COMPARATOR_MODE: 可通过右键修正（目标属性）
 * - HORIZONTAL_FACING: 不可在位修改（必须方向一致才处理）
 * - POWERED: 运行时属性，忽略
 */
public class ComparatorModeBehavior extends PropertyToggleBehavior {

    @Override public Group group() { return Group.REDSTONE; }
    @Override public Key key() { return Key.COMPARATOR_MODE; }

    @Override protected boolean isTargetBlock(Block b) { return b instanceof ComparatorBlock; }
    @Override protected Property<?> targetProperty() { return Properties.COMPARATOR_MODE; }

    private static final Property<?>[] INVARIANTS = { Properties.HORIZONTAL_FACING };
    @Override protected Property<?>[] invariantProperties() { return INVARIANTS; }
}
