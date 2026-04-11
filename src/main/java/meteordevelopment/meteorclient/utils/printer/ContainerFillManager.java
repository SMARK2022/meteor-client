package meteordevelopment.meteorclient.utils.printer;

import meteordevelopment.meteorclient.events.packets.InventoryEvent;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * ContainerFillManager — 容器物品自动填充五态状态机
 *
 * <p>与 Printer 标准行为管线并行运行的有状态子系统。
 * 标准行为处理<b>方块状态</b>差异（单 tick 无状态），
 * 本管理器处理<b>容器内容</b>差异（多 tick 有状态）。
 *
 * <h2>架构核心：容器打开完全接入 Printer 的旋转/后发包体系</h2>
 * <pre>
 * Tick N     [PREPARING]   清除 sneak + 停止 sprint → 规划交互面/点
 * Tick N+1   [ARMED_OPEN]  Rotations.requestPreMovement → 等待 Post-movement
 *            → Printer 在 SendMovementPacketsEvent.Post 里调用 executeOpen()
 *            → interactBlock 发出，进入 OPENING
 * Tick N+1+RTT [onInventorySync]  同 tick 内批量 QUICK_MOVE + CLOSE_WINDOW
 * Tick N+2+RTT [COOLDOWN]  恢复 → IDLE
 * </pre>
 *
 * <h2>关键 Grim 约束</h2>
 * <ul>
 *   <li><b>PacketOrderF</b>: sprint 切换与 interactBlock 分 tick → PREPARING 隔离</li>
 *   <li><b>Sneak→Use 语义</b>: 潜行右键容器 = 绕过 onUse → 开不了容器 → 必须 REQUIRE_NOT_SNEAK</li>
 *   <li><b>Movement 对齐</b>: interactBlock 与 look packet 同 tick → Rotations + Post-movement</li>
 *   <li><b>MultiActionsC/D</b>: CLICK_WINDOW / CLOSE_WINDOW 时 !sprinting → 全程压制</li>
 *   <li><b>PacketOrderA</b>: 全部使用 QUICK_MOVE，不混用其他点击类型</li>
 * </ul>
 *
 * <h2>支持的容器类型（MVP）</h2>
 * <p>发射器（含投掷器 — DropperBE extends DispenserBE）、漏斗、木桶。</p>
 */
public class ContainerFillManager {

    // ==================== 状态枚举 ====================

    /**
     * 五态状态机。
     *
     * <pre>
     * IDLE ──pickTarget──→ PREPARING ──(plan ok)──→ ARMED_OPEN ──(post-movement)──→ OPENING
     *                       (unsneak+停sprint)       (旋转提交)     (interactBlock)
     *                                                                  │ inventorySync → fill+close
     *                                                                  │ timeout
     *                                                                  └──→ COOLDOWN ──→ IDLE
     * </pre>
     */
    public enum State {
        /** 空闲，搜索下一个目标 */
        IDLE,
        /** 已选定目标，清理输入态（sneak→off, sprint→off），规划交互面 */
        PREPARING,
        /** 交互已规划完成，旋转已提交，等待 Printer 在 Post-movement 中执行 */
        ARMED_OPEN,
        /** 已发送 interactBlock，等待服务端 WINDOW_ITEMS 回包 */
        OPENING,
        /** 操作完成或超时/失败，清理后恢复 IDLE */
        COOLDOWN
    }

    // ==================== 过填策略 ====================

    /**
     * 容器填充策略。
     * <ul>
     *   <li>{@link #FAST} — 仅用 QUICK_MOVE（shift-click），整组搬运最快，可能过填</li>
     *   <li>{@link #PRECISE} — 小堆 QUICK_MOVE + 大堆 PICKUP→右键逐个放置 → PICKUP 放回，
     *       确保精确到个数。Grim-safe（仅依赖 PICKUP button 0/1，BadPacketsP 合法）。</li>
     * </ul>
     */
    public enum OverfillPolicy {
        /** 仅 QUICK_MOVE，最快但可能过填 */
        FAST,
        /** 小堆 QUICK_MOVE + 大堆精确右键放置，不过填 */
        PRECISE
    }

