package meteordevelopment.meteorclient.utils.printer;

import net.minecraft.client.MinecraftClient;

import java.util.List;

/**
 * PrinterBehavior - 打印机行为接口
 *
 * 独立于几何层（ResolverRegistry），回答三个核心问题：
 * 1. "这个任务我能处理吗？" → {@link #supports}
 * 2. "这个任务已经完成了吗？" → {@link #isSatisfied}
 * 3. "这一 tick 该生成什么动作计划？" → {@link #plan}
 *
 * 行为注册表按优先级排列：特化行为优先于通用放置行为。
 * 这样 repeater delay 修正会在"重新放一个 repeater"之前被尝试。
 */
public interface PrinterBehavior {

    /**
     * 此行为是否能处理该任务
     * 应为轻量判断，不做 resolve 或世界交互。
     */
    boolean supports(PrinterTask task);

    /**
     * 该任务是否已被满足（无需行动）
     * 轻量判断，仅比较状态。
     */
    boolean isSatisfied(PrinterTask task);

    /**
     * 为该任务生成一个动作计划
     * 可能涉及 ResolverRegistry resolve、reach/LOS 检查、物品栏查询等。
     *
     * @param task     待处理的任务
     * @param mc       客户端实例
     * @param strict   是否启用严格模式（NCP 方向检查）
     * @param checkLos 是否检查视线
     * @param maxReach 最大交互距离
     * @return 动作计划，如果当前无法生成合法计划则返回 null
     */
    ActionPlan plan(PrinterTask task, MinecraftClient mc, boolean strict, boolean checkLos, double maxReach);

    // ==================== 行为注册表 ====================

    /**
     * 已注册的行为列表（按优先级排列）
     * 特化行为在前，通用放置在后。
     *
     * 顺序决定优先级：
     * 1. RepeaterDelayBehavior - 同种方块但 delay 不一致 → 右键修状态
     * 2. BlockPlacementBehavior - 缺块/可替换 → 放置新方块
     */
    List<PrinterBehavior> REGISTRY = List.of(
        new RepeaterDelayBehavior(),
        new BlockPlacementBehavior()
    );

    /**
     * 为任务查找第一个匹配的行为
     *
     * @return 匹配的行为，如果没有行为能处理则返回 null
     */
    static PrinterBehavior find(PrinterTask task) {
        for (PrinterBehavior behavior : REGISTRY) {
            if (behavior.supports(task)) return behavior;
        }
        return null;
    }
}
