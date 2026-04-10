package meteordevelopment.meteorclient.utils.printer.behavior;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;

/**
 * TrapdoorBehavior - 活板门开关状态修正
 *
 * 当蓝图要求的活板门开关状态与世界不一致时，通过右键切换。
 * 仅处理可手动切换的活板门（排除铁活板门）。
 *
 * 属性处理：
 * - OPEN:        可通过右键修正（目标属性）
 * - FACING:      不可在位修改（不一致则需 break-and-replace）
 * - BLOCK_HALF:  不可在位修改
 * - WATERLOGGED: 由环境决定，忽略
 * - POWERED:     运行时属性，忽略
 */
public class TrapdoorBehavior extends PropertyToggleBehavior {

    @Override public Group group() { return Group.INTERACTABLE; }
    @Override public Key key() { return Key.TRAPDOOR_OPEN; }

    @Override protected boolean isTargetBlock(Block b) { return b instanceof TrapdoorBlock; }
    @Override protected Property<?> targetProperty() { return Properties.OPEN; }

    // 铁活板门不可手动切换
    @Override protected boolean isExcluded(Block b) { return b == Blocks.IRON_TRAPDOOR; }

    private static final Property<?>[] INVARIANTS = { Properties.HORIZONTAL_FACING, Properties.BLOCK_HALF };
    @Override protected Property<?>[] invariantProperties() { return INVARIANTS; }
}