    // ==================== 容器快照 ====================

    /**
     * 单个容器的填充状态快照。每次 fillAndClose 后记录，
     * 替代原来的布尔 satisfiedPositions，为 GUI 提供 Need/Actual/Missing 显示。
     *
     * @param status      填充结果状态
     * @param actualTotals 容器内各物品实际数量（仅追踪蓝图需求中的物品）
     * @param syncTick    最后一次同步时的游戏 tick
     */
    public record ContainerSnapshot(Status status, Map<Item, Integer> actualTotals, int syncTick) {
        public enum Status {
            /** 所有需求物品数量均已满足 */
            SATISFIED,
            /** 部分满足，但尚有缺口（玩家背包中物品不足） */
            PARTIAL,
            /** 玩家背包中无任何所需物品 */
            NO_MATERIALS
        }
    }

    // ==================== 常量 ====================

    /** 服务端响应超时（40 tick ≈ 2s，覆盖高延迟场景） */
    private static final int OPEN_TIMEOUT = 40;

    /** PREPARING 状态超时（60 tick ≈ 3s，防止卡住） */
    private static final int PREPARING_TIMEOUT = 60;

    /** 容器窗口中玩家背包占的固定槽位数（main 27 + hotbar 9） */
    private static final int PLAYER_INV_SLOTS = 36;

    // ==================== 状态字段 ====================

    private State state = State.IDLE;
    private BlockPos targetPos;
    private int openTick;
    private int preparingStartTick;

    /**
     * ARMED_OPEN 阶段的交互规划。
     * <p>由 {@link InteractionPlanner#planSelfInteraction} 生成，
     * Printer 在 {@code SendMovementPacketsEvent.Post} 中读取并执行。
     */
    private ActionPlan.Interaction armedInteraction;

    /**
     * 容器屏幕抑制标志。
     * <p>为 true 时，Printer 应拦截 OpenScreenEvent 阻止容器 GUI 弹出。
     * 操作完成后自动清除。
     */
    private boolean suppressScreen;

    /** 最大交互距离（由 Printer 传入）。 */
    private double maxReach;

    /** 当前过填策略（由 Printer 每 tick 传入）。 */
    private OverfillPolicy overfillPolicy = OverfillPolicy.FAST;

    // ==================== 蓝图缓存 ====================

    /** 蓝图容器需求：pos → {item → count}。每 tick 由 Printer 扫描填充。 */
    private final Map<BlockPos, Map<Item, Integer>> schematicCache = new HashMap<>();

    /**
     * 容器填充状态快照：pos → snapshot。
     * <p>替代原来的布尔 Set，记录每个容器的填充结果（actual totals + status），
     * 供 GUI 显示 Need / Actual / Missing 和状态判定。
     */
    private final Map<BlockPos, ContainerSnapshot> containerSnapshots = new LinkedHashMap<>();

    /** 本 tick 扫描到的位置（用于 pruneStaleEntries）。 */
    private final Set<BlockPos> thisTickScanned = new HashSet<>();

    // ==================== 扫描接口 ====================

    /** 扫描循环开始前调用，清除上一 tick 的扫描记录。 */
    public void beginScan() {
        thisTickScanned.clear();
    }

    /**
     * 注册蓝图容器。物品按类型汇总，忽略槽位顺序和 NBT。
     *
     * @param pos   容器位置（必须 immutable）
     * @param items 蓝图期望的物品列表
     */
    public void registerSchematicContainer(BlockPos pos, List<ItemStack> items) {
        Map<Item, Integer> needs = aggregateItems(items);
        if (!needs.isEmpty()) {
            schematicCache.put(pos, needs);
        }
        thisTickScanned.add(pos);
    }

    /**
     * 扫描循环结束后调用。
     * schematicCache 和 containerSnapshots 持久化（不随 range 变化而清除），
     * 仅在 reset() 时清空。这使 GUI 能显示蓝图内所有已发现的容器状态。
     */
    public void pruneStaleEntries() {
    }

    // ==================== 主循环 ====================

