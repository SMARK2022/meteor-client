/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.printer;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.Collection;
import java.util.Set;

/**
 * 通用打印器任务提供者接口。
 *
 * <p>实现此接口的模块（如 Surround、SpawnProof）可将自己的放置需求注入
 * Printer 的任务列表，从而复用 Printer 的 GrimAC 安全落面选择、
 * 旋转协调、物品切换等完整管线。</p>
 *
 * <p>外挂模块通过 {@code Printer.registerProvider(this)} / {@code unregisterProvider(this)}
 * 在 onActivate/onDeactivate 时自行注册/注销，无需 Printer 知道具体模块类型。</p>
 *
 * <p>{@link #allowedGroups()} 声明此输入源允许的 Behavior Group 白名单，
 * 防止 Printer 为非蓝图输入源启用不需要的行为（如 BREAK）。</p>
 */
public interface PrinterTaskProvider {

    /**
     * 返回当前 tick 需要放置方块的目标位置集合。
     * <p>返回的集合应为不可修改的快照，以防止并发修改。</p>
     */
    Collection<BlockPos> getPlacementPositions();

    /**
     * 返回指定位置期望的方块状态。
     *
     * @param pos 目标放置位置
     * @return 期望的 BlockState
     */
    BlockState getDesiredState(BlockPos pos);

    /**
     * 当此提供者启用时，是否允许在没有 Litematica 蓝图的情况下运行。
     * <p>防御型模块（如 Surround）应返回 {@code true}；
     * 蓝图覆盖层（如 SpawnProof）保持默认 {@code false}。</p>
     */
    default boolean bypassSchematicCheck() {
        return false;
    }

    /**
     * 此输入源允许的 Behavior Group 集合。
     * <p>Printer 在为此输入源的任务匹配 behavior 时，只会考虑在此集合内的 Group。
     * 默认只允许 PLACEMENT，防御模块不会意外触发 BREAK 等行为。</p>
     */
    default Set<PrinterBehavior.Group> allowedGroups() {
        return Set.of(PrinterBehavior.Group.PLACEMENT);
    }

    /**
     * 任务优先级（数值越大越优先）。
     * <p>高优先级输入源的任务在 Printer 扫描中排在前面。
     * 防御模块（如 Surround）应使用较高值。默认 0。</p>
     */
    default int priority() {
        return 0;
    }

    /**
     * 此输入源通过 Printer 管线提交旋转时使用的优先级。
     * <p>数值越大越优先，语义与 {@link meteordevelopment.meteorclient.utils.player.Rotations#rotate} 的 priority 参数一致。
     * 默认 40（标准蓝图级别 T4）。防御型模块（如 Surround）应覆盖为更高值。</p>
     */
    default int rotationPriority() {
        return 40;
    }

    /**
     * 显示名称，用于日志输出。
     */
    String name();
}
