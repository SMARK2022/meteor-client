package meteordevelopment.meteorclient.utils.printer;

import meteordevelopment.meteorclient.events.packets.InventoryEvent;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.inventory.Inventory;
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
 * ContainerFillManager — 容器物品自动填充状态机
 *
 * <p>与 Printer 的标准行为管线并行运行的子系统。
 * 标准行为处理方块状态差异（单 tick 无状态），本管理器处理容器内容差异（多 tick 有状态）。
 *
 * <h2>工作流程</h2>
 * <ol>
 *   <li><b>扫描阶段</b>：从蓝图 WorldSchematic 读取容器 BlockEntity 的物品清单</li>
 *   <li><b>选择阶段</b>：找到 reach 内未满足的最近容器</li>
 *   <li><b>打开阶段</b>：发送 interactBlock 打开容器，等待服务端 WINDOW_ITEMS</li>
 *   <li><b>填充阶段</b>：收到同步包后，同 tick 批量 QUICK_MOVE 所有缺失物品 + 关闭</li>
 * </ol>
 *
 * <h2>Grim 安全时序</h2>
 * <ul>
 *   <li>打开前停止 sprint（规避 MultiActionsC/D）</li>
 *   <li>全部使用 QUICK_MOVE 点击类型（规避 PacketOrderA 混用检查）</li>
 *   <li>1.13+ 客户端 CLICK_WINDOW 免 Post 检查（无次数限制）</li>
 *   <li>利用 serverOpenedInventoryThisTick 豁免（收到 OPEN_WINDOW 同 tick 发 CLICK_WINDOW）</li>
 * </ul>
 *
 * <h2>支持的容器类型（MVP）</h2>
 * <p>发射器、投掷器、漏斗、木桶。大箱子和特殊容器（潜影盒/熔炉/酿造台）暂不支持。</p>
 */
public class ContainerFillManager {

    // ==================== 状态枚举 ====================

    /**
     * 容器交互状态
     *
     * <pre>
     * 状态转换:
     *   IDLE ──tryPickTarget──→ OPENING ──onInventorySync──→ [executeFillAndClose] → COOLDOWN → IDLE
     *                              │ timeout                        │ failure
     *                              └────→ IDLE ←────────────────────┘
     * </pre>
     */
    public enum State {
        /** 空闲，等待下一个目标 */
        IDLE,
        /** 已发送 interactBlock，等待 OPEN_WINDOW + WINDOW_ITEMS */
        OPENING,
        /** 操作完成/超时，等待一 tick 冷却后恢复（用于 sprint 恢复等） */
        COOLDOWN
    }

    // ==================== 常量 ====================

    /** 等待服务端响应的超时 tick 数（2 秒 = 40 tick） */
    private static final int OPEN_TIMEOUT = 40;

    /** 玩家背包在任何容器 ScreenHandler 中占据的固定槽位数（main 27 + hotbar 9） */
    private static final int PLAYER_INV_SLOTS = 36;

    // ==================== 状态字段 ====================

    private State state = State.IDLE;

    /** 当前操作的目标容器位置 */
    private BlockPos targetPos;

    /** 发送 interactBlock 时的 Printer tickCounter 值 */
    private int sentTick;

    /** 是否由管理器主动停止了 sprint */
    private boolean didStopSprint;

    // ==================== 缓存 ====================

    /**
     * 蓝图容器缓存：蓝图中每个有物品的容器位置 → 按类型汇总的物品需求。
     * <p>每 tick 由 Printer 扫描填充，通过 {@link #beginScan()} / {@link #pruneStaleEntries()} 管理生命周期。
     */
    private final Map<BlockPos, Map<Item, Integer>> schematicCache = new HashMap<>();

    /**
     * 已确认满足蓝图要求的容器位置集合。
     * <p>避免对已满足的容器反复打开探测。
     * 当蓝图容器不再出现在扫描范围内时由 {@link #pruneStaleEntries()} 自动清除。
     */
    private final Set<BlockPos> satisfiedPositions = new HashSet<>();

    /** 本 tick 扫描到的蓝图容器位置（临时集合，用于 prune 过期条目） */
    private final Set<BlockPos> thisTickScanned = new HashSet<>();

    // ==================== 公共接口：扫描 ====================

    /**
     * 注册蓝图容器。每 tick 由 Printer 在扫描循环中调用。
     *
     * <p>仅接受 MVP 支持的简单容器（发射器、投掷器、漏斗、木桶）。
     * 物品按类型汇总存储，忽略槽位顺序和 NBT 差异。
     *
     * @param pos   容器位置（必须 immutable）
     * @param items 蓝图期望的物品列表（直接从 BlockEntity Inventory 读取）
     */
    public void registerSchematicContainer(BlockPos pos, List<ItemStack> items) {
        Map<Item, Integer> needs = aggregateItems(items);
        if (!needs.isEmpty()) {
            schematicCache.put(pos, needs);
        }
        thisTickScanned.add(pos);
    }

