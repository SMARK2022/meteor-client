package meteordevelopment.meteorclient.utils.printer.behavior;

import net.minecraft.block.Block;
import net.minecraft.block.DaylightDetectorBlock;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;

/**
 * DaylightDetectorBehavior - 阳光探测器反转状态修正
 *
 * 当蓝图要求的阳光探测器反转状态与世界不一致时，通过右键切换。
 *
 * 属性处理：
 * - INVERTED: 可通过右键修正（目标属性）
 * - POWER:    运行时属性，忽略
 */
public class DaylightDetectorBehavior extends PropertyToggleBehavior {

    @Override public Group group() { return Group.INTERACTABLE; }
    @Override public Key key() { return Key.DAYLIGHT_DETECTOR; }

    @Override protected boolean isTargetBlock(Block b) { return b instanceof DaylightDetectorBlock; }
    @Override protected Property<?> targetProperty() { return Properties.INVERTED; }
}
