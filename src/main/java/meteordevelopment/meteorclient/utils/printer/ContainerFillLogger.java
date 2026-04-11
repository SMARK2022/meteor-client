package meteordevelopment.meteorclient.utils.printer;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * [临时调试] ContainerFillLogger — 容器填充子系统的详细文件日志工具。
 *
 * <p>JSON-like 结构化输出，覆盖从扫描到填充关闭的全生命周期。
 * 日志写入 {@code run/logs/printer-container-fill.log}。
 *
 * <p><b>注意：此类及全部调用点（搜索 [临时调试] 标记）均为临时调试代码，后续应删除。</b>
 */
public final class ContainerFillLogger {

    // ==================== [临时调试] 全局开关 ====================

    /** 由 Printer 的 debugContainerFillLog 设置控制。 */
    public static boolean enabled = false;

    // ==================== 内部常量 ====================

    private static final Path LOG_PATH = Path.of("run", "logs", "printer-container-fill.log");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final String SEP = "────────────────────────────────────────";
    private static BufferedWriter writer;

    private ContainerFillLogger() {}

    // ═══════════════════════════════════════════════
    //  生命周期
    // ═══════════════════════════════════════════════

    /** [临时调试] 开启日志（Printer 激活时调用）。 */
    public static void open() {
        if (!enabled) return;
        try {
            Files.createDirectories(LOG_PATH.getParent());
            writer = Files.newBufferedWriter(LOG_PATH,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            raw("╔══════════════════════════════════════════════════════════════╗");
            raw("║  Container Fill Debug Log — Session Start                   ║");
            raw("║  Time: %-53s ║", TIME_FMT.format(LocalDateTime.now()));
            raw("╚══════════════════════════════════════════════════════════════╝");
            raw("");
            if (mc.player != null) {
                tagged("INIT", "playerSnapshot {");
                kv("  position", fmtVec(mc.player.getPos()));
                kv("  eyePos", fmtVec(mc.player.getEyePos()));
                kv("  gameMode", mc.interactionManager != null ? mc.interactionManager.getCurrentGameMode().toString() : "null");
                raw("    }");
            }
            raw("");
        } catch (IOException e) {
            System.err.println("[ContainerFillLogger] Failed to open log file: " + e.getMessage());
        }
    }

    /** [临时调试] 关闭日志（Printer 停用时调用）。 */
    public static void close() {
        if (writer != null) {
            raw("");
            raw("╔══════════════════════════════════════════════════════════════╗");
            raw("║  Session End — %-45s ║", TIME_FMT.format(LocalDateTime.now()));
            raw("╚══════════════════════════════════════════════════════════════╝");
            try { writer.close(); } catch (IOException ignored) {}
            writer = null;
        }
    }

    // ═══════════════════════════════════════════════
    //  GROUP A: 扫描阶段
    // ═══════════════════════════════════════════════

    /** [临时调试] scanSchematicContainer 被调用 — 详细记录 BE 类型和世界状态。 */
    public static void logScanAttempt(BlockPos pos, String beTypeOrInfo, boolean isSupported) {
        if (!enabled) return;
        tagged("SCAN", "attempt {");
        kv("  pos", fmtPos(pos));
        kv("  blockEntity", beTypeOrInfo);
        kv("  supported", String.valueOf(isSupported));
        // 追加世界方块状态和 BlockEntity 信息
        if (mc.world != null) {
            BlockState worldState = mc.world.getBlockState(pos);
            kv("  worldBlockState", dumpBlockState(worldState));
            BlockEntity worldBe = mc.world.getBlockEntity(pos);
            if (worldBe != null) {
                kv("  worldBE.class", worldBe.getClass().getSimpleName());
                if (worldBe instanceof Inventory inv) {
                    kv("  worldBE.invSize", String.valueOf(inv.size()));
                    kv("  worldBE.contents", dumpInventory(inv));
                }
            } else {
                kv("  worldBE", "null");
            }
        }
        raw("    }");
    }

    /** [临时调试] 注册到缓存的容器 — 打印完整槽位列表。 */
    public static void logScanRegistered(BlockPos pos, List<ItemStack> items, boolean hasContent) {
        if (!enabled) return;
        tagged("SCAN", "register {");
        kv("  pos", fmtPos(pos));
        kv("  hasContent", String.valueOf(hasContent));
        kv("  slotCount", String.valueOf(items.size()));
        raw("    slots: [");
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            raw("      [%d] %s", i, stack.isEmpty() ? "<empty>" : stack.getItem() + " x" + stack.getCount());
        }
        raw("    ]");
        raw("    }");
    }

