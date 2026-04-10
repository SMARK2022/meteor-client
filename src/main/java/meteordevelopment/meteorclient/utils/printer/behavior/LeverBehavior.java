package meteordevelopment.meteorclient.utils.printer.behavior;

import net.minecraft.block.Block;
import net.minecraft.block.LeverBlock;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;

/**
 * LeverBehavior - 拉杆开关状态修正
 *
 * 当蓝图要求的拉杆供电状态与世界不一致时，通过右键切换。
 * 拉杆是稳定态开关，适合作为构建目标（区别于按钮的瞬时态）。
 *
 * 属性处理：
 * - POWERED:             可通过右键修正（目标属性，单次 toggle）
 * - BLOCK_FACE:          不可在位修改（FLOOR/WALL/CEILING，放置时决定）
 * - HORIZONTAL_FACING:   不可在位修改（放置时决定）
 */
public class LeverBehavior extends PropertyToggleBehavior {

    @Override public Group group() { return Group.INTERACTABLE; }
    @Override public Key key() { return Key.LEVER_POWERED; }

    @Override protected boolean isTargetBlock(Block b) { return b instanceof LeverBlock; }
    @Override protected Property<?> targetProperty() { return Properties.POWERED; }

    private static final Property<?>[] INVARIANTS = { Properties.BLOCK_FACE, Properties.HORIZONTAL_FACING };
    @Override protected Property<?>[] invariantProperties() { return INVARIANTS; }
}
