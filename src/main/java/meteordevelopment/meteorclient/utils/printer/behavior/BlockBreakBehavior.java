package meteordevelopment.meteorclient.utils.printer.behavior;

import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.world.PacketMine;
import meteordevelopment.meteorclient.utils.printer.ActionPlan;
import meteordevelopment.meteorclient.utils.printer.PrinterBehavior;
import meteordevelopment.meteorclient.utils.printer.PrinterTask;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;

/**
 * BlockBreakBehavior - 破坏不匹配方块的兜底行为
 *
 * 当世界中有实体方块与蓝图不匹配、且没有其他行为能通过交互修正时，
 * 委托 PacketMine 执行多 tick 挖掘。
 *
 * 判定逻辑：遍历 REGISTRY 中的其他行为，如果有任何一个能处理此任务
 * （无论是否被用户启用），则认为差异可通过交互修复，不走破坏路径。
 * 只有当所有其他行为都不支持时，才说明差异来自不可变属性
 * （方块类型不同、facing 朝向不一致等），必须破坏重放。
 *
 * 破坏完成后，目标位置变为空气，下一 tick 的扫描会触发
 * BlockPlacementBehavior 放置蓝图所需方块，形成 破坏→放置 链。
 *
 * 注意：plan() 内部直接调用 PacketMine.addBreakTarget() 完成委托，
 * 返回 null 表示 Printer 无需执行额外动作——所有破坏包逻辑由 PacketMine 管理。
 */
public class BlockBreakBehavior implements PrinterBehavior {

    @Override public Group group() { return Group.BREAK; }
    @Override public Key key() { return Key.BREAK_MISMATCHED; }

    /**
     * 支持条件：
     * 1. 当前方块非空气、非可替换（有实体需要破坏）
     * 2. REGISTRY 中没有其他行为能处理此任务（差异来自不可变属性）
     *
     * 通过遍历 REGISTRY 实现"不可变属性"的判定：
     * - repeater delay 差异 → RepeaterDelayBehavior 支持 → 此处 return false
     * - furnace facing 差异 → 无行为支持 → return true，需要破坏
     * - slab SINGLE→DOUBLE → BlockPlacementBehavior 支持 → return false
     */
    @Override
    public boolean supports(PrinterTask task) {
        BlockState current = task.currentState();
        if (current.isAir() || current.isReplaceable()) return false;
        // 遍历其他行为：如果有任何行为能修复此差异，则不走破坏路径
        for (PrinterBehavior b : REGISTRY) {
            if (b == this) continue;
            if (b.supports(task)) return false;
        }
        return true;
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
