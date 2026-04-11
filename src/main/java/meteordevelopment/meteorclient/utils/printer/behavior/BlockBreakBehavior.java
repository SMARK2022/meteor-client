package meteordevelopment.meteorclient.utils.printer.behavior;

import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.world.PacketMine;
import meteordevelopment.meteorclient.utils.printer.ActionPlan;
import meteordevelopment.meteorclient.utils.printer.PrinterBehavior;
import meteordevelopment.meteorclient.utils.printer.PrinterTask;
import net.minecraft.block.BlockState;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;

/**
 * BlockBreakBehavior - 破坏不匹配方块的兜底行为
 *
 * <p>当世界中有实体方块与蓝图不匹配时，判断差异是否来自
 * <b>不可变属性</b>（放置时决定、无法通过右键交互修正），
 * 只有不可变差异才走破坏路径，委托 PacketMine 执行多 tick 挖掘。
 *
 * <h3>判定逻辑（替代旧版 REGISTRY 遍历）</h3>
 * <ol>
 *   <li>方块类型不同 → 必须破坏重放</li>
 *   <li>方块类型相同，{@link #IMMUTABLE_PROPERTIES} 中任一属性不一致 → 破坏</li>
 *   <li>方块类型相同且不可变属性全部一致 → <b>不破坏</b>。
 *       差异必然来自可变/运行时属性（open / powered / delay / note 等），
 *       应交给对应的交互行为修正（即使该行为尚未实现）。</li>
 * </ol>
 *
 * <p>破坏完成后，目标位置变为空气，下一 tick 的扫描会触发
 * BlockPlacementBehavior 放置蓝图所需方块，形成 破坏→放置 链。
 *
 * <p>注意：plan() 内部直接调用 PacketMine.addBreakTarget() 完成委托，
 * 返回 null 表示 Printer 无需执行额外动作——所有破坏包逻辑由 PacketMine 管理。
 */
public class BlockBreakBehavior implements PrinterBehavior {

    @Override public Group group() { return Group.BREAK; }
    @Override public Key key() { return Key.BREAK_MISMATCHED; }

    /**
     * 放置时由位置/朝向/方块变体决定的不可变属性集合。
     * 这些属性只能通过 break-and-replace 修正，不存在右键交互途径。
     *
     * <ul>
     *   <li>HORIZONTAL_FACING — 熔炉/箱子/楼梯/活板门等的水平朝向</li>
     *   <li>FACING — 活塞/侦测器/发射器/投掷器的六方向朝向</li>
     *   <li>AXIS — 原木/柱子/锁链的轴向</li>
     *   <li>BLOCK_HALF — 楼梯/活板门的上/下半</li>
     *   <li>BLOCK_FACE — 拉杆/按钮的附着面（地板/墙面/天花板）</li>
     *   <li>DOOR_HINGE — 门的左/右铰链</li>
     *   <li>ORIENTATION — 合成器/试炼刷怪笼的复合朝向</li>
     *   <li>SlabBlock.TYPE — 台阶的类型（上半/下半/双层）</li>
     * </ul>
     */
    private static final Property<?>[] IMMUTABLE_PROPERTIES = {
        Properties.HORIZONTAL_FACING,
        Properties.FACING,
        Properties.AXIS,
        Properties.BLOCK_HALF,
        Properties.BLOCK_FACE,
        Properties.DOOR_HINGE,
        Properties.ORIENTATION,
        SlabBlock.TYPE,
    };

    /**
     * 支持条件：
     * <ol>
     *   <li>当前方块非空气、非可替换（有实体需要破坏）</li>
     *   <li>方块类型不同，或同类型但存在不可变属性差异</li>
     * </ol>
     *
     * <p>同类型方块中仅可变/运行时属性（open / powered / lit / note 等）不一致时
     * 返回 false，交给对应的交互行为处理（即使该行为尚未实现）。
     */
    @Override
    public boolean supports(PrinterTask task) {
        BlockState current = task.currentState();
        BlockState desired = task.desiredState();
        if (current.isAir() || current.isReplaceable()) return false;

        // 方块类型不同 → 必须破坏重放
        if (current.getBlock() != desired.getBlock()) return true;

        // 双层半砖补全：蓝图要求 DOUBLE、世界已有 TOP/BOTTOM → 放置完成即可，不破坏
        if (current.getBlock() instanceof SlabBlock
            && desired.contains(SlabBlock.TYPE) && current.contains(SlabBlock.TYPE)
            && desired.get(SlabBlock.TYPE) == SlabType.DOUBLE
            && current.get(SlabBlock.TYPE) != SlabType.DOUBLE) {
            return false;
        }

        // 同类型方块：仅在不可变属性不一致时破坏
        return hasImmutablePropertyDifference(desired, current);
    }

    /**
     * 检查两个方块状态在 {@link #IMMUTABLE_PROPERTIES} 中是否存在差异。
     * 只比较双方都拥有的属性（缺失的属性不参与比较）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean hasImmutablePropertyDifference(BlockState desired, BlockState current) {
        for (Property prop : IMMUTABLE_PROPERTIES) {
            if (desired.contains(prop) && current.contains(prop)
                && !desired.get(prop).equals(current.get(prop))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 满足条件：当前位置已变为空气/可替换（破坏完成），
     * 或 PacketMine 正在挖掘此位置（进行中，无需重复委托）。
     */
    @Override
    public boolean isSatisfied(PrinterTask task) {
        BlockState current = task.currentState();
        if (current.isAir() || current.isReplaceable()) return true;
        // PacketMine 正在处理，视为"满足"防止重复委托
        PacketMine pm = Modules.get().get(PacketMine.class);
        return pm != null && pm.isActive() && pm.isMiningBlock(task.pos());
    }

    /**
     * 直接委托 PacketMine 执行破坏，返回 null（Printer 无需执行动作）。
     * 所有破坏包发送、旋转、工具选择逻辑均由 PacketMine 内部管理。
     */
    @Override
    public ActionPlan plan(PrinterTask task, MinecraftClient mc, boolean strict, boolean checkLos, double maxReach) {
        PacketMine pm = Modules.get().get(PacketMine.class);
        if (pm == null || !pm.isActive()) return null;
        if (pm.isMiningBlock(task.pos())) return null;
        if (pm.blocks.size() >= 3) return null;
        // 委托 PacketMine：addBreakTarget 内部处理面朝向、工具切换、包发送
        pm.addBreakTarget(task.pos());
        return null;
    }
}
