package meteordevelopment.meteorclient.utils.printer;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

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
 * [临时调试] ContainerFillLogger — 容器填充子系统的文件日志工具。
 *
 * <p>所有方法均为静态调用，通过 {@link #enabled} 全局开关控制。
 * 日志写入 {@code run/logs/printer-container-fill.log}。
 *
 * <p><b>注意：此类及全部调用点均为临时调试代码，后续应删除。</b>
 */
public final class ContainerFillLogger {

    // ==================== [临时调试] 全局开关 ====================

    /** 由 Printer 的 debugContainerFillLog 设置控制。 */
    public static boolean enabled = false;

    // ==================== 内部 ====================

    private static final Path LOG_PATH = Path.of("run", "logs", "printer-container-fill.log");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static BufferedWriter writer;

    private ContainerFillLogger() {}

    // ==================== 生命周期 ====================

    /** [临时调试] 开启日志（Printer 激活时调用）。 */
    public static void open() {
        if (!enabled) return;
        try {
            Files.createDirectories(LOG_PATH.getParent());
            writer = Files.newBufferedWriter(LOG_PATH,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            write("===== Container Fill Debug Log — Session Start =====");
        } catch (IOException e) {
            System.err.println("[ContainerFillLogger] Failed to open log file: " + e.getMessage());
        }
    }

    /** [临时调试] 关闭日志（Printer 停用时调用）。 */
    public static void close() {
        if (writer != null) {
            write("===== Session End =====");
            try { writer.close(); } catch (IOException ignored) {}
            writer = null;
        }
    }

    // ==================== 扫描阶段 ====================

    /** [临时调试] 记录 scanSchematicContainer 被调用。 */
    public static void logScanAttempt(BlockPos pos, String blockEntityType, boolean isSupported) {
        if (!enabled) return;
        write("[SCAN] pos=%s  beType=%s  supported=%s", fmtPos(pos), blockEntityType, isSupported);
    }

    /** [临时调试] 记录成功注册到缓存的容器。 */
    public static void logScanRegistered(BlockPos pos, List<ItemStack> items, boolean hasContent) {
        if (!enabled) return;
        StringBuilder sb = new StringBuilder();
        sb.append("[SCAN-REG] pos=").append(fmtPos(pos))
          .append("  hasContent=").append(hasContent)
          .append("  items=[");
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty()) {
                sb.append(stack.getItem()).append("x").append(stack.getCount());
                if (i < items.size() - 1) sb.append(", ");
            }
        }
        sb.append("]");
        write(sb.toString());
    }

    /** [临时调试] 记录 beginScan / pruneStaleEntries。 */
    public static void logScanCycle(String phase, int cacheSize, int satisfiedCount) {
        if (!enabled) return;
        write("[SCAN-%s] cacheSize=%d  satisfied=%d", phase, cacheSize, satisfiedCount);
    }

    // ==================== 状态机 ====================

    /** [临时调试] 记录状态切换。 */
    public static void logStateChange(ContainerFillManager.State from, ContainerFillManager.State to, String reason) {
        if (!enabled) return;
        write("[STATE] %s -> %s  reason=%s", from, to, reason);
    }

    /** [临时调试] 记录 pickTarget 候选搜索。 */
    public static void logPickTarget(int totalCandidates, int satisfiedSkipped,
                                     int outOfRange, int notContainer, int noItems,
                                     int movingBlocked, BlockPos selected) {
        if (!enabled) return;
        write("[PICK] candidates=%d  satisfiedSkip=%d  outOfRange=%d  notContainer=%d  noItems=%d  movingBlock=%d  selected=%s",
            totalCandidates, satisfiedSkipped, outOfRange, notContainer, noItems, movingBlocked,
            selected != null ? fmtPos(selected) : "NONE");
    }

    /** [临时调试] 记录 PREPARING 阶段等待原因。 */
    public static void logPreparingWait(String reason) {
        if (!enabled) return;
        write("[PREPARING] waiting: %s", reason);
    }

    /** [临时调试] 记录 InteractionPlanner 结果。 */
    public static void logInteractionPlan(boolean success, BlockPos target) {
        if (!enabled) return;
        write("[PLAN] target=%s  success=%s", fmtPos(target), success);
    }

    // ==================== 执行阶段 ====================

    /** [临时调试] 记录 executeOpen 调用。 */
    public static void logExecuteOpen(boolean fired, BlockPos target, double distSq, double maxReachSq) {
        if (!enabled) return;
        write("[EXEC-OPEN] fired=%s  target=%s  distSq=%.2f  maxReachSq=%.2f", fired, fmtPos(target), distSq, maxReachSq);
    }

    /** [临时调试] 记录 onInventorySync 收到包。 */
    public static void logInventorySync(int syncId, int totalSlots, int containerSlots) {
        if (!enabled) return;
        write("[INV-SYNC] syncId=%d  totalSlots=%d  containerSlots=%d", syncId, totalSlots, containerSlots);
    }

    /** [临时调试] 记录 fillAndClose 每一步。 */
    public static void logFillStep(Item item, int required, int existing, int slotClicked) {
        if (!enabled) return;
        write("[FILL] item=%s  required=%d  existing=%d  slotClicked=%d", item, required, existing, slotClicked);
    }

    /** [临时调试] 记录 fillAndClose 最终结果。 */
    public static void logFillResult(BlockPos pos, Map<Item, Integer> needs, boolean allSatisfied) {
        if (!enabled) return;
        StringBuilder sb = new StringBuilder();
        sb.append("[FILL-RESULT] pos=").append(fmtPos(pos)).append("  satisfied=").append(allSatisfied).append("  needs={");
        if (needs != null) {
            needs.forEach((item, count) -> sb.append(item).append("=").append(count).append(", "));
        }
        sb.append("}");
        write(sb.toString());
    }

    /** [临时调试] 记录屏幕抑制。 */
    public static void logScreenSuppress(boolean suppressed) {
        if (!enabled) return;
        write("[SCREEN] suppress=%s", suppressed);
    }

    /** [临时调试] 记录 sprint/sneak 压制动作。 */
    public static void logInputSuppression(String action) {
        if (!enabled) return;
        write("[INPUT] %s", action);
    }

    // ==================== 内部写入 ====================

    private static void write(String fmt, Object... args) {
        if (writer == null) return;
        try {
            int tick = mc.player != null ? mc.player.age : -1;
            String line = String.format("[%s][tick=%d] %s", TIME_FMT.format(LocalDateTime.now()), tick, String.format(fmt, args));
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            // 静默失败，避免日志错误影响游戏
        }
    }

    private static String fmtPos(BlockPos pos) {
        return pos != null ? String.format("(%d,%d,%d)", pos.getX(), pos.getY(), pos.getZ()) : "null";
    }
}
