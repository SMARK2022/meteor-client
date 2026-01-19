/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.player;

import meteordevelopment.meteorclient.mixininterface.IClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 物品切换辅助工具类 - 使用 Meteor 原生方法
 *
 * 功能说明：
 * 1. 快捷栏物品：使用 InvUtils.swap() 直接切换 selectedSlot
 * 2. 背包物品：使用 InvUtils.shiftClick() (QUICK_MOVE) 转移到快捷栏
 * 3. 副手物品：无需切换，直接可用
 *
 * 关键修复：
 * - 使用 Meteor 的 InvUtils.swap() 进行快捷栏切换（已验证可用）
 * - 使用 shiftClick() 进行背包到快捷栏的转移
 */
public class ItemSwitchHelper {
    // 记录切换前的槽位
    private static int previousSlot = -1;
    // 记录是否执行过背包转移（用于决定是否需要恢复）
    private static boolean didInventoryTransfer = false;

    /**
     * 查找并切换到指定物品
     *
     * @param targetItem 目标物品
     * @param allowInventory 是否允许从背包转移
     * @param trackSwap 是否记录原始槽位以便恢复
     * @return 切换是否成功
     */
    public static boolean switchToItem(Item targetItem, boolean allowInventory, boolean trackSwap) {
        if (mc.player == null) {
            return false;
        }

        // 先检查物品是否已经在主手
        if (isItemInMainHand(targetItem)) {
            return true;
        }

        // 检查副手
        if (mc.player.getOffHandStack().getItem() == targetItem) {
            return true; // 副手物品可以直接使用
        }

        PlayerInventory inventory = mc.player.getInventory();
        int currentSlot = inventory.selectedSlot;

        // 查找物品位置
        // 优先在快捷栏中查找，然后在背包中查找
        FindItemResult hotbarResult = InvUtils.findInHotbar(targetItem);

        // 情况1：物品在快捷栏中 (0-8)
        if (hotbarResult.found() && hotbarResult.slot() >= 0 && hotbarResult.slot() <= 8) {
            int targetSlot = hotbarResult.slot();

            // 记录原始槽位
            if (trackSwap && previousSlot == -1) {
                previousSlot = currentSlot;
            }

            // 使用 InvUtils.swap() 切换到目标槽位
            InvUtils.swap(targetSlot, false);
            return true;
        }

        // 情况2：物品在副手 (槽位 45)
        if (hotbarResult.found() && hotbarResult.slot() == SlotUtils.OFFHAND) {
            return true; // 副手物品无需切换
        }

        // 情况3：物品在背包中 (9-35)
        if (allowInventory) {
            FindItemResult inventoryResult = InvUtils.find(targetItem);

            if (inventoryResult.found() && inventoryResult.slot() >= 9 && inventoryResult.slot() <= 35) {
                int inventorySlot = inventoryResult.slot();

                // 记录原始槽位
                if (trackSwap && previousSlot == -1) {
                    previousSlot = currentSlot;
                }

                // 将背包物品转移到快捷栏
                return transferInventoryItemToHotbar(inventorySlot, targetItem);
            }
        }

        return false;
    }

    /**
     * 将背包物品转移到快捷栏并选中
     *
     * 使用 Shift-Click (QUICK_MOVE) 操作：
     * - Shift-Click 背包物品会自动转移到快捷栏的空槽位
     * - 如果快捷栏已满，转移会失败
     *
     * @param inventorySlot 背包槽位索引 (9-35)
     * @param targetItem 目标物品（用于验证）
     * @return 转移是否成功
     */
    private static boolean transferInventoryItemToHotbar(int inventorySlot, Item targetItem) {
        if (mc.player == null || mc.interactionManager == null) {
            return false;
        }

        PlayerInventory inventory = mc.player.getInventory();

        // 检查源物品是否存在
        ItemStack sourceStack = inventory.getStack(inventorySlot);
        if (sourceStack.isEmpty() || sourceStack.getItem() != targetItem) {
            return false;
        }

        // 方案1：找一个空的快捷栏槽位，使用 SWAP 操作交换
        int emptyHotbarSlot = findEmptyHotbarSlot();

        if (emptyHotbarSlot != -1) {
            // 使用 quickSwap 将背包物品与空快捷栏槽位交换
            // quickSwap 使用 SWAP 操作，from 是快捷栏索引(0-8)，to 是容器槽位ID
            InvUtils.quickSwap().fromHotbar(emptyHotbarSlot).to(inventorySlot);

            // 切换到该槽位
            InvUtils.swap(emptyHotbarSlot, false);
            didInventoryTransfer = true;
            return true;
        }

        // 方案2：没有空槽位，使用 Shift-Click 尝试转移
        // Shift-Click 会把物品移动到快捷栏的第一个可用槽位
        InvUtils.shiftClick().slot(inventorySlot);

        // 等待一个刻让转移完成，然后查找物品在快捷栏的位置
        // 由于这是同步操作，物品应该已经转移了
        FindItemResult newResult = InvUtils.findInHotbar(targetItem);
        if (newResult.found() && newResult.slot() >= 0 && newResult.slot() <= 8) {
            InvUtils.swap(newResult.slot(), false);
            didInventoryTransfer = true;
            return true;
        }

        return false;
    }

    /**
     * 查找快捷栏中的空槽位
     *
     * @return 空槽位索引 (0-8)，如果没有空槽位返回 -1
     */
    private static int findEmptyHotbarSlot() {
        if (mc.player == null) return -1;

        PlayerInventory inventory = mc.player.getInventory();

        for (int i = 0; i <= 8; i++) {
            if (inventory.getStack(i).isEmpty()) {
                return i;
            }
        }

        return -1;
    }

    /**
     * 恢复到之前的槽位
     *
     * @return 恢复是否成功
     */
    public static boolean swapBack() {
        if (previousSlot == -1 || mc.player == null) {
            return false;
        }

        InvUtils.swap(previousSlot, false);
        previousSlot = -1;
        didInventoryTransfer = false;

        return true;
    }

    /**
     * 检查物品是否在主手
     */
    public static boolean isItemInMainHand(Item targetItem) {
        if (mc.player == null) {
            return false;
        }
        return mc.player.getMainHandStack().getItem() == targetItem;
    }

    /**
     * 检查物品是否在手中（主手或副手）
     */
    public static boolean isItemInHands(Item targetItem) {
        if (mc.player == null) {
            return false;
        }

        return mc.player.getMainHandStack().getItem() == targetItem ||
               mc.player.getOffHandStack().getItem() == targetItem;
    }

    /**
     * 获取当前主手物品
     */
    public static Item getMainHandItem() {
        if (mc.player == null) {
            return null;
        }
        return mc.player.getMainHandStack().getItem();
    }

    /**
     * 重置切换状态
     */
    public static void reset() {
        previousSlot = -1;
        didInventoryTransfer = false;
    }
}

