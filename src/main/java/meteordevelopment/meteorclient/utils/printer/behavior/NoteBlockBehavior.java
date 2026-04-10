package meteordevelopment.meteorclient.utils.printer.behavior;

import meteordevelopment.meteorclient.utils.printer.PrinterTask;

import net.minecraft.block.Block;
import net.minecraft.block.NoteBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;

/**
 * NoteBlockBehavior - 音符盒音高修正行为
 *
 * 当蓝图要求的音符盒音高与世界中不一致时，
 * 通过右键交互循环 NOTE 到目标值。
 *
 * 设计要点：
 * - 每 tick 只做一次 click，不维护内部计数器
 * - 依赖世界状态驱动：下一 tick 重新检查，不够再点
 * - NOTE 范围 0-24，每次右键 (current + 1) % 25
 * - 从当前到目标需要 (desired - current + 25) % 25 次右键
 * - stillNeedsAction 谓词防止 stale-plan
 * - 上方有实体方块时右键无效，plan 返回 null 避免无限重试
 *
 * 属性处理：
 * - NOTE:       可通过右键修正（目标属性，循环 0-24）
 * - INSTRUMENT: 由下方方块决定，运行时属性，忽略
 * - POWERED:    红石信号，运行时属性，忽略
 */
public class NoteBlockBehavior extends PropertyToggleBehavior {

    @Override public Group group() { return Group.REDSTONE; }
    @Override public Key key() { return Key.NOTE_BLOCK_NOTE; }

    @Override protected boolean isTargetBlock(Block b) { return b instanceof NoteBlock; }
    @Override protected Property<?> targetProperty() { return Properties.NOTE; }

    // 音符盒上方有实体方块时右键无法改变音高（Minecraft 硬编码行为）
    @Override
    protected boolean canInteract(PrinterTask task, MinecraftClient mc) {
        return mc.world.getBlockState(task.pos().up()).isAir();
    }
}