    /**
     * 扫描循环开始前调用。清除上一 tick 的扫描记录。
     */
    public void beginScan() {
        thisTickScanned.clear();
    }

    /**
     * 扫描循环结束后调用。移除不再出现在蓝图扫描范围内的过期缓存条目。
     */
    public void pruneStaleEntries() {
        schematicCache.keySet().retainAll(thisTickScanned);
        satisfiedPositions.retainAll(thisTickScanned);
    }

    // ==================== 公共接口：主循环 ====================

    /**
     * 每 tick 主循环，由 Printer.onTickPre 在标准行为管线之后调用。
     *
     * <p>状态转换：
     * <ul>
     *   <li>{@code IDLE} → 搜索最近的未满足容器，找到则打开并转移到 OPENING</li>
     *   <li>{@code OPENING} → 等待服务端响应（由 {@link #onInventorySync} 驱动），超时则重置</li>
     *   <li>{@code COOLDOWN} → 恢复 sprint，回到 IDLE</li>
     * </ul>
     *
     * @param tickCounter Printer 的全局 tick 计数器
     * @param maxReach    最大交互距离（通常 = placeRange）
     */
    public void tick(int tickCounter, double maxReach) {
        if (mc.player == null || mc.world == null) return;

        switch (state) {
            case IDLE -> tryPickTarget(maxReach);
            case OPENING -> {
                if (tickCounter - sentTick > OPEN_TIMEOUT) {
                    // 服务端响应超时，关闭可能残留的窗口，回到 IDLE
                    closeContainerSilently();
                    restoreSprint();
                    state = State.IDLE;
                }
            }
            case COOLDOWN -> {
                restoreSprint();
                state = State.IDLE;
            }
        }
    }

    // ==================== 公共接口：事件回调 ====================

