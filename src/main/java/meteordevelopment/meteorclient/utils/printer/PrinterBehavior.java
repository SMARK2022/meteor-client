package meteordevelopment.meteorclient.utils.printer;

import meteordevelopment.meteorclient.utils.printer.behavior.*;
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

    // ==================== 行为组枚举 ====================

    /**
     * 行为所属分组，用于开关控制和设置分类。
     * Printer 为每个分组创建一个开关，行为各自声明归属，
     * 不再需要 instanceof 匹配。
     */
    enum Group {
        /** 红石组件状态修正（repeater delay / comparator mode / wire dot-cross） */
        REDSTONE,
        /** 可交互方块状态修正（trapdoor / door / fence gate / daylight detector） */
        INTERACTABLE,
        /** 流体与含水（water/lava bucket, waterlog） */
        FLUID,
        /** 通用方块放置（fallback，始终启用） */
        PLACEMENT
    }

    /**
     * 行为唯一标识，用于细粒度开关和设置映射。
     * 每个行为声明自己的 Key，Printer 据此创建独立开关。
     */
    enum Key {
        REPEATER_DELAY("fix-repeater-delay", "Fix repeater delay mismatch."),
        COMPARATOR_MODE("fix-comparator-mode", "Fix comparator mode mismatch."),
        REDSTONE_DOT_CROSS("fix-redstone-wire", "Fix redstone wire dot/cross mismatch."),
        TRAPDOOR_OPEN("fix-trapdoor", "Fix trapdoor open state."),
        DOOR_OPEN("fix-door", "Fix door open state."),
        FENCE_GATE_OPEN("fix-fence-gate", "Fix fence gate open state."),
        LEVER_POWERED("fix-lever", "Fix lever powered state."),
        DAYLIGHT_DETECTOR("fix-daylight-detector", "Fix daylight detector inverted state."),
        NOTE_BLOCK_NOTE("fix-note-block", "Fix note block note value."),
        FLUID_SOURCE("place-fluid-source", "Place water/lava source blocks from buckets."),
        WATERLOG("fix-waterlog", "Add water to waterloggable blocks."),
        BLOCK_PLACEMENT("place-blocks", "Standard block placement.");

        public final String settingName;
        public final String description;

        Key(String settingName, String description) {
            this.settingName = settingName;
            this.description = description;
        }
    }

    /**
     * 此行为所属的分组。
     * 默认返回 PLACEMENT，通用放置行为无需覆写。
     */
    default Group group() {
        return Group.PLACEMENT;
    }

    /**
     * 此行为的唯一标识。
     * 默认返回 BLOCK_PLACEMENT。
     */
    default Key key() {
        return Key.BLOCK_PLACEMENT;
    }

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
     * 1-7. 红石组件状态修正（repeater / comparator / wire / note block）
     * 8-11. 可交互组件状态修正（trapdoor / door / fence gate / lever / daylight detector）
     * 12.  流体放置（water/lava bucket）
     * 13.  方块含水（waterlog with bucket）
     * 14.  双箱子放置（two-phase merge）
     * 15.  通用方块放置（fallback）
     */
    List<PrinterBehavior> REGISTRY = List.of(
        // 红石组件状态修正
        new RepeaterDelayBehavior(),
        new ComparatorModeBehavior(),
        new RedstoneDotCrossBehavior(),
        new NoteBlockBehavior(),
        // 可交互组件状态修正
        new TrapdoorBehavior(),
        new DoorBehavior(),
        new FenceGateBehavior(),
        new LeverBehavior(),
        new DaylightDetectorBehavior(),
        // 流体放置
        new WaterBehavior(),
        // 方块含水
        new WaterlogBehavior(),
        // 通用放置（fallback，同时处理双箱子两阶段放置，ChestType 由 ResolverRegistry 安全解析）
        new BlockPlacementBehavior()
    );

    /**
     * 为任务查找第一个匹配的行为（不考虑启用状态）。
     * 注意：在 Printer 中应使用 findEnabledBehavior() 代替，
     * 以确保行为匹配与用户开关保持一致。
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
