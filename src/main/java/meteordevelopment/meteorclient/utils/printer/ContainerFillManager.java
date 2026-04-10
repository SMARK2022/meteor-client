package meteordevelopment.meteorclient.utils.printer;

import meteordevelopment.meteorclient.events.packets.InventoryEvent;
import meteordevelopment.meteorclient.utils.player.InvUtils;
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
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * ContainerFillManager — 容器物品自动填充四态状态机
 *
 * <p>与 Printer 标准行为管线并行运行的有状态子系统。
 * 标准行为处理<b>方块状态</b>差异（单 tick 无状态），
 * 本管理器处理<b>容器内容</b>差异（多 tick 有状态）。
 *
 * <h2>Grim 安全时序（严格遵循约束报告）</h2>
 * <pre>
 * Tick N     [PREPARING]  停止 sprint → ENTITY_ACTION(STOP_SPRINTING)
 * Tick N+1   [OPENING]    interactBlock → PLAYER_BLOCK_PLACEMENT
 *            ⏳ 等待服务端 OPEN_WINDOW + WINDOW_ITEMS（1~3 tick RTT）
 * Tick N+1+RTT [onInventorySync]  同 tick 内批量 CLICK_WINDOW + CLOSE_WINDOW
 * Tick N+2+RTT [COOLDOWN]  恢复 sprint → IDLE
 * </pre>
 *
 * <h2>关键 Grim 约束对照</h2>
 * <ul>
 *   <li><b>PacketOrderF</b>: sprint 切换与 interactBlock 分 tick → PREPARING 态隔离</li>
 *   <li><b>MultiActionsC</b>: CLICK_WINDOW 时 !sprinting → PREPARING/OPENING 持续压制 sprint</li>
 *   <li><b>MultiActionsD</b>: CLOSE_WINDOW 时 !sprinting → 同上</li>
 *   <li><b>PacketOrderA</b>: 全部使用 QUICK_MOVE，不混用其他点击类型</li>
 *   <li><b>Post(1.13+)</b>: CLICK_WINDOW 免检 → 无数量限制</li>
 * </ul>
 *
 * <h2>支持的容器类型（MVP）</h2>
 * <p>发射器（含投掷器 — DropperBE extends DispenserBE）、漏斗、木桶。</p>
 */
public class ContainerFillManager {

    // ==================== 状态枚举 ====================

    /**
     * 四态状态机。
     *
     * <pre>
     * IDLE ──pickTarget──→ PREPARING ──(next tick)──→ OPENING ──onInventorySync──→ COOLDOWN ──→ IDLE
     *                       (停sprint)    (interactBlock)     (fill+close)        (sprint恢复)
     *                                                  │ timeout
     *                                                  └──→ COOLDOWN
     * </pre>
     */
    public enum State {
        /** 空闲，搜索下一个目标 */
        IDLE,
        /** 已选定目标并停止 sprint，等待下一 tick 发送 interactBlock（PacketOrderF 要求分 tick） */
        PREPARING,
        /** 已发送 interactBlock，等待服务端 WINDOW_ITEMS 回包 */
        OPENING,
        /** 操作完成或超时，等待一 tick 冷却后恢复 sprint */
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
     * 容器屏幕抑制标志。
     * <p>为 true 时，Printer 应拦截 OpenScreenEvent 阻止容器 GUI 弹出。
     * 操作完成后自动清除。
     */
    private boolean suppressScreen;

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
     * 每 tick 调用，驱动四态状态机。
     * <p>PREPARING / OPENING 期间持续压制 sprint，防止玩家移动输入重新触发。
     *
     * @param tickCounter Printer 全局 tick 计数器
     * @param maxReach    最大交互距离
     */
    public void tick(int tickCounter, double maxReach) {
        if (mc.player == null || mc.world == null) return;

        // 处于激活状态时持续压制 sprint（MultiActionsC/D 要求全程 !sprinting）
        if (state == State.PREPARING || state == State.OPENING) {
            if (mc.player.isSprinting()) mc.player.setSprinting(false);
        }

        switch (state) {
            case IDLE -> pickTarget(maxReach);

            case PREPARING -> {
                // 上一 tick 已停 sprint，本 tick 安全发送 interactBlock（PacketOrderF 安全）
                sendOpenContainer(targetPos);
                openTick = mc.player.age;
                suppressScreen = true;
                state = State.OPENING;
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
                state = State.IDLE;
            }
        }
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

        Map<Item, Integer> containerContents = new HashMap<>();
        for (int i = 0; i < containerSlotCount; i++) {
            ItemStack stack = allSlots.get(i);
            if (!stack.isEmpty()) {
                containerContents.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }

        fillAndClose(containerContents, containerSlotCount);
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
        schematicCache.clear();
        satisfiedPositions.clear();
        thisTickScanned.clear();
    }

    // ==================== 内部：IDLE → PREPARING ====================

    /**
     * 搜索 reach 内最近的可操作未满足容器。
     * 找到后停止 sprint 并进入 PREPARING（PacketOrderF 要求 sprint 切换与 interact 分 tick）。
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

        // 停止 sprint，进入 PREPARING（下一 tick 才发 interactBlock）
        if (mc.player.isSprinting()) mc.player.setSprinting(false);
        targetPos = best;
        state = State.PREPARING;
    }

    // ==================== 内部：填充 + 关闭 ====================

    /**
     * 计算差异 → 批量 QUICK_MOVE → 关闭容器。全部在 onInventorySync 同一 tick 完成。
     *
     * <p>Grim 安全保证：
     * <ul>
     *   <li>全部 QUICK_MOVE（PacketOrderA 安全，不混用 PICKUP）</li>
     *   <li>1.13+ CLICK_WINDOW 免 Post 检查，无数量限制</li>
     *   <li>serverOpenedInventoryThisTick 豁免 MultiActionsC</li>
     * </ul>
     */
    private void fillAndClose(Map<Item, Integer> containerContents, int containerSlotCount) {
        Map<Item, Integer> needs = schematicCache.get(targetPos);
        ScreenHandler handler = mc.player.currentScreenHandler;

        if (needs == null || handler == null || handler.syncId == 0) {
            closeContainerSilently();
            state = State.COOLDOWN;
            return;
        }

        boolean allSatisfied = true;
        int totalSlots = handler.slots.size();

        for (var entry : needs.entrySet()) {
            Item item = entry.getKey();
            int deficit = entry.getValue() - containerContents.getOrDefault(item, 0);
            if (deficit <= 0) continue;

            allSatisfied = false;
            int remaining = deficit;

            // 在容器窗口的玩家背包区域查找并 QUICK_MOVE
            for (int slot = containerSlotCount; slot < totalSlots && remaining > 0; slot++) {
                ItemStack stack = handler.getSlot(slot).getStack();
                if (stack.isEmpty() || stack.getItem() != item) continue;

                InvUtils.shiftClick().slotId(slot);
                remaining -= Math.min(stack.getCount(), remaining);
            }
        }

        if (allSatisfied) satisfiedPositions.add(targetPos);

        closeContainerSilently();
        state = State.COOLDOWN;
    }

    // ==================== 内部：交互辅助 ====================

    /** 发送 interactBlock 打开容器（点击顶面中心）。 */
    private void sendOpenContainer(BlockPos pos) {
        Vec3d hit = Vec3d.ofCenter(pos).add(0, 0.5, 0);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
            new BlockHitResult(hit, Direction.UP, pos, false));
    }

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
