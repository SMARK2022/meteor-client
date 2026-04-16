package meteordevelopment.meteorclient.utils.printer.behavior;

import net.minecraft.block.Block;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;

/**
 * FenceGateBehavior - 栅栏门开关状态修正
 *
 * 当蓝图要求的栅栏门开关状态与世界不一致时，通过右键切换。
 * 所有栅栏门均可手动切换。
 *
 * 属性处理：
 * - OPEN:        可通过右键修正（目标属性）
 * - FACING:      不可在位修改
 * - IN_WALL:     由环境决定（相邻是否为墙），忽略
 * - POWERED:     运行时属性，忽略
 */
public class FenceGateBehavior extends PropertyToggleBehavior {

    @Override public Group group() { return Group.INTERACTABLE; }
    @Override public Key key() { return Key.FENCE_GATE_OPEN; }

    @Override protected boolean isTargetBlock(Block b) { return b instanceof FenceGateBlock; }
    @Override protected Property<?> targetProperty() { return Properties.OPEN; }

    private static final Property<?>[] INVARIANTS = { Properties.HORIZONTAL_FACING };
    @Override protected Property<?>[] invariantProperties() { return INVARIANTS; }
}
