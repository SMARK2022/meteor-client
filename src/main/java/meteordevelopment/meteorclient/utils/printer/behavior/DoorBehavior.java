package meteordevelopment.meteorclient.utils.printer.behavior;

import meteordevelopment.meteorclient.utils.printer.PrinterTask;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;

/**
 * DoorBehavior - 门开关状态修正
 *
 * 当蓝图要求的门开关状态与世界不一致时，通过右键切换。
 * 仅处理可手动切换的门（排除铁门）。
 * 门是两格高方块，点击任一半都会同时切换。
 *
 * 属性处理：
 * - OPEN:        可通过右键修正（目标属性）
 * - FACING:      不可在位修改
 * - HINGE:       不可在位修改
 * - HALF:        由位置决定（上/下半）
 * - POWERED:     运行时属性，忽略
 */
public class DoorBehavior extends PropertyToggleBehavior {

    @Override public Group group() { return Group.INTERACTABLE; }
    @Override public Key key() { return Key.DOOR_OPEN; }

    @Override protected boolean isTargetBlock(Block b) { return b instanceof DoorBlock; }
    @Override protected Property<?> targetProperty() { return Properties.OPEN; }

    // 铁门不可手动切换
    @Override protected boolean isExcluded(Block b) { return b == Blocks.IRON_DOOR; }

    private static final Property<?>[] INVARIANTS = { Properties.HORIZONTAL_FACING, Properties.DOOR_HINGE };
    @Override protected Property<?>[] invariantProperties() { return INVARIANTS; }

    // 门是两格高方块，先尝试下半（canonical 位置），失败再尝试上半
    @Override
    protected BlockPos[] interactionPositions(PrinterTask task) {
        BlockPos lower = task.pos();
        return new BlockPos[]{ lower, lower.up() };
    }
}
