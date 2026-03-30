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
     * 将背包物品转移到快捷栏第9格（槽位8）并选中
     *
     * 策略：
     * - 使用 move() 执行两步操作模拟物品拖拽：
     *   move() 使用 PICKUP 操作但设置 two=true，会执行两次点击
     *   第1次：点击背包物品，将其捡起到光标
     *   第2次：点击快捷栏槽位8，将物品放下
     * - 然后切换到槽位8选中该物品
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

        // 执行两步点击操作，模拟从背包拖拽物品到快捷栏槽位8的操作
        // move() 会执行两次 PICKUP 点击：第一次从来源，第二次到目标
        InvUtils.move().from(inventorySlot).toHotbar(8);

        // 立即切换到槽位8选中转移过来的物品
        InvUtils.swap(8, false);
        didInventoryTransfer = true;
        return true;
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

    /**
     * 检查本 tick 是否刚执行过背包→热栏转移
     * 用于让 Printer 在转移后等待 1 tick 再放置，避免物品同步未稳定
     */
    public static boolean didInventoryTransferThisTick() {
        return didInventoryTransfer;
    }

    /**
     * 重置背包转移标记（在下一 tick 开始时调用）
     */
    public static void resetTransferFlag() {
        didInventoryTransfer = false;
    }
}