    /** [临时调试] 扫描循环开始/结束。 */
    public static void logScanCycle(String phase, int cacheSize, int satisfiedCount) {
        if (!enabled) return;
        tagged("SCAN", "%s { cacheSize: %d, satisfied: %d, unsatisfied: %d }",
            phase, cacheSize, satisfiedCount, cacheSize - satisfiedCount);
    }

    // ═══════════════════════════════════════════════
    //  GROUP B: 状态机转换
    // ═══════════════════════════════════════════════

    /** [临时调试] 状态切换 — 记录前后状态、原因和玩家快照。 */
    public static void logStateChange(ContainerFillManager.State from, ContainerFillManager.State to, String reason) {
        if (!enabled) return;
        raw(SEP);
        tagged("STATE", "%s ──→ %s", from, to);
        kv("  reason", reason);
        if (mc.player != null) {
            kv("  playerPos", fmtVec(mc.player.getPos()));
            kv("  sprinting", String.valueOf(mc.player.isSprinting()));
            kv("  sneaking", String.valueOf(mc.player.isSneaking()));
            kv("  onGround", String.valueOf(mc.player.isOnGround()));
        }
        raw(SEP);
    }

    /** [临时调试] pickTarget 候选搜索 — 详细拒绝原因统计。 */
    public static void logPickTarget(int totalCandidates, int satisfiedSkipped,
                                     int outOfRange, int notContainer, int noItems,
                                     int movingBlocked, BlockPos selected) {
        if (!enabled) return;
        tagged("PICK", "search {");
        kv("  totalInCache", String.valueOf(totalCandidates));
        kv("  satisfiedSkipped", String.valueOf(satisfiedSkipped));
        kv("  outOfRange", String.valueOf(outOfRange));
        kv("  notContainerInWorld", String.valueOf(notContainer));
        kv("  noMatchingItems", String.valueOf(noItems));
        kv("  movingBlocked", String.valueOf(movingBlocked));
        kv("  selected", selected != null ? fmtPos(selected) : "NONE");
        if (mc.player != null) {
            kv("  playerEye", fmtVec(mc.player.getEyePos()));
            if (mc.options != null) {
                kv("  fwdKey", String.valueOf(mc.options.forwardKey.isPressed()));
                kv("  backKey", String.valueOf(mc.options.backKey.isPressed()));
            }
        }
        raw("    }");
    }

    /** [临时调试] PREPARING 阶段等待原因 — 包含当前输入态快照。 */
    public static void logPreparingWait(String reason) {
        if (!enabled) return;
        tagged("PREPARING", "wait { reason: \"%s\"", reason);
        if (mc.player != null) {
            kv("  sneaking", String.valueOf(mc.player.isSneaking()));
            kv("  sprinting", String.valueOf(mc.player.isSprinting()));
            if (mc.options != null) {
                kv("  fwd", String.valueOf(mc.options.forwardKey.isPressed()));
                kv("  back", String.valueOf(mc.options.backKey.isPressed()));
                kv("  left", String.valueOf(mc.options.leftKey.isPressed()));
                kv("  right", String.valueOf(mc.options.rightKey.isPressed()));
                kv("  sneakKey", String.valueOf(mc.options.sneakKey.isPressed()));
                kv("  sprintKey", String.valueOf(mc.options.sprintKey.isPressed()));
            }
        }
        raw("    }");
    }

