package meteordevelopment.meteorclient.utils.printer;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

/**
 * PrinterTask - 打印机任务单元
 *
 * 表示蓝图与世界之间的一处不一致。
 * 扫描阶段生成，规划阶段由 {@link PrinterBehavior} 消费。
 *
 * 不携带"如何修复"的信息，仅描述"哪里需要修复"。
 * 具体修复方式由匹配的 Behavior 决定。
 */
public record PrinterTask(
    /** 目标位置 */
    BlockPos pos,

    /** 蓝图期望的状态 */
    BlockState desiredState,

    /** 世界中当前的状态 */
    BlockState currentState
) {}
