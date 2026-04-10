package meteordevelopment.meteorclient.utils.printer.behavior;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.printer.*;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.item.ShovelItem;
import net.minecraft.state.property.Properties;

/**
 * CampfireBehavior - 营火点火 / 熄灭行为
 *
 * <p>处理营火（campfire / soul_campfire）的 {@link Properties#LIT} 属性修正：
 * <ul>
 *   <li>熄灭（LIT=true → false）：手持铲子右键营火，由 {@code ShovelItem.useOnBlock()} 处理</li>
 *   <li>点火（LIT=false → true）：手持打火石 / 火焰弹右键营火，由 {@code FlintAndSteelItem / FireChargeItem.useOnBlock()} 处理</li>
 * </ul>
 *
 * <p>产出 {@link ActionPlan.UseItemOnBlock} 类型的动作计划。
 *
 * <p>不变量属性：{@code HORIZONTAL_FACING}（放置朝向不可通过交互修改）。
 * 不关心：{@code SIGNAL_FIRE}（由下方干草捆自动决定）、{@code WATERLOGGED}（由 WaterlogBehavior 处理）。
 *
 * <p>注意事项：
 * <ul>
 *   <li>铲子熄灭交互拒绝 {@code Direction.DOWN} 面（vanilla 限制），
 *       但 {@link InteractionPlanner} 默认 UP 优先，实际不受影响。</li>
 *   <li>含水营火无法点燃（{@code CampfireBlock.canBeLit()} 检查 WATERLOGGED=false），
 *       需先由 WaterlogBehavior 处理含水状态。</li>
 *   <li>火焰弹为单次消耗品，优先使用打火石。</li>
 * </ul>
 */
public class CampfireBehavior implements PrinterBehavior {

    @Override
    public Group group() {
        return Group.INTERACTABLE;
    }

    @Override
    public Key key() {
        return Key.CAMPFIRE_LIT;
    }

    // ==================== 核心三问 ====================

    @Override
    public boolean supports(PrinterTask task) {
        BlockState desired = task.desiredState();
        BlockState current = task.currentState();

        // 双方必须是同一类型的营火（campfire / soul_campfire）
        if (!isCampfire(desired.getBlock())) return false;
        if (desired.getBlock() != current.getBlock()) return false;

        // 不变量：朝向必须一致（否则需要 break-and-replace）
        if (!BlockUtilHelper.propertiesMatch(task, Properties.HORIZONTAL_FACING)) return false;

        // 目标属性 LIT 必须不一致
        return !BlockUtilHelper.propertiesMatch(task, Properties.LIT);
    }

    @Override
    public boolean isSatisfied(PrinterTask task) {
        if (!isCampfire(task.currentState().getBlock())) return false;
        return BlockUtilHelper.propertiesMatch(task, Properties.LIT);
    }

    @Override
    public ActionPlan plan(PrinterTask task, MinecraftClient mc, boolean strict, boolean checkLos, double maxReach) {
        boolean needsLight = task.desiredState().get(Properties.LIT);
        Item item = findItem(needsLight, mc);
        if (item == null) return null;

        // 自身交互几何规划（点击营火方块本身）
        ActionPlan.Interaction interaction = InteractionPlanner.planSelfInteraction(
            mc, task.pos(), strict, checkLos, maxReach
        );
        if (interaction == null) return null;

        return new ActionPlan.UseItemOnBlock(
            task.pos(),
            task.desiredState(),
            interaction,
            item,
            // 铲子 / 打火石走 Item.useOnBlock 路径，与潜行状态无关
            ActionPlan.SneakPolicy.KEEP_CURRENT,
            ActionPlan.HandPolicy.ANY_HAND_WITH_ITEM,
            // stillNeedsAction：当前 LIT 值仍不等于目标时返回 true
            state -> state.contains(Properties.LIT) && state.get(Properties.LIT) != needsLight
        );
    }

    // ==================== 辅助方法 ====================

    private static boolean isCampfire(Block block) {
        return block == Blocks.CAMPFIRE || block == Blocks.SOUL_CAMPFIRE;
    }

    /**
     * 查找可用的交互物品。
     * <ul>
     *   <li>点火：打火石（可复用） &gt; 火焰弹（消耗品）</li>
     *   <li>熄灭：背包中任意铲子</li>
     * </ul>
     *
     * @param needsLight true = 需要点燃，false = 需要熄灭
     * @return 可用物品，无可用物品时返回 null
     */
    private static Item findItem(boolean needsLight, MinecraftClient mc) {
        if (needsLight) {
            if (InvUtils.find(Items.FLINT_AND_STEEL).found()) return Items.FLINT_AND_STEEL;
            if (InvUtils.find(Items.FIRE_CHARGE).found()) return Items.FIRE_CHARGE;
            return null;
        } else {
            var result = InvUtils.find(stack -> stack.getItem() instanceof ShovelItem);
            if (!result.found()) return null;
            // 返回实际找到的铲子 Item 实例（可能是任意材质）
            return mc.player.getInventory().getStack(result.slot()).getItem();
        }
    }
}