    /** [临时调试] InteractionPlanner 结果 — 打印交互几何。 */
    public static void logInteractionPlan(boolean success, BlockPos target) {
        if (!enabled) return;
        tagged("PLAN", "result {");
        kv("  target", fmtPos(target));
        kv("  success", String.valueOf(success));
        if (mc.player != null) {
            kv("  eyePos", fmtVec(mc.player.getEyePos()));
            kv("  distToTarget", String.format("%.3f", mc.player.getEyePos().distanceTo(Vec3d.ofCenter(target))));
        }
        raw("    }");
    }

    // ═══════════════════════════════════════════════
    //  GROUP C: 执行阶段
    // ═══════════════════════════════════════════════

    /** [临时调试] executeOpen — 记录 interactBlock 参数和玩家快照。 */
    public static void logExecuteOpen(boolean fired, BlockPos target, double distSq, double maxReachSq) {
        if (!enabled) return;
        tagged("EXEC", "open {");
        kv("  fired", String.valueOf(fired));
        kv("  target", fmtPos(target));
        kv("  distSq", String.format("%.4f", distSq));
        kv("  maxReachSq", String.format("%.4f", maxReachSq));
        kv("  dist", String.format("%.3f", Math.sqrt(distSq)));
        kv("  maxReach", String.format("%.3f", Math.sqrt(maxReachSq)));
        if (mc.player != null) {
            kv("  yaw", String.format("%.2f", mc.player.getYaw()));
            kv("  pitch", String.format("%.2f", mc.player.getPitch()));
            kv("  sprinting", String.valueOf(mc.player.isSprinting()));
            kv("  sneaking", String.valueOf(mc.player.isSneaking()));
            kv("  handler.syncId", String.valueOf(
                mc.player.currentScreenHandler != null ? mc.player.currentScreenHandler.syncId : -1));
        }
        raw("    }");
    }

    /** [临时调试] InventorySync — 服务端回包详情 + handler 槽位 dump。 */
    public static void logInventorySync(int syncId, int totalSlots, int containerSlots) {
        if (!enabled) return;
        tagged("INV", "sync {");
        kv("  syncId", String.valueOf(syncId));
        kv("  totalSlots", String.valueOf(totalSlots));
        kv("  containerSlots", String.valueOf(containerSlots));
        kv("  playerInvSlots", String.valueOf(totalSlots - containerSlots));
        if (mc.player != null && mc.player.currentScreenHandler != null) {
            ScreenHandler h = mc.player.currentScreenHandler;
            kv("  handler.syncId", String.valueOf(h.syncId));
            kv("  handler.slotCount", String.valueOf(h.slots.size()));
            raw("    containerSlots: [");
            for (int i = 0; i < containerSlots && i < h.slots.size(); i++) {
                ItemStack stack = h.getSlot(i).getStack();
                raw("      [%d] %s", i, stack.isEmpty() ? "<empty>" : stack.getItem() + " x" + stack.getCount());
            }
            raw("    ]");
            raw("    playerNonEmpty: [");
            for (int i = containerSlots; i < h.slots.size(); i++) {
                ItemStack stack = h.getSlot(i).getStack();
                if (!stack.isEmpty()) raw("      [%d] %s x%d", i, stack.getItem(), stack.getCount());
            }
            raw("    ]");
        }
        raw("    }");
    }

    /** [临时调试] fillAndClose 每一步 shift-click。 */
    public static void logFillStep(Item item, int required, int existing, int slotClicked) {
        if (!enabled) return;
        tagged("FILL", "shiftClick { item: %s, required: %d, existingBefore: %d, slot: %d }", item, required, existing, slotClicked);
    }