    /**
     * 处理 InventoryS2CPacket 回调。由 Printer 的 InventoryEvent 处理器转发。
     *
     * <p>当状态为 OPENING 时，收到非玩家背包的 WINDOW_ITEMS 即认为目标容器已打开。
     * 利用 Grim 的 serverOpenedInventoryThisTick 豁免，<b>立即在同 tick 内</b>执行全部
     * QUICK_MOVE 点击并关闭容器。
     *
     * @param event Meteor 封装的库存同步事件
     */
    public void onInventorySync(InventoryEvent event) {
        if (state != State.OPENING || mc.player == null) return;

        // syncId == 0 是玩家自身背包，忽略
        int syncId = event.packet.getSyncId();
        if (syncId == 0) return;

        // 读取容器当前内容
        Map<Item, Integer> containerContents = new HashMap<>();
        List<ItemStack> allSlots = event.packet.getContents();

        // 容器窗口的总槽位 = 容器槽位 + 玩家背包(36)
        // 只统计容器自身的槽位（前 N 个）
        int containerSlotCount = Math.max(0, allSlots.size() - PLAYER_INV_SLOTS);
        for (int i = 0; i < containerSlotCount; i++) {
            ItemStack stack = allSlots.get(i);
            if (!stack.isEmpty()) {
                containerContents.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }

        // 执行填充并关闭
        executeFillAndClose(containerContents, containerSlotCount);
    }

    // ==================== 公共接口：查询 ====================

    /** 管理器是否正在进行容器操作（Printer 据此暂停标准管线） */
    public boolean isBusy() {
        return state != State.IDLE;
    }

    /** 当前状态（调试/渲染用） */
    public State getState() {
        return state;
    }

    /** 当前操作目标位置（渲染用，IDLE 时返回 null） */
    public BlockPos getTargetPos() {
        return state != State.IDLE ? targetPos : null;
    }

    /** 蓝图中已注册的容器数量（info string 用） */
    public int getSchematicContainerCount() {
        return schematicCache.size();
    }

    /** 已确认满足的容器数量（info string 用） */
    public int getSatisfiedCount() {
        return satisfiedPositions.size();
    }

    /** 指定位置是否已满足蓝图要求 */
    public boolean isSatisfied(BlockPos pos) {
        return satisfiedPositions.contains(pos);
    }

    /** 获取所有已注册的蓝图容器位置（不可修改视图） */
    public Set<BlockPos> getRegisteredPositions() {
        return Collections.unmodifiableSet(schematicCache.keySet());
    }

    /** 获取指定位置的蓝图物品需求（可为 null） */
    public Map<Item, Integer> getNeedsAt(BlockPos pos) {
        return schematicCache.get(pos);
    }

    /**
     * 获取容器类型分类摘要。
     * <p>返回 containerTypeItem → [satisfied, total] 的映射。
     * 必须在世界可用时调用（通过 mc.world 获取 BlockEntity 类型）。
     *
     * @return 各容器类型的满足/总计数组映射
     */
    public Map<Item, int[]> getTypeSummaries() {
        Map<Item, int[]> result = new LinkedHashMap<>();
        for (BlockPos pos : schematicCache.keySet()) {
            Item icon = getContainerIcon(pos);
            if (icon == null) continue;
            int[] counts = result.computeIfAbsent(icon, k -> new int[2]);
            counts[1]++; // total
            if (satisfiedPositions.contains(pos)) counts[0]++; // satisfied
        }
        return result;
    }

    /**
     * 聚合所有未满足容器的物品总需求。
     * <p>返回各物品类型在所有未满足容器中缺失的总量。
     *
     * @return item → totalNeeded 映射
     */
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

    /**
     * 获取所有未满足的容器位置集合（用于 3D 高亮渲染）。
     */
    public Set<BlockPos> getUnsatisfiedPositions() {
        Set<BlockPos> result = new HashSet<>();
        for (BlockPos pos : schematicCache.keySet()) {
            if (!satisfiedPositions.contains(pos)) {
                result.add(pos);
            }
        }
        return result;
    }

    /**
     * 获取容器位置对应的物品 icon（通过世界 BlockEntity 类型推断）。
     */
    private Item getContainerIcon(BlockPos pos) {
        if (mc.world == null) return null;
        BlockEntity be = mc.world.getBlockEntity(pos);
        if (be == null) return null;
        return be.getCachedState().getBlock().asItem();
    }

    // ==================== 公共接口：生命周期 ====================

    /** 完全重置状态。Printer 激活/关闭时调用。 */
    public void reset() {
        if (state == State.OPENING) {
            closeContainerSilently();
        }
        state = State.IDLE;
        targetPos = null;
        sentTick = 0;
        schematicCache.clear();
        satisfiedPositions.clear();
        thisTickScanned.clear();
        restoreSprint();
    }

    // ==================== 状态机：IDLE → OPENING ====================

    /**
     * 搜索 reach 内最近的未满足蓝图容器，停 sprint 后打开。
     */
    private void tryPickTarget(double maxReach) {
        Vec3d eyePos = mc.player.getEyePos();
        double maxReachSq = maxReach * maxReach;

        BlockPos bestPos = null;
        double bestDistSq = Double.MAX_VALUE;

        for (var entry : schematicCache.entrySet()) {
            BlockPos pos = entry.getKey();

            // 已确认满足，跳过
            if (satisfiedPositions.contains(pos)) continue;

            // 距离检查
            double distSq = eyePos.squaredDistanceTo(Vec3d.ofCenter(pos));
            if (distSq > maxReachSq) continue;

            // 世界中方块必须仍然是容器
            if (!isWorldBlockSimpleContainer(pos)) continue;

            // 选择最近的
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestPos = pos;
            }
        }

        if (bestPos == null) return;

        // 检查玩家背包中是否有任何所需物品（避免无意义打开）
        Map<Item, Integer> needs = schematicCache.get(bestPos);
        if (needs != null && !hasAnyNeededItem(needs)) return;

        // Grim 安全：停止 sprint（MultiActionsC/D 要求）
        stopSprint();

        // 向服务端发送 interactBlock
        sendOpenContainer(bestPos);

        targetPos = bestPos;
        sentTick = mc.player.age;
        state = State.OPENING;
    }

    // ==================== 状态机：OPENING → 填充 → COOLDOWN ====================