    /**
     * 每 tick 调用，驱动五态状态机。
     *
     * @param tickCounter    Printer 全局 tick 计数器
     * @param maxReach       最大交互距离
     * @param strict         是否 strict 模式
     * @param rotateEnabled  是否启用旋转
     * @param sneakForced    Printer 是否正在强制潜行（需要先清除才能开容器）
     * @param sneakClearer   清除 Printer 潜行状态的回调
     * @param overfillPolicy 过填策略（FAST / AVOID）
     */
    public void tick(int tickCounter, double maxReach, boolean strict,
                     boolean rotateEnabled, boolean sneakForced, Runnable sneakClearer,
                     OverfillPolicy overfillPolicy) {
        if (mc.player == null || mc.world == null) return;

        this.maxReach = maxReach;
        this.overfillPolicy = overfillPolicy;

        // 处于激活状态时持续压制 sprint（MultiActionsC/D 要求全程 !sprinting）
        if (state == State.PREPARING || state == State.ARMED_OPEN || state == State.OPENING) {
            if (mc.player.isSprinting()) {
                mc.player.setSprinting(false);
            }
        }

        switch (state) {
            case IDLE -> pickTarget(maxReach);

            case PREPARING -> {
                // 超时保护：防止因持续蹲下或其他原因导致 PREPARING 无限自旋
                if (mc.player.age - preparingStartTick > PREPARING_TIMEOUT) {
                    state = State.COOLDOWN;
                    return;
                }

                // 第一步：清除 Printer 遗留的强制潜行
                if (sneakForced) {
                    sneakClearer.run();
                    return; // 等下一 tick sneak 状态生效
                }

                // 第二步：确保玩家当前不处于潜行（用户手动潜行也要等）
                if (mc.player.isSneaking()) {
                    return; // 等玩家松开 sneak
                }

                // 第三步：MultiActionsC 安全 — 等待玩家停止移动
                // 1.21.2+ Grim 通过 knownInput.moving() 检测 CLICK_WINDOW 时是否有移动输入
                // 必须确保从 PREPARING 到 fill+close 全过程无移动输入
                if (isPlayerMovingInput()) {
                    return; // 等玩家停止移动
                }

                // 第四步：规划容器打开交互（真实几何面 + LOS + reach）
                armedInteraction = InteractionPlanner.planSelfInteraction(
                    mc, targetPos, strict, true, maxReach
                );

                if (armedInteraction == null) {
                    state = State.COOLDOWN;
                    return;
                }

                state = State.ARMED_OPEN;

                // 提交旋转请求（如果启用），本 tick movement 会包含正确的 yaw/pitch
                if (rotateEnabled) {
                    Rotations.requestPreMovement(
                        armedInteraction.yaw(), armedInteraction.pitch(), 50, null
                    );
                }
            }

            case ARMED_OPEN -> {
                // 等待 Printer 在 SendMovementPacketsEvent.Post 中调用 executeOpen()。
                // 正常情况下本态只存活 1 tick。
            }

            case OPENING -> {
                if (mc.player.age - openTick > OPEN_TIMEOUT) {
                    closeContainerSilently();
                    state = State.COOLDOWN;
                }
            }

            case COOLDOWN -> {
                suppressScreen = false;
                armedInteraction = null;
                state = State.IDLE;
            }
        }
    }

    // ==================== Printer 执行钩子 ====================