    /** [临时调试] fillAndClose 最终结果 — 需求对比 + handler 最终状态。 */
    public static void logFillResult(BlockPos pos, Map<Item, Integer> needs, boolean allSatisfied) {
        if (!enabled) return;
        tagged("FILL", "result {");
        kv("  pos", fmtPos(pos));
        kv("  allSatisfied", String.valueOf(allSatisfied));
        if (needs != null) {
            raw("    needs: {");
            for (var entry : needs.entrySet()) {
                raw("      %s: %d", entry.getKey(), entry.getValue());
            }
            raw("    }");
        }
        // handler 最终非空槽位
        if (mc.player != null && mc.player.currentScreenHandler != null) {
            ScreenHandler h = mc.player.currentScreenHandler;
            if (h.syncId != 0) {
                raw("    handlerFinal: [");
                for (int i = 0; i < h.slots.size(); i++) {
                    ItemStack stack = h.getSlot(i).getStack();
                    if (!stack.isEmpty()) raw("      [%d] %s x%d", i, stack.getItem(), stack.getCount());
                }
                raw("    ]");
            }
        }
        raw("    }");
    }

    /** [临时调试] 屏幕抑制。 */
    public static void logScreenSuppress(boolean suppressed) {
        if (!enabled) return;
        tagged("SCREEN", "suppress=%s  handler.syncId=%d", suppressed,
            mc.player != null && mc.player.currentScreenHandler != null ? mc.player.currentScreenHandler.syncId : -1);
    }

    /** [临时调试] 输入压制（sprint/sneak）。 */
    public static void logInputSuppression(String action) {
        if (!enabled) return;
        tagged("INPUT", "%s", action);
    }

    // ═══════════════════════════════════════════════
    //  内部：格式化工具
    // ═══════════════════════════════════════════════

    /** 带时间戳和 tick 的标签行。 */
    private static void tagged(String tag, String fmt, Object... args) {
        if (writer == null) return;
        int tick = mc.player != null ? mc.player.age : -1;
        writeLine(String.format("[%s][tick=%d][%s] %s",
            TIME_FMT.format(LocalDateTime.now()), tick, tag, String.format(fmt, args)));
    }

    /** 键值对行（缩进对齐）。 */
    private static void kv(String key, String value) {
        if (writer == null) return;
        writeLine(String.format("    %-28s : %s", key, value));
    }

    /** 原始行。 */
    private static void raw(String fmt, Object... args) {
        if (writer == null) return;
        writeLine(args.length > 0 ? String.format(fmt, args) : fmt);
    }

    /** BlockState 完整 dump：方块 ID + 所有属性。 */
    private static String dumpBlockState(BlockState state) {
        if (state == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append(state.getBlock().toString());
        if (!state.getProperties().isEmpty()) {
            sb.append(" {");
            boolean first = true;
            for (Property<?> prop : state.getProperties()) {
                if (!first) sb.append(", ");
                sb.append(prop.getName()).append("=").append(propValue(state, prop));
                first = false;
            }
            sb.append("}");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String propValue(BlockState state, Property<T> prop) {
        return prop.name(state.get(prop));
    }

    /** Inventory 内容概要。 */
    private static String dumpInventory(Inventory inv) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) {
                if (!first) sb.append(", ");
                sb.append(i).append(":").append(stack.getItem()).append("x").append(stack.getCount());
                first = false;
            }
        }
        sb.append("]");
        return first ? "[<all empty>]" : sb.toString();
    }

    private static String fmtPos(BlockPos pos) {
        return pos != null ? String.format("(%d, %d, %d)", pos.getX(), pos.getY(), pos.getZ()) : "null";
    }

    private static String fmtVec(Vec3d vec) {
        return vec != null ? String.format("(%.2f, %.2f, %.2f)", vec.x, vec.y, vec.z) : "null";
    }

    private static void writeLine(String line) {
        try {
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            // 静默失败
        }
    }
}