    /**
     * 计算缺失物品，批量 QUICK_MOVE，关闭容器。全部在同一 tick 内完成。
     *
     * <p>Grim 安全性保证：
     * <ul>
     *   <li>全部使用 QUICK_MOVE（不混用 PICKUP → PacketOrderA 安全）</li>
     *   <li>1.13+ CLICK_WINDOW 免 Post 检查</li>
     *   <li>serverOpenedInventoryThisTick 豁免 MultiActionsC</li>
     *   <li>无 per-tick CLICK_WINDOW 数量限制</li>
     * </ul>
     *
     * @param containerContents 容器当前物品（按类型汇总）
     * @param containerSlotCount 容器自身槽位数
     */
    private void executeFillAndClose(Map<Item, Integer> containerContents, int containerSlotCount) {
        Map<Item, Integer> needs = schematicCache.get(targetPos);

        if (needs == null) {
            closeContainerSilently();
            state = State.COOLDOWN;
            return;
        }

        // 计算差异：蓝图需要 N 个，容器已有 M 个 → 缺 max(0, N-M)
        boolean allSatisfied = true;
        ScreenHandler handler = mc.player.currentScreenHandler;

        if (handler == null || handler.syncId == 0) {
            // 容器窗口未正确打开，放弃
            state = State.COOLDOWN;
            return;
        }

        for (var entry : needs.entrySet()) {
            Item item = entry.getKey();
            int required = entry.getValue();
            int existing = containerContents.getOrDefault(item, 0);
            int deficit = required - existing;

            if (deficit <= 0) continue;

            allSatisfied = false;
            int remaining = deficit;

            // 在容器窗口的玩家背包区域中查找该物品并 QUICK_MOVE
            // 玩家背包区域 = containerSlotCount ~ handler.slots.size()-1
            int totalSlots = handler.slots.size();
            for (int slotId = containerSlotCount; slotId < totalSlots && remaining > 0; slotId++) {
                ItemStack slotStack = handler.getSlot(slotId).getStack();
                if (slotStack.isEmpty() || slotStack.getItem() != item) continue;

                InvUtils.shiftClick().slotId(slotId);
                remaining -= Math.min(slotStack.getCount(), remaining);
            }
        }

        if (allSatisfied) {
            satisfiedPositions.add(targetPos);
        }

        // 关闭容器
        closeContainerSilently();
        state = State.COOLDOWN;
    }

    // ==================== 容器交互 ====================

    /**
     * 向服务端发送 interactBlock 打开容器。
     * 点击容器顶面中心，使用主手（空手也可以打开容器）。
     */
    private void sendOpenContainer(BlockPos pos) {
        Vec3d hitVec = Vec3d.ofCenter(pos).add(0, 0.5, 0);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, pos, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
    }

    /**
     * 程序化关闭当前容器窗口。
     * <p>使用 {@code closeHandledScreen()} 走正规路径，确保 Grim CompensatedInventory 同步。
     */
    private void closeContainerSilently() {
        if (mc.player != null
            && mc.player.currentScreenHandler != null
            && mc.player.currentScreenHandler.syncId != 0) {
            mc.player.closeHandledScreen();
        }
    }

    // ==================== Sprint 控制 ====================

    /** 停止 sprint（Grim MultiActionsC/D 要求操作容器时不能 sprinting） */
    private void stopSprint() {
        if (mc.player != null && mc.player.isSprinting()) {
            mc.player.setSprinting(false);
            didStopSprint = true;
        }
    }

    /** 清除 sprint 停止标记。Sprint 恢复由玩家移动输入自然触发。 */
    private void restoreSprint() {
        didStopSprint = false;
    }

    // ==================== 容器识别 ====================

    /**
     * 判断世界中该位置是否为 MVP 支持的简单容器方块。
     * <p>通过 BlockEntity 类型判断（与蓝图端一致）。
     */
    private boolean isWorldBlockSimpleContainer(BlockPos pos) {
        BlockEntity be = mc.world.getBlockEntity(pos);
        return isSupportedContainer(be);
    }

    /**
     * 判断 BlockEntity 是否为受支持的简单容器。
     * <p>MVP 范围：发射器（含投掷器）、漏斗、木桶。
     * <p>排除：大箱子（双 BE 合并复杂）、潜影盒（动画延迟）、熔炉系列（槽位限制）。
     *
     * @param be 方块实体，可为 null
     * @return 是否为受支持的容器
     */
    public static boolean isSupportedContainer(BlockEntity be) {
        return be instanceof DispenserBlockEntity  // DropperBlockEntity extends DispenserBlockEntity
            || be instanceof HopperBlockEntity
            || be instanceof BarrelBlockEntity;
    }

    // ==================== 物品匹配 ====================

    /**
     * 将物品列表按类型汇总数量。忽略空槽、NBT/附魔差异。
     *
     * @param stacks 物品列表（可含空 ItemStack）
     * @return item → totalCount 映射
     */
    private static Map<Item, Integer> aggregateItems(List<ItemStack> stacks) {
        Map<Item, Integer> map = new HashMap<>();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                map.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return map;
    }

    /**
     * 检查玩家背包中是否持有任何蓝图容器需要的物品。
     * <p>仅做存在性检查，避免在无相关物品时无意义地打开容器。
     */
    private boolean hasAnyNeededItem(Map<Item, Integer> needs) {
        for (Item item : needs.keySet()) {
            FindItemResult result = InvUtils.find(item);
            if (result.found()) return true;
        }
        return false;
    }
}
