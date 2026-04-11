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

    // ==================== 常量 ====================

    /** 服务端响应超时（40 tick ≈ 2s，覆盖高延迟场景） */
    private static final int OPEN_TIMEOUT = 40;

    /** 容器窗口中玩家背包占的固定槽位数（main 27 + hotbar 9） */
    private static final int PLAYER_INV_SLOTS = 36;

    // ==================== 状态字段 ====================

    private State state = State.IDLE;
    private BlockPos targetPos;
    private int openTick;

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

    // ==================== 蓝图缓存 ====================

    /** 蓝图容器需求：pos → {item → count}。每 tick 由 Printer 扫描填充。 */
    private final Map<BlockPos, Map<Item, Integer>> schematicCache = new HashMap<>();

    /** 已确认内容满足蓝图要求的容器位置。 */
    private final Set<BlockPos> satisfiedPositions = new HashSet<>();

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

    /** 扫描循环结束后调用，移除超出扫描范围的过期条目。 */
    public void pruneStaleEntries() {
        schematicCache.keySet().retainAll(thisTickScanned);
        satisfiedPositions.retainAll(thisTickScanned);
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
     */
    public void tick(int tickCounter, double maxReach, boolean strict,
                     boolean rotateEnabled, boolean sneakForced, Runnable sneakClearer) {
        if (mc.player == null || mc.world == null) return;

        this.maxReach = maxReach;

        // 处于激活状态时持续压制 sprint（MultiActionsC/D 要求全程 !sprinting）
        if (state == State.PREPARING || state == State.ARMED_OPEN || state == State.OPENING) {
            if (mc.player.isSprinting()) mc.player.setSprinting(false);
        }

        switch (state) {
            case IDLE -> pickTarget(maxReach);

            case PREPARING -> {
                // 第一步：清除 Printer 遗留的强制潜行
                if (sneakForced) {
                    sneakClearer.run();
                    return; // 等下一 tick sneak 状态生效
                }

                // 第二步：确保玩家当前不处于潜行（用户手动潜行也要等）
                if (mc.player.isSneaking()) {
                    return; // 等玩家松开 sneak
                }

                // 第三步：规划容器打开交互（真实几何面 + LOS + reach）
                armedInteraction = InteractionPlanner.planSelfInteraction(
                    mc, targetPos, strict, true, maxReach
                );

                if (armedInteraction == null) {
                    // 当前无法找到有效交互面（被遮挡/超出 reach）
                    state = State.COOLDOWN;
                    return;
                }

                // 成功规划 → 进入 ARMED_OPEN
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
                // sprint 恢复由玩家移动输入自然触发，此处仅清除标志
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

    /**
     * ARMED_OPEN 态的交互规划（Printer 用于提交 Rotations）。
     * 非 ARMED_OPEN 态返回 null。
     */
    public ActionPlan.Interaction getArmedInteraction() {
        return state == State.ARMED_OPEN ? armedInteraction : null;
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
    public int getSatisfiedCount() { return satisfiedPositions.size(); }

    public boolean isSatisfied(BlockPos pos) {
        return satisfiedPositions.contains(pos);
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
            if (satisfiedPositions.contains(pos)) counts[0]++;
        }
        return result;
    }

    /** 所有未满足容器的物品需求汇总。 */
    public Map<Item, Integer> getAllUnsatisfiedItemNeeds() {
        Map<Item, Integer> result = new LinkedHashMap<>();
        for (var entry : schematicCache.entrySet()) {
            if (satisfiedPositions.contains(entry.getKey())) continue;
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
            if (!satisfiedPositions.contains(pos)) result.add(pos);
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
        suppressScreen = false;
        armedInteraction = null;
        schematicCache.clear();
        satisfiedPositions.clear();
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
            if (satisfiedPositions.contains(pos)) continue;

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

        // 停止 sprint，进入 PREPARING
        if (mc.player.isSprinting()) mc.player.setSprinting(false);
        targetPos = best;
        state = State.PREPARING;
    }

    // ==================== 内部：填充 + 关闭 ====================

    /**
     * 读取当前 handler 的容器槽 → 计算差异 → 批量 QUICK_MOVE → 关闭容器。
     * 全部在 onInventorySync 同一 tick 完成。
     *
     * <p>每次 shift-click 后从 handler 重读容器实际数量，用真实增量驱动 deficit，
     * 避免 QUICK_MOVE 整组搬运导致的过填/欠填。
     *
     * <p>填充结束后 post-fill 重算：从 handler 最终状态准确判定 satisfied。
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

        // ── 按物品逐类填充 ──
        for (var entry : needs.entrySet()) {
            Item item = entry.getKey();
            int required = entry.getValue();

            // 每次 shift-click 后都从 handler 重读容器实际数量
            int existing = countItemInContainer(handler, item, containerSlotCount);
            if (existing >= required) continue;

            for (int slot = containerSlotCount; slot < totalSlots; slot++) {
                ItemStack stack = handler.getSlot(slot).getStack();
                if (stack.isEmpty() || stack.getItem() != item) continue;

                InvUtils.shiftClick().slotId(slot);

                // 重读容器区，用真实数量判断 deficit（而非纸面 -= stack.getCount()）
                existing = countItemInContainer(handler, item, containerSlotCount);
                if (existing >= required) break;
            }
        }

        // ── post-fill 重算：从 handler 最终状态准确判定 satisfied ──
        boolean allSatisfied = true;
        for (var entry : needs.entrySet()) {
            int existing = countItemInContainer(handler, entry.getKey(), containerSlotCount);
            if (existing < entry.getValue()) {
                allSatisfied = false;
                break;
            }
        }

        if (allSatisfied) satisfiedPositions.add(targetPos);

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
}
