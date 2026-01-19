/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.player;

import meteordevelopment.meteorclient.mixininterface.IClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * 物品切换辅助工具类 - 参考Litematica InventoryUtils的实现
 *
 * 核心改进点：
 * 1. 不依赖meteor的quickSwap（有问题）
 * 2. 直接进行shift-click操作，让物品从背包转移到快捷栏
 * 3. 完整同步到服务器
 */
public class ItemSwitchHelper {
    private static int previousSlot = -1;

    /**
     * 查找并切换到指定物品
     *
     * 逻辑流程（参考Litematica）：
     * 1. 快捷栏中找到 → 直接切换 selectedSlot
     * 2. 背包中找到 → Shift-click 转移到快捷栏 → 再切换 selectedSlot
     * 3. 副手中找到 → 返回成功（副手自动可用）
     * 4. 都未找到 → 返回失败
     *
     * @param targetItem 目标物品
     * @param allowInventory 是否允许从背包转移
     * @param trackSwap 是否记录原始槽位以便恢复
     * @return 切换是否成功
     */
    public static boolean switchToItem(Item targetItem, boolean allowInventory, boolean trackSwap) {
        if (mc.player == null) return false;

        PlayerInventory inventory = mc.player.getInventory();
        int currentSlot = inventory.selectedSlot;

        // 第一步：查找物品位置
        FindItemResult result = allowInventory ?
            InvUtils.find(targetItem) :
            InvUtils.findInHotbar(targetItem);

        if (!result.found()) {
            return false;
        }

        int targetSlot = result.slot();

        // 记录原始槽位（如果需要恢复）
        if (trackSwap && previousSlot == -1) {
            previousSlot = currentSlot;
        }

        // 第二步：根据位置执行切换/转移

        // 情况1：快捷栏 (0-8) - 直接切换
        if (targetSlot >= 0 && targetSlot <= 8) {
            inventory.selectedSlot = targetSlot;
            syncSelectedSlot();
            return true;
        }

        // 情况2：副手 (40) - 无需切换，副手自动可用
        if (targetSlot == SlotUtils.OFFHAND) {
            return true;
        }

        // 情况3：背包 (9-35) - 需要转移到快捷栏
        if (allowInventory && targetSlot >= 9 && targetSlot <= 35) {
            return transferInventoryItemToHotbar(targetSlot);
        }

        return false;
    }

    /**
     * 将背包物品转移到快捷栏
     *
     * 参考Litematica逻辑：
     * 1. 找一个空的快捷栏位，或使用当前选中位置
     * 2. 通过Shift-Click将物品转移
     * 3. 设置快捷栏选中
     * 4. 同步到服务器
     *
     * @param inventorySlot 背包槽位 (9-35)
     * @return 转移是否成功
     */
    private static boolean transferInventoryItemToHotbar(int inventorySlot) {
        if (mc.player == null || mc.world == null) {
            return false;
        }

        PlayerInventory inventory = mc.player.getInventory();
        int currentSlot = inventory.selectedSlot;

        // 获取物品堆栈（用于后续验证）
        ItemStack sourceStack = inventory.getStack(inventorySlot);
        if (sourceStack.isEmpty()) {
            return false;
        }

        // 第一步：找一个合适的快捷栏位置
        int targetHotbarSlot = findBestHotbarSlot(currentSlot);
        if (targetHotbarSlot == -1) {
            return false;  // 无法找到合适位置
        }

        // 第二步：通过Shift-Click进行物品转移
        // 在容器菜单中进行shift-click操作
        // inventorySlot是背包中的真实槽位ID
        if (mc.interactionManager != null && mc.player.currentScreenHandler != null) {
            // 获取容器中对应背包槽位的实际槽位ID
            // 背包槽位 9-35 在容器菜单中的槽位对应关系
            int containerSlotId = inventorySlot;

            // 执行Shift-Click操作，让物品自动转移到快捷栏
            mc.interactionManager.clickSlot(
                mc.player.currentScreenHandler.syncId,
                containerSlotId,
                0,  // mouseButton
                SlotActionType.QUICK_MOVE,  // Shift-Click
                mc.player
            );

            // 给服务器一点时间处理操作
            try {
                Thread.sleep(5);  // 5ms延迟
            } catch (InterruptedException ignored) {}
        }

        // 第三步：切换快捷栏选中
        inventory.selectedSlot = targetHotbarSlot;
        syncSelectedSlot();

        return true;
    }

    /**
     * 查找最合适的快捷栏槽位
     *
     * 优先级（参考Litematica）：
     * 1. 空位 - 优先使用空位
     * 2. 当前选中位置 - 其次覆盖当前位置
     *
     * @param currentSlot 当前选中的快捷栏位置
     * @return 槽位索引(0-8)，未找到返回-1
     */
    private static int findBestHotbarSlot(int currentSlot) {
        if (mc.player == null) {
            return -1;
        }

        // 优先级1：找空的快捷栏位
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) {
                return i;
            }
        }

        // 优先级2：使用当前选中位置
        if (currentSlot >= 0 && currentSlot < 9) {
            return currentSlot;
        }

        // 无法找到合适位置
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

        mc.player.getInventory().selectedSlot = previousSlot;
        syncSelectedSlot();
        previousSlot = -1;

        return true;
    }

    /**
     * 同步快捷栏选中到服务器
     */
    private static void syncSelectedSlot() {
        if (mc.interactionManager != null) {
            ((IClientPlayerInteractionManager) mc.interactionManager).meteor$syncSelected();
        }
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
     * 重置切换状态
     */
    public static void reset() {
        previousSlot = -1;
    }
}
