/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.network.PacketUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.util.hit.BlockHitResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PacketLogger — 可选粒度的包日志模块。
 *
 * <h3>Chat 输出三模式</h3>
 * <ul>
 *   <li><b>Never</b>  — 不输出到 chat，零渲染开销</li>
 *   <li><b>Statistics</b> — 每秒一条摘要（+N C2S | +M S2C），最小 chat 开销</li>
 *   <li><b>Details</b> — 逐包输出完整详情（高频时可能影响帧率）</li>
 * </ul>
 *
 * <h3>文件记录</h3>
 * 独立开关，通过 {@link ConcurrentLinkedQueue} + daemon 线程异步写盘，
 * 主线程仅做 offer()（~20ns），不阻塞渲染。
 *
 * <h3>性能概要</h3>
 * Statistics/Never 模式下主线程每包开销：2× AtomicLong.increment + 1× queue.offer（如启用文件）。
 */
public class PacketLogger extends Module {

    // ════════════════════════════════════════════════════════════
    //  Chat 输出模式枚举
    // ════════════════════════════════════════════════════════════

    public enum ChatMode {
        /** 不输出到 chat，零开销 */
        Never,
        /** 每秒一条统计摘要：+N C2S | +M S2C (total: X | Y) */
        Statistics,
        /** 逐包输出完整详情（兼容旧版行为） */
        Details
    }

    // ════════════════════════════════════════════════════════════
    //  Setting Groups
    // ════════════════════════════════════════════════════════════

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDigging = settings.createGroup("Digging");
    private final SettingGroup sgMovement = settings.createGroup("Movement");
    private final SettingGroup sgInteraction = settings.createGroup("Interaction");
    private final SettingGroup sgSlots = settings.createGroup("Slots & Swing");
    private final SettingGroup sgS2C = settings.createGroup("S2C (Receive)");
    private final SettingGroup sgContainerFill = settings.createGroup("Container Fill");
    private final SettingGroup sgAdvancedC2S = settings.createGroup("Advanced C2S");

    // ======================== General ========================

    /** Chat 输出粒度控制 */
    private final Setting<ChatMode> chatMode = sgGeneral.add(new EnumSetting.Builder<ChatMode>()
        .name("chat-mode")
        .description("Chat output: Never (silent), Statistics (1/sec summary), Details (per-packet).")
        .defaultValue(ChatMode.Statistics)
        .build()
    );

    /** 仅 Details 模式下可见——为每行添加序号/tick/耗时前缀 */
    private final Setting<Boolean> showTimestamp = sgGeneral.add(new BoolSetting.Builder()
        .name("show-timestamp")
        .description("Prefix each detail line with sequence, tick, and elapsed ms.")
        .defaultValue(true)
        .visible(() -> chatMode.get() == ChatMode.Details)
        .build()
    );

    /** 文件记录开关——启用后通过异步线程写盘，不阻塞主线程 */
    private final Setting<Boolean> logToFile = sgGeneral.add(new BoolSetting.Builder()
        .name("log-to-file")
        .description("Write complete detailed log to logs/packet-logger/ via async I/O thread.")
        .defaultValue(false)
        .build()
    );

    /** 启动时清空 chat 并插入分隔符——方便区分多次录制 */
    private final Setting<Boolean> clearOnStart = sgGeneral.add(new BoolSetting.Builder()
        .name("clear-on-start")
        .description("Clear chat and insert separator line when starting the logger.")
        .defaultValue(false)
        .build()
    );

    // ======================== Digging (PlayerActionC2SPacket sub-actions) ========================