    /**
     * 由 Printer 在 {@code SendMovementPacketsEvent.Post} 中调用。
     *
     * <p>此时 movement packet（含旋转）已经发出，可以安全发送 interactBlock。
     * 交互使用 {@link #armedInteraction} 中规划好的面、命中点、方向。
     *
     * @return true 表示确实执行了容器打开（Printer 据此跳过其他操作）
     */
    public boolean executeOpen() {
        if (state != State.ARMED_OPEN || armedInteraction == null) return false;

        BlockPos pos = targetPos;
        ActionPlan.Interaction inter = armedInteraction;

        // 最终距离检查（移动后可能超出 reach）
        double distSq = mc.player.getEyePos().squaredDistanceTo(inter.hitVec());
        if (distSq > maxReach * maxReach) {
            armedInteraction = null;
            state = State.COOLDOWN;
            return false;
        }

        // 发送 interactBlock — 使用规划好的面和命中点
        suppressScreen = true;
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
            new BlockHitResult(inter.hitVec(), inter.clickedFace(), pos, false));
        openTick = mc.player.age;
        armedInteraction = null;
        state = State.OPENING;
        return true;
    }

    // ==================== 事件回调 ====================

    /**
     * InventoryS2CPacket 回调（由 Printer 转发）。
     *
     * <p>OPENING 态收到非玩家背包的 WINDOW_ITEMS 时，利用 Grim 的
     * serverOpenedInventoryThisTick 豁免，同 tick 内完成：
     * 差异计算 → 批量 QUICK_MOVE → CLOSE_WINDOW。
     */
    public void onInventorySync(InventoryEvent event) {
        if (state != State.OPENING || mc.player == null) return;

        int syncId = event.packet.getSyncId();
        if (syncId == 0) return;

        // 解析容器当前内容（前 N 个槽位为容器自身，后 36 个为玩家背包）
        List<ItemStack> allSlots = event.packet.getContents();
        int containerSlotCount = Math.max(0, allSlots.size() - PLAYER_INV_SLOTS);
        fillAndClose(containerSlotCount);
    }

    /** Printer 在 OpenScreenEvent 中检查此标志以决定是否抑制容器 GUI。 */
    public boolean shouldSuppressScreen() {
        return suppressScreen;
    }

    // ==================== 查询接口 ====================

    /** 管理器是否正在进行容器操作（Printer 据此暂停标准管线）。 */
    public boolean isBusy() { return state != State.IDLE; }

    public State getState() { return state; }

    public BlockPos getTargetPos() {
        return state != State.IDLE ? targetPos : null;
    }

    public int getSchematicContainerCount() { return schematicCache.size(); }

    public int getSatisfiedCount() {
        return (int) containerSnapshots.values().stream()
            .filter(s -> s.status() == ContainerSnapshot.Status.SATISFIED).count();
    }

    public boolean isSatisfied(BlockPos pos) {
        ContainerSnapshot snap = containerSnapshots.get(pos);
        return snap != null && snap.status() == ContainerSnapshot.Status.SATISFIED;
    }

    /** 获取指定容器的快照（可能为 null — 尚未打开过）。 */
    public ContainerSnapshot getSnapshot(BlockPos pos) {
        return containerSnapshots.get(pos);
    }

    /**
     * 玩家手动交互容器后调用（非自动填充流程）。
     * 将该容器的快照移除，使其在下次进入 range 时被重新目标化和同步。
     *
     * @param pos 容器位置
     */
    public void invalidateSnapshot(BlockPos pos) {
        containerSnapshots.remove(pos);
    }

    public Set<BlockPos> getRegisteredPositions() {
        return Collections.unmodifiableSet(schematicCache.keySet());
    }

    public Map<Item, Integer> getNeedsAt(BlockPos pos) {
        return schematicCache.get(pos);
    }

    /** 按容器类型分组的满足/总计摘要：containerBlockItem → [satisfied, total]。 */
    public Map<Item, int[]> getTypeSummaries() {
        Map<Item, int[]> result = new LinkedHashMap<>();
        for (BlockPos pos : schematicCache.keySet()) {
            Item icon = getContainerIcon(pos);
            if (icon == null) continue;
            int[] counts = result.computeIfAbsent(icon, k -> new int[2]);
            counts[1]++;
            if (isSatisfied(pos)) counts[0]++;
        }
        return result;
    }

    /** 所有未满足容器的物品需求汇总。 */
    public Map<Item, Integer> getAllUnsatisfiedItemNeeds() {
        Map<Item, Integer> result = new LinkedHashMap<>();
        for (var entry : schematicCache.entrySet()) {
            if (isSatisfied(entry.getKey())) continue;
            for (var need : entry.getValue().entrySet()) {
                result.merge(need.getKey(), need.getValue(), Integer::sum);
            }
        }
        return result;
    }

    /** 未满足容器的位置集合（用于 3D 高亮）。 */
    public Set<BlockPos> getUnsatisfiedPositions() {
        Set<BlockPos> result = new HashSet<>();
        for (BlockPos pos : schematicCache.keySet()) {
            if (!isSatisfied(pos)) result.add(pos);
        }
        return result;
    }

    // ==================== 生命周期 ====================

    /** 完全重置。Printer 激活/关闭时调用。 */
    public void reset() {
        if (state == State.OPENING) closeContainerSilently();
        state = State.IDLE;
        targetPos = null;
        openTick = 0;
        preparingStartTick = 0;
        suppressScreen = false;
        armedInteraction = null;
        schematicCache.clear();
        containerSnapshots.clear();
        thisTickScanned.clear();
    }

    // ==================== 内部：IDLE → PREPARING ====================

    /**
     * 搜索 reach 内最近的可操作未满足容器。
     * 找到后停止 sprint 并进入 PREPARING。
     */
    private void pickTarget(double maxReach) {
        Vec3d eye = mc.player.getEyePos();
        double maxSq = maxReach * maxReach;

        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;

        for (BlockPos pos : schematicCache.keySet()) {
            ContainerSnapshot snap = containerSnapshots.get(pos);
            if (snap != null && snap.status() == ContainerSnapshot.Status.SATISFIED) continue;

            double dSq = eye.squaredDistanceTo(Vec3d.ofCenter(pos));
            if (dSq > maxSq || dSq >= bestSq) continue;
            if (!isWorldBlockSimpleContainer(pos)) continue;

            bestSq = dSq;
            best = pos;
        }

        if (best == null) return;

        // 预检查：玩家背包中是否有任何所需物品（避免无意义打开）
        Map<Item, Integer> needs = schematicCache.get(best);
        if (needs != null && !hasAnyNeededItem(needs)) return;

        // MultiActionsC 安全：不要在玩家移动时启动容器操作
        if (isPlayerMovingInput()) return;

        // 停止 sprint，进入 PREPARING
        if (mc.player.isSprinting()) mc.player.setSprinting(false);
        targetPos = best;
        preparingStartTick = mc.player.age;
        state = State.PREPARING;
    }

    // ==================== 内部：填充 + 关闭 ====================

    /**
     * 读取当前 handler 的容器槽 → 三阶段填充 → 记录快照 → 关闭容器。
     * 全部在 onInventorySync 同一 tick 完成。
     *
     * <h3>三阶段填充算法</h3>
     * <ol>
     *   <li><b>QUICK_MOVE 阶段</b>（FAST + PRECISE 共用）：
     *       对 stackCount ≤ deficit 的玩家背包槽位执行 shift-click，整组搬入容器。</li>
     *   <li><b>精确放置阶段</b>（仅 PRECISE 模式）：
     *       若仍有 deficit 且剩余堆叠均 > deficit：<br>
     *       PICKUP 拿起源堆叠 → 对目标容器槽 RIGHT_CLICK deficit 次 → PICKUP 放回。<br>
     *       Grim-safe：PICKUP button 0/1 均为 BadPacketsP 合法值。</li>
     *   <li><b>快照记录</b>：从 handler 最终状态构建 {@link ContainerSnapshot}。</li>
     * </ol>
     */
    private void fillAndClose(int containerSlotCount) {
        Map<Item, Integer> needs = schematicCache.get(targetPos);
        ScreenHandler handler = mc.player.currentScreenHandler;

        if (needs == null || handler == null || handler.syncId == 0) {
            closeContainerSilently();
            state = State.COOLDOWN;
            return;
        }

        int totalSlots = handler.slots.size();
        boolean precise = (overfillPolicy == OverfillPolicy.PRECISE);

        // ── 按物品逐类填充 ──
        for (var entry : needs.entrySet()) {
            Item item = entry.getKey();
            int required = entry.getValue();
            int existing = countItemInContainer(handler, item, containerSlotCount);
            int deficit = required - existing;
            if (deficit <= 0) continue;

            // ── 阶段 1：QUICK_MOVE 适配堆（stackCount ≤ deficit），整组搬入 ──
            for (int slot = containerSlotCount; slot < totalSlots && deficit > 0; slot++) {
                ItemStack stack = handler.getSlot(slot).getStack();
                if (stack.isEmpty() || stack.getItem() != item) continue;
                if (stack.getCount() > deficit) continue; // 留给阶段 2

                InvUtils.shiftClick().slotId(slot);
                existing = countItemInContainer(handler, item, containerSlotCount);
                deficit = required - existing;
            }

            // ── 阶段 2：精确放置（PRECISE 模式，deficit > 0 且剩余堆叠 > deficit）──
            if (precise && deficit > 0) {
                placeExact(handler, item, deficit, containerSlotCount, totalSlots);
                existing = countItemInContainer(handler, item, containerSlotCount);
                deficit = required - existing;
            }

            // ── FAST 模式降级：仍有 deficit 时直接 QUICK_MOVE 第一个可用堆（允许过填）──
            if (!precise && deficit > 0) {
                for (int slot = containerSlotCount; slot < totalSlots && deficit > 0; slot++) {
                    ItemStack stack = handler.getSlot(slot).getStack();
                    if (stack.isEmpty() || stack.getItem() != item) continue;

                    InvUtils.shiftClick().slotId(slot);
                    existing = countItemInContainer(handler, item, containerSlotCount);
                    deficit = required - existing;
                }
            }
        }

        // ── 阶段 3：从 handler 最终状态构建快照 ──
        Map<Item, Integer> actualTotals = new LinkedHashMap<>();
        boolean allSatisfied = true;
        for (var entry : needs.entrySet()) {
            int actual = countItemInContainer(handler, entry.getKey(), containerSlotCount);
            actualTotals.put(entry.getKey(), actual);
            if (actual < entry.getValue()) allSatisfied = false;
        }

        ContainerSnapshot.Status status = allSatisfied
            ? ContainerSnapshot.Status.SATISFIED
            : ContainerSnapshot.Status.PARTIAL;
        containerSnapshots.put(targetPos, new ContainerSnapshot(status, actualTotals, mc.player.age));
        closeContainerSilently();
        state = State.COOLDOWN;
    }

    /**
     * 从 ScreenHandler 的容器区（前 containerSlotCount 个槽位）统计指定物品的实际数量。
     * <p>QUICK_MOVE 后 handler 本地预测已更新，因此此方法能反映真实插入量。
     */
    private static int countItemInContainer(ScreenHandler handler, Item item, int containerSlotCount) {
        int count = 0;
        for (int i = 0; i < containerSlotCount; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty() && stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * 精确放置：从一个大堆叠中取 exactCount 个物品放入容器。
     *
     * <p>操作序列（同 tick 内全部完成）：
     * <ol>
     *   <li>Left-click 源槽 → cursor 拿起整组</li>
     *   <li>在容器区找一个有空间的目标槽（同类型未满 / 空槽）</li>
     *   <li>Right-click 目标槽 exactCount 次 → 每次放入 1 个</li>
     *   <li>Left-click 源槽 → 把剩余放回</li>
     * </ol>
     *
     * <p>Grim 安全：仅使用 {@code SlotActionType.PICKUP} button 0/1，
     * {@code BadPacketsP} 对两者均合法。{@code MultiActionsC} 在 !sprinting + !input 条件下通过。
     *
     * @param handler            当前 ScreenHandler
     * @param item               要放置的物品类型
     * @param exactCount         需要精确放置的数量
     * @param containerSlotCount 容器区槽位数
     * @param totalSlots         handler 总槽位数
     */
    private void placeExact(ScreenHandler handler, Item item, int exactCount,
                            int containerSlotCount, int totalSlots) {
        // 找到第一个含有该物品且 count > exactCount 的玩家背包槽
        int sourceSlot = -1;
        for (int slot = containerSlotCount; slot < totalSlots; slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (!stack.isEmpty() && stack.getItem() == item && stack.getCount() > exactCount) {
                sourceSlot = slot;
                break;
            }
        }
        if (sourceSlot < 0) return;

        // 在容器区找到一个可以接收该物品的槽位（空槽或同类型未满）
        int targetSlot = findContainerTarget(handler, item, containerSlotCount);
        if (targetSlot < 0) return;

        int syncId = handler.syncId;

        // Step 1: Left-click 源槽 — 拿起整组到 cursor
        mc.interactionManager.clickSlot(syncId, sourceSlot, 0, SlotActionType.PICKUP, mc.player);

        // Step 2: Right-click 目标容器槽 exactCount 次 — 每次从 cursor 放入 1 个
        for (int i = 0; i < exactCount; i++) {
            mc.interactionManager.clickSlot(syncId, targetSlot, 1, SlotActionType.PICKUP, mc.player);
        }

        // Step 3: Left-click 源槽 — 把 cursor 剩余放回
        mc.interactionManager.clickSlot(syncId, sourceSlot, 0, SlotActionType.PICKUP, mc.player);
    }

    /**
     * 在容器区找到第一个可以接收指定物品的槽位。
     * 优先选择已有同类物品且未满的槽，其次选择空槽。
     *
     * @return 槽位 ID，找不到返回 -1
     */
    private static int findContainerTarget(ScreenHandler handler, Item item, int containerSlotCount) {
        int emptySlot = -1;
        for (int i = 0; i < containerSlotCount; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) {
                if (emptySlot < 0) emptySlot = i;
            } else if (stack.getItem() == item && stack.getCount() < stack.getMaxCount()) {
                return i; // 同类未满槽优先
            }
        }
        return emptySlot;
    }

    // ==================== 内部：容器关闭 ====================

    /** 关闭当前容器窗口（走正规路径，Grim CompensatedInventory 同步）。 */
    private void closeContainerSilently() {
        if (mc.player != null
            && mc.player.currentScreenHandler != null
            && mc.player.currentScreenHandler.syncId != 0) {
            mc.player.closeHandledScreen();
        }
    }

    // ==================== 内部：容器识别 ====================

    private boolean isWorldBlockSimpleContainer(BlockPos pos) {
        return isSupportedContainer(mc.world.getBlockEntity(pos));
    }

    /**
     * BlockEntity 是否为 MVP 支持的简单容器。
     * <p>发射器（含投掷器 — DropperBE extends DispenserBE）、漏斗、木桶。
     */
    public static boolean isSupportedContainer(BlockEntity be) {
        return be instanceof DispenserBlockEntity
            || be instanceof HopperBlockEntity
            || be instanceof BarrelBlockEntity;
    }

    /** 容器位置对应的物品 icon（通过世界 BlockEntity 推断）。 */
    private Item getContainerIcon(BlockPos pos) {
        if (mc.world == null) return null;
        BlockEntity be = mc.world.getBlockEntity(pos);
        return be != null ? be.getCachedState().getBlock().asItem() : null;
    }

    // ==================== 内部：物品匹配 ====================

    /** 物品列表按类型汇总数量（忽略空槽、NBT/附魔差异）。 */
    private static Map<Item, Integer> aggregateItems(List<ItemStack> stacks) {
        Map<Item, Integer> map = new HashMap<>();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                map.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return map;
    }

    /** 玩家背包中是否持有任何所需物品（存在性预检查）。 */
    private boolean hasAnyNeededItem(Map<Item, Integer> needs) {
        for (Item item : needs.keySet()) {
            if (InvUtils.find(item).found()) return true;
        }
        return false;
    }

    /**
     * 玩家是否有移动输入（WASD 按键）。
     * <p>用于规避 Grim MultiActionsC (1.21.2+) 的 knownInput.moving() 检测：
     * 容器 CLICK_WINDOW 到达服务端时如果 knownInput 仍报告移动 → 标记。
     */
    private static boolean isPlayerMovingInput() {
        return mc.options != null
            && (mc.options.forwardKey.isPressed()
                || mc.options.backKey.isPressed()
                || mc.options.leftKey.isPressed()
                || mc.options.rightKey.isPressed());
    }
}