    private final Setting<Boolean> logStartDig = sgDigging.add(new BoolSetting.Builder()
        .name("start-destroy")
        .description("Log START_DESTROY_BLOCK actions.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logStopDig = sgDigging.add(new BoolSetting.Builder()
        .name("stop-destroy")
        .description("Log STOP_DESTROY_BLOCK actions.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logAbortDig = sgDigging.add(new BoolSetting.Builder()
        .name("abort-destroy")
        .description("Log ABORT_DESTROY_BLOCK actions.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logDropItem = sgDigging.add(new BoolSetting.Builder()
        .name("drop-item")
        .description("Log DROP_ITEM and DROP_ALL_ITEMS actions.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logReleaseUse = sgDigging.add(new BoolSetting.Builder()
        .name("release-use-item")
        .description("Log RELEASE_USE_ITEM action.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logSwapOffhand = sgDigging.add(new BoolSetting.Builder()
        .name("swap-offhand")
        .description("Log SWAP_ITEM_WITH_OFFHAND action.")
        .defaultValue(false)
        .build()
    );

    // ======================== Movement ========================

    private final Setting<Boolean> logMovement = sgMovement.add(new BoolSetting.Builder()
        .name("player-move")
        .description("Log all PlayerMoveC2SPacket variants (Full, Position, Look, OnGround).")
        .defaultValue(false)
        .build()
    );

    // ======================== Interaction ========================

    private final Setting<Boolean> logInteractBlock = sgInteraction.add(new BoolSetting.Builder()
        .name("interact-block")
        .description("Log PlayerInteractBlockC2SPacket.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logInteractEntity = sgInteraction.add(new BoolSetting.Builder()
        .name("interact-entity")
        .description("Log PlayerInteractEntityC2SPacket.")
        .defaultValue(false)
        .build()
    );

    // ======================== Slots & Swing ========================

    private final Setting<Boolean> logSlotChange = sgSlots.add(new BoolSetting.Builder()
        .name("slot-change")
        .description("Log UpdateSelectedSlotC2SPacket.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logHandSwing = sgSlots.add(new BoolSetting.Builder()
        .name("hand-swing")
        .description("Log HandSwingC2SPacket.")
        .defaultValue(false)
        .build()
    );

    // ======================== Container Fill ========================

    private final Setting<Boolean> logClientCommand = sgContainerFill.add(new BoolSetting.Builder()
        .name("client-command")
        .description("Log ClientCommandC2SPacket (sprint/sneak toggle, fall flying).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logClickSlot = sgContainerFill.add(new BoolSetting.Builder()
        .name("click-slot")
        .description("Log ClickSlotC2SPacket (container slot operations).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logCloseHandledScreen = sgContainerFill.add(new BoolSetting.Builder()
        .name("close-handled-screen")
        .description("Log CloseHandledScreenC2SPacket (client closes container).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logInteractItem = sgContainerFill.add(new BoolSetting.Builder()
        .name("interact-item")
        .description("Log PlayerInteractItemC2SPacket (use item in air).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logPlayerInput = sgContainerFill.add(new BoolSetting.Builder()
        .name("player-input")
        .description("Log PlayerInputC2SPacket (movement/sneak/sprint input state).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logOpenScreen = sgContainerFill.add(new BoolSetting.Builder()
        .name("open-screen")
        .description("Log OpenScreenS2CPacket (server opens container GUI).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logInventorySync = sgContainerFill.add(new BoolSetting.Builder()
        .name("inventory-sync")
        .description("Log InventoryS2CPacket (server sends container contents).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logSlotUpdate = sgContainerFill.add(new BoolSetting.Builder()
        .name("slot-update")
        .description("Log ScreenHandlerSlotUpdateS2CPacket.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logCloseScreen = sgContainerFill.add(new BoolSetting.Builder()
        .name("close-screen")
        .description("Log CloseScreenS2CPacket (server closes container).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logBlockEntityUpdate = sgContainerFill.add(new BoolSetting.Builder()
        .name("block-entity-update")
        .description("Log BlockEntityUpdateS2CPacket.")
        .defaultValue(false)
        .build()
    );

    // ======================== S2C ========================

    private final Setting<Boolean> logBlockUpdate = sgS2C.add(new BoolSetting.Builder()
        .name("block-update")
        .description("Log BlockUpdateS2CPacket.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logPlayerPosLook = sgS2C.add(new BoolSetting.Builder()
        .name("player-pos-look")
        .description("Log PlayerPositionLookS2CPacket (server corrections).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logHealthUpdate = sgS2C.add(new BoolSetting.Builder()
        .name("health-update")
        .description("Log HealthUpdateS2CPacket.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logPlaySound = sgS2C.add(new BoolSetting.Builder()
        .name("play-sound")
        .description("Log PlaySoundS2CPacket.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Set<Class<? extends Packet<?>>>> s2cPackets = sgS2C.add(new PacketListSetting.Builder()
        .name("extra-S2C-packets")
        .description("Additional S2C packets not covered by dedicated toggles above.")
        .filter(aClass -> PacketUtils.getS2CPackets().contains(aClass))
        .build()
    );

    private final Setting<Boolean> s2cLogAll = sgS2C.add(new BoolSetting.Builder()
        .name("log-all-S2C")
        .description("Log all S2C packets.")
        .defaultValue(false)
        .build()
    );

    // ======================== Advanced C2S ========================

    private final Setting<Set<Class<? extends Packet<?>>>> c2sPacketsExtra = sgAdvancedC2S.add(new PacketListSetting.Builder()
        .name("extra-C2S-packets")
        .description("Additional C2S packet types not covered by the dedicated toggles above.")
        .filter(aClass -> PacketUtils.getC2SPackets().contains(aClass))
        .build()
    );

    public PacketLogger() {
        super(Categories.Misc, "packet-logger", "Logs selected packets with configurable chat/file output.");
    }

    // ════════════════════════════════════════════════════════════
    //  Internal State
    // ════════════════════════════════════════════════════════════

    /** 全局序号，用于文件和 Details 模式的行号 */
    private final AtomicLong seqCounter = new AtomicLong();

    /** 统计计数器——主线程 increment，统计/停用时读取 */
    private final AtomicLong c2sTotal = new AtomicLong();
    private final AtomicLong s2cTotal = new AtomicLong();
    /** 距上次统计输出的增量（每秒重置） */
    private final AtomicLong c2sDelta = new AtomicLong();
    private final AtomicLong s2cDelta = new AtomicLong();

    /** 模块激活时的 System.currentTimeMillis()，用于计算 elapsed */
    private long activateMs;
    /** 统计 tick 计数器，0~19 循环 */
    private int statsTicks;

    // ── 异步文件写入 ──
    /** 无锁队列：主线程 offer()，IO 线程 poll() */
    private final ConcurrentLinkedQueue<String> fileQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean ioRunning;
    private Thread ioThread;
    private BufferedWriter fileWriter;

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    /** IO 线程批量写入周期（ms）——平衡延迟与吞吐 */
    private static final long IO_DRAIN_INTERVAL_MS = 200;

    // ════════════════════════════════════════════════════════════
    //  Lifecycle
    // ════════════════════════════════════════════════════════════

    @Override
    public void onActivate() {
        // 重置所有计数器
        seqCounter.set(0);
        c2sTotal.set(0);
        s2cTotal.set(0);
        c2sDelta.set(0);
        s2cDelta.set(0);
        activateMs = System.currentTimeMillis();
        statsTicks = 0;

        // 清屏 + 分隔符
        if (clearOnStart.get() && mc.inGameHud != null) {
            mc.inGameHud.getChatHud().clear(false);
            info("═══════════════ PacketLogger started ═══════════════");
        }

        // 启动异步 IO 线程（仅文件模式需要）
        if (logToFile.get()) {
            openLogFile();
            if (fileWriter != null) {
                ioRunning = true;
                ioThread = new Thread(this::ioLoop, "Meteor-PacketLogger-IO");
                ioThread.setDaemon(true);
                ioThread.start();
            }
        }
    }

    @Override
    public void onDeactivate() {
        // 停止 IO 线程
        ioRunning = false;
        if (ioThread != null) {
            ioThread.interrupt();
            try { ioThread.join(2000); } catch (InterruptedException ignored) {}
            ioThread = null;
        }

        // 把队列中残留条目写完
        drainQueueToFile();
        closeLogFile();
        fileQueue.clear();

        // 输出 session 统计总结
        if (chatMode.get() != ChatMode.Never) {
            info("Session total: (highlight)%d C2S(default) | (highlight)%d S2C(default)",
                c2sTotal.get(), s2cTotal.get());
        }
    }

    // ════════════════════════════════════════════════════════════
    //  File I/O — 异步写盘
    // ════════════════════════════════════════════════════════════

    private void openLogFile() {
        try {
            Path dir = Path.of("logs", "packet-logger");
            Files.createDirectories(dir);
            Path file = dir.resolve("packets_" + FILE_TS.format(LocalDateTime.now()) + ".log");
            fileWriter = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            error("Failed to open packet log file: " + e.getMessage());
            fileWriter = null;
        }
    }

    private void closeLogFile() {
        if (fileWriter != null) {
            try { fileWriter.close(); } catch (IOException ignored) {}
            fileWriter = null;
        }
    }

    /**
     * IO 线程主循环——每 {@value IO_DRAIN_INTERVAL_MS}ms drain 一次队列并批量 flush。
     * 相比旧版逐条 flush，减少 ~99% 的 fsync 次数。
     */
    private void ioLoop() {
        while (ioRunning) {
            drainQueueToFile();
            try {
                //noinspection BusyWait
                Thread.sleep(IO_DRAIN_INTERVAL_MS);
            } catch (InterruptedException e) {
                break; // onDeactivate 中断退出
            }
        }
    }

    /** 将队列中所有待写条目一次性写入文件，末尾统一 flush */
    private void drainQueueToFile() {
        if (fileWriter == null) return;
        String line;
        int count = 0;
        while ((line = fileQueue.poll()) != null) {
            try {
                fileWriter.write(line);
                fileWriter.newLine();
                count++;
            } catch (IOException e) {
                error("File write error: " + e.getMessage());
                closeLogFile();
                return;
            }
        }
        if (count > 0) {
            try { fileWriter.flush(); } catch (IOException ignored) {}
        }
    }

    // ════════════════════════════════════════════════════════════
    //  Statistics Tick Handler
    // ════════════════════════════════════════════════════════════

    /**
     * 每 20 tick（≈1 秒）输出一次统计摘要到 chat。
     * 如果本周期无任何包记录则静默跳过，避免刷屏。
     */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (chatMode.get() != ChatMode.Statistics) return;

        if (++statsTicks < 20) return;
        statsTicks = 0;

        long dc2s = c2sDelta.getAndSet(0);
        long ds2c = s2cDelta.getAndSet(0);

        // 无包时静默
        if (dc2s == 0 && ds2c == 0) return;

        info("(highlight)+%d C2S(default) | (highlight)+%d S2C(default)  (gray)(total: %d C2S | %d S2C)",
            dc2s, ds2c, c2sTotal.get(), s2cTotal.get());
    }

    // ════════════════════════════════════════════════════════════
    //  Packet Event Handlers
    // ════════════════════════════════════════════════════════════

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        Packet<?> packet = event.packet;

        // Dedicated S2C toggles
        if (packet instanceof BlockUpdateS2CPacket) {
            if (logBlockUpdate.get()) recordPacket("[S2C]", packet, packet.getClass());
            return;
        }
        if (packet instanceof PlayerPositionLookS2CPacket) {
            if (logPlayerPosLook.get()) recordPacket("[S2C]", packet, packet.getClass());
            return;
        }
        if (packet instanceof HealthUpdateS2CPacket) {
            if (logHealthUpdate.get()) recordPacket("[S2C]", packet, packet.getClass());
            return;
        }
        if (packet instanceof PlaySoundS2CPacket) {
            if (logPlaySound.get()) recordPacket("[S2C]", packet, packet.getClass());
            return;
        }

        // Container Fill S2C toggles
        if (packet instanceof OpenScreenS2CPacket) {
            if (logOpenScreen.get()) recordPacket("[S2C]", packet, packet.getClass());
            return;
        }
        if (packet instanceof InventoryS2CPacket) {
            if (logInventorySync.get()) recordPacket("[S2C]", packet, packet.getClass());
            return;
        }
        if (packet instanceof ScreenHandlerSlotUpdateS2CPacket) {
            if (logSlotUpdate.get()) recordPacket("[S2C]", packet, packet.getClass());
            return;
        }
        if (packet instanceof CloseScreenS2CPacket) {
            if (logCloseScreen.get()) recordPacket("[S2C]", packet, packet.getClass());
            return;
        }
        if (packet instanceof BlockEntityUpdateS2CPacket) {
            if (logBlockEntityUpdate.get()) recordPacket("[S2C]", packet, packet.getClass());
            return;
        }

        // Fallback: generic S2C list
        @SuppressWarnings("unchecked")
        Class<? extends Packet<?>> packetClass = (Class<? extends Packet<?>>) packet.getClass();
        if (s2cLogAll.get() || s2cPackets.get().contains(packetClass)) {
            recordPacket("[S2C]", packet, packetClass);
        }
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        Packet<?> packet = event.packet;

        // Dedicated toggles for common C2S packets
        if (packet instanceof PlayerActionC2SPacket p) {
            if (shouldLogAction(p.getAction())) {
                recordPacket("[C2S]", packet, PlayerActionC2SPacket.class);
            }
            return;
        }
        if (packet instanceof PlayerMoveC2SPacket) {
            if (logMovement.get()) recordPacket("[C2S]", packet, packet.getClass());
            return;
        }
        if (packet instanceof PlayerInteractBlockC2SPacket) {
            if (logInteractBlock.get()) recordPacket("[C2S]", packet, packet.getClass());
            return;
        }
        if (packet instanceof PlayerInteractEntityC2SPacket) {
            if (logInteractEntity.get()) recordPacket("[C2S]", packet, packet.getClass());
            return;
        }
        if (packet instanceof UpdateSelectedSlotC2SPacket) {
            if (logSlotChange.get()) recordPacket("[C2S]", packet, packet.getClass());
            return;
        }
        if (packet instanceof HandSwingC2SPacket) {
            if (logHandSwing.get()) recordPacket("[C2S]", packet, packet.getClass());
            return;
        }

        // Container Fill C2S toggles
        if (packet instanceof ClientCommandC2SPacket) {
            if (logClientCommand.get()) recordPacket("[C2S]", packet, packet.getClass());
            return;
        }
        if (packet instanceof ClickSlotC2SPacket) {
            if (logClickSlot.get()) recordPacket("[C2S]", packet, packet.getClass());
            return;
        }
        if (packet instanceof CloseHandledScreenC2SPacket) {
            if (logCloseHandledScreen.get()) recordPacket("[C2S]", packet, packet.getClass());
            return;
        }
        if (packet instanceof PlayerInteractItemC2SPacket) {
            if (logInteractItem.get()) recordPacket("[C2S]", packet, packet.getClass());
            return;
        }
        if (packet instanceof PlayerInputC2SPacket) {
            if (logPlayerInput.get()) recordPacket("[C2S]", packet, packet.getClass());
            return;
        }

        // Fallback: advanced C2S list
        @SuppressWarnings("unchecked")
        Class<? extends Packet<?>> packetClass = (Class<? extends Packet<?>>) packet.getClass();
        if (c2sPacketsExtra.get().contains(packetClass)) {
            recordPacket("[C2S]", packet, packetClass);
        }
    }

    private boolean shouldLogAction(PlayerActionC2SPacket.Action action) {
        return switch (action) {
            case START_DESTROY_BLOCK -> logStartDig.get();
            case STOP_DESTROY_BLOCK -> logStopDig.get();
            case ABORT_DESTROY_BLOCK -> logAbortDig.get();
            case DROP_ITEM, DROP_ALL_ITEMS -> logDropItem.get();
            case RELEASE_USE_ITEM -> logReleaseUse.get();
            case SWAP_ITEM_WITH_OFFHAND -> logSwapOffhand.get();
        };
    }

    // ════════════════════════════════════════════════════════════
    //  Core Recording — 主线程快路径
    // ════════════════════════════════════════════════════════════

    /**
     * 主线程包记录入口。
     * <ol>
     *   <li>原子计数 — 永远执行，~20ns/call</li>
     *   <li>格式化 — 仅 Details chat 或 file 模式需要</li>
     *   <li>Chat 输出 — 仅 Details 模式</li>
     *   <li>队列入队 — 仅 file 模式，非阻塞 offer()</li>
     * </ol>
     */
    private void recordPacket(String direction, Packet<?> packet, Class<?> packetClass) {
        boolean isC2S = "[C2S]".equals(direction);

        // 1) 计数 — 始终执行
        (isC2S ? c2sTotal : s2cTotal).incrementAndGet();
        (isC2S ? c2sDelta : s2cDelta).incrementAndGet();

        boolean needsFormat = chatMode.get() == ChatMode.Details || logToFile.get();
        if (!needsFormat) return; // Statistics/Never + 无文件 → 快速退出

        // 2) 格式化
        String formatted = formatLine(direction, packet, packetClass);

        // 3) Chat 输出（仅 Details）
        if (chatMode.get() == ChatMode.Details) {
            info(formatted);
        }

        // 4) 文件队列（非阻塞 offer）
        if (logToFile.get()) {
            fileQueue.offer(formatted);
        }
    }

    // ════════════════════════════════════════════════════════════
    //  Formatting
    // ════════════════════════════════════════════════════════════

    /**
     * 构造单条包日志行。格式：
     * {@code [C2S] #seq tTICK +ELAPSEDms PacketName detail_fields}
     * <p>
     * 时间戳前缀在文件中始终写入；在 chat Details 模式下由 showTimestamp 控制。
     */
    @SuppressWarnings("unchecked")
    private String formatLine(String direction, Packet<?> packet, Class<?> packetClass) {
        long seq = seqCounter.incrementAndGet();
        long elapsedMs = System.currentTimeMillis() - activateMs;
        long tick = mc.world != null ? mc.world.getTime() : -1;

        String name = PacketUtils.getName((Class<? extends Packet<?>>) packetClass);
        if (name == null) name = packetClass.getSimpleName();

        StringBuilder sb = new StringBuilder(128);
        sb.append(direction).append(' ');

        // 时间戳前缀
        // 对文件：始终包含；对 chat Details：尊重 showTimestamp 设置
        // 此处统一写入——chat 和 file 共享同一格式行
        if (showTimestamp.get() || logToFile.get()) {
            sb.append(String.format("#%d t%d +%dms ", seq, tick, elapsedMs));
        }

        sb.append(name);

        // 详细字段
        String detail = formatSpecial(packet);
        if (detail == null) detail = formatReflective(packet);
        if (detail != null && !detail.isEmpty()) {
            sb.append(' ').append(detail);
        }

        return sb.toString();
    }

    private String formatSpecial(Packet<?> packet) {
        // C2S
        if (packet instanceof PlayerActionC2SPacket p) {
            return "action=" + p.getAction() + " pos=" + p.getPos().toShortString() + " face=" + p.getDirection();
        }
        if (packet instanceof PlayerMoveC2SPacket p) {
            StringBuilder sb = new StringBuilder();
            if (p.changesPosition()) sb.append("pos=(").append(fmt(p.getX(0))).append(", ").append(fmt(p.getY(0))).append(", ").append(fmt(p.getZ(0))).append(") ");
            if (p.changesLook()) sb.append("yaw=").append(fmt(p.getYaw(0))).append(" pitch=").append(fmt(p.getPitch(0))).append(" ");
            sb.append("onGround=").append(p.isOnGround());
            return sb.toString().trim();
        }
        if (packet instanceof UpdateSelectedSlotC2SPacket p) {
            return "slot=" + p.getSelectedSlot();
        }
        if (packet instanceof HandSwingC2SPacket p) {
            return "hand=" + p.getHand();
        }
        // 容器填充相关 C2S — 详细 formatter
        if (packet instanceof PlayerInteractBlockC2SPacket p) {
            BlockHitResult hit = p.getBlockHitResult();
            StringBuilder sb = new StringBuilder();
            sb.append("hand=").append(p.getHand());
            sb.append(" pos=").append(hit.getBlockPos().toShortString());
            sb.append(" face=").append(hit.getSide());
            sb.append(" hitVec=(").append(fmt(hit.getPos().x)).append(", ").append(fmt(hit.getPos().y)).append(", ").append(fmt(hit.getPos().z)).append(")");
            sb.append(" insideBlock=").append(hit.isInsideBlock());
            sb.append(" seq=").append(p.getSequence());
            // 附加本地玩家上下文
            if (mc.player != null) {
                sb.append(" | eyePos=(").append(fmt(mc.player.getEyePos().x)).append(", ").append(fmt(mc.player.getEyePos().y)).append(", ").append(fmt(mc.player.getEyePos().z)).append(")");
                sb.append(" yaw=").append(fmt(mc.player.getYaw())).append(" pitch=").append(fmt(mc.player.getPitch()));
                sb.append(" sneaking=").append(mc.player.isSneaking());
                sb.append(" sprinting=").append(mc.player.isSprinting());
                sb.append(" mainHand=").append(mc.player.getMainHandStack().getItem());
            }
            return sb.toString();
        }
        if (packet instanceof ClientCommandC2SPacket p) {
            StringBuilder sb = new StringBuilder();
            sb.append("mode=").append(p.getMode());
            if (mc.player != null) {
                sb.append(" | sneaking=").append(mc.player.isSneaking());
                sb.append(" sprinting=").append(mc.player.isSprinting());
            }
            return sb.toString();
        }
        if (packet instanceof ClickSlotC2SPacket p) {
            StringBuilder sb = new StringBuilder();
            sb.append("syncId=").append(p.syncId());
            sb.append(" revision=").append(p.revision());
            sb.append(" slot=").append(p.slot());
            sb.append(" button=").append(p.button());
            sb.append(" actionType=").append(p.actionType());
            sb.append(" cursor=").append(p.cursor());
            sb.append(" modifiedSlots=").append(p.modifiedStacks().size());
            return sb.toString();
        }
        if (packet instanceof CloseHandledScreenC2SPacket p) {
            return "syncId=" + p.getSyncId();
        }
        if (packet instanceof PlayerInteractItemC2SPacket p) {
            StringBuilder sb = new StringBuilder();
            sb.append("hand=").append(p.getHand());
            sb.append(" seq=").append(p.getSequence());
            if (mc.player != null) {
                sb.append(" | mainHand=").append(mc.player.getMainHandStack().getItem());
                sb.append(" yaw=").append(fmt(mc.player.getYaw())).append(" pitch=").append(fmt(mc.player.getPitch()));
            }
            return sb.toString();
        }
        if (packet instanceof PlayerInputC2SPacket p) {
            var input = p.input();
            return "forward=" + input.forward() + " backward=" + input.backward()
                + " left=" + input.left() + " right=" + input.right()
                + " jump=" + input.jump() + " sneak=" + input.sneak()
                + " sprint=" + input.sprint();
        }
        // S2C
        if (packet instanceof BlockUpdateS2CPacket p) {
            return "pos=" + p.getPos().toShortString() + " state=" + p.getState();
        }
        if (packet instanceof PlayerPositionLookS2CPacket p) {
            return "teleportId=" + p.teleportId() + " relatives=" + p.relatives();
        }
        if (packet instanceof HealthUpdateS2CPacket p) {
            return "health=" + fmt(p.getHealth()) + " food=" + p.getFood() + " saturation=" + fmt(p.getSaturation());
        }
        if (packet instanceof PlaySoundS2CPacket p) {
            return "sound=" + p.getSound().value().id() + " pos=(" + fmt(p.getX()) + ", " + fmt(p.getY()) + ", " + fmt(p.getZ()) + ") vol=" + fmt(p.getVolume()) + " pitch=" + fmt(p.getPitch());
        }
        // 容器填充相关 S2C — 详细 formatter
        if (packet instanceof OpenScreenS2CPacket p) {
            return "syncId=" + p.getSyncId() + " type=" + p.getScreenHandlerType() + " title=" + p.getName().getString();
        }
        if (packet instanceof InventoryS2CPacket p) {
            List<ItemStack> contents = p.contents();
            int nonEmpty = 0;
            for (ItemStack s : contents) { if (!s.isEmpty()) nonEmpty++; }
            StringBuilder sb = new StringBuilder();
            sb.append("syncId=").append(p.syncId());
            sb.append(" revision=").append(p.revision());
            sb.append(" totalSlots=").append(contents.size());
            sb.append(" nonEmpty=").append(nonEmpty);
            sb.append(" cursor=").append(p.cursorStack().isEmpty() ? "<empty>" : p.cursorStack().getItem() + "x" + p.cursorStack().getCount());
            // 前 9 格摘要
            sb.append(" first9=[");
            for (int i = 0; i < Math.min(9, contents.size()); i++) {
                if (i > 0) sb.append(", ");
                ItemStack s = contents.get(i);
                sb.append(s.isEmpty() ? "_" : s.getItem() + "x" + s.getCount());
            }
            sb.append("]");
            return sb.toString();
        }
        if (packet instanceof ScreenHandlerSlotUpdateS2CPacket p) {
            ItemStack s = p.getStack();
            return "syncId=" + p.getSyncId() + " revision=" + p.getRevision() + " slot=" + p.getSlot()
                + " stack=" + (s.isEmpty() ? "<empty>" : s.getItem() + "x" + s.getCount());
        }
        if (packet instanceof CloseScreenS2CPacket p) {
            return "syncId=" + p.getSyncId();
        }
        if (packet instanceof BlockEntityUpdateS2CPacket p) {
            return "pos=" + p.getPos().toShortString() + " type=" + p.getBlockEntityType();
        }
        return null;
    }

    private String formatReflective(Packet<?> packet) {
        Class<?> cls = packet.getClass();
        RecordComponent[] components = cls.getRecordComponents();

        if (components != null && components.length > 0) {
            StringJoiner sj = new StringJoiner(", ");
            for (RecordComponent rc : components) {
                try {
                    Object val = rc.getAccessor().invoke(packet);
                    sj.add(rc.getName() + "=" + abbreviate(val));
                } catch (Exception ignored) {}
            }
            return sj.toString();
        }

        // Fallback: try declared fields via reflection
        var fields = cls.getDeclaredFields();
        if (fields.length == 0) return null;
        StringJoiner sj = new StringJoiner(", ");
        for (var field : fields) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            try {
                field.setAccessible(true);
                Object val = field.get(packet);
                sj.add(field.getName() + "=" + abbreviate(val));
            } catch (Exception ignored) {}
        }
        return sj.length() > 0 ? sj.toString() : null;
    }

    private static String abbreviate(Object val) {
        if (val == null) return "null";
        String s = val.toString();
        return s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
