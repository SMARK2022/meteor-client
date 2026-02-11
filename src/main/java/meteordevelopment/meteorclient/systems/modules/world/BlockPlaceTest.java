/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerActionResponseS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BlockPlaceTest (Timing Attack)
 * Refactored:
 * 1. Uses Snake Pattern (A-B-C-C-B-A) to eliminate serialization order bias.
 * 2. Spreads packets over Ticks instead of instant batching.
 */
public class BlockPlaceTest extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSequence = settings.createGroup("Sequence Control");
    private final SettingGroup sgPointA = settings.createGroup("Point A (Control)");
    private final SettingGroup sgPointB = settings.createGroup("Point B (Target)");
    private final SettingGroup sgPointC = settings.createGroup("Point C (Control)");

    // ==================== 坐标设置 (A, B, C) ====================

    private final Setting<Integer> ax = sgPointA.add(new IntSetting.Builder().name("a-x").defaultValue(100).build());
    private final Setting<Integer> ay = sgPointA.add(new IntSetting.Builder().name("a-y").defaultValue(64).build());
    private final Setting<Integer> az = sgPointA.add(new IntSetting.Builder().name("a-z").defaultValue(100).build());

    private final Setting<Integer> bx = sgPointB.add(new IntSetting.Builder().name("b-x").defaultValue(10000).build());
    private final Setting<Integer> by = sgPointB.add(new IntSetting.Builder().name("b-y").defaultValue(64).build());
    private final Setting<Integer> bz = sgPointB.add(new IntSetting.Builder().name("b-z").defaultValue(10000).build());

    private final Setting<Integer> cx = sgPointC.add(new IntSetting.Builder().name("c-x").defaultValue(-100).build());
    private final Setting<Integer> cy = sgPointC.add(new IntSetting.Builder().name("c-y").defaultValue(64).build());
    private final Setting<Integer> cz = sgPointC.add(new IntSetting.Builder().name("c-z").defaultValue(-100).build());

    // ==================== 序列号控制 ====================

    private final Setting<Boolean> manualSeq = sgSequence.add(new BoolSetting.Builder()
            .name("manual-sequence")
            .description("Override the client's internal sequence ID.")
            .defaultValue(false)
            .build());

    private final Setting<Integer> startSeqId = sgSequence.add(new IntSetting.Builder()
            .name("start-id")
            .description("The Sequence ID for the first packet in the batch.")
            .defaultValue(0)
            .visible(manualSeq::get)
            .build());

    // ==================== 发送配置 ====================

    private final Setting<Integer> durationTicks = sgGeneral.add(new IntSetting.Builder()
            .name("duration-ticks")
            .description("How many ticks to keep sending packets (20 ticks = 1 second).")
            .defaultValue(40)
            .min(1)
            .sliderMax(100)
            .build());

    private final Setting<Integer> patternsPerTick = sgGeneral.add(new IntSetting.Builder()
            .name("patterns-per-tick")
            .description("How many A-B-C-C-B-A cycles to send per tick. (1 cycle = 6 packets).")
            .defaultValue(1)
            .min(1)
            .sliderMax(10)
            .build());

    private final Setting<Boolean> sendButton = sgGeneral.add(new BoolSetting.Builder()
            .name("start-test")
            .description("Start the timing attack test.")
            .defaultValue(false)
            .onChanged(value -> {
                if (value)
                    startBatch();
                else
                    stopBatch();
            })
            .build());

    private final Setting<Boolean> clearStats = sgGeneral.add(new BoolSetting.Builder()
            .name("clear-stats")
            .description("Clear recorded timing data.")
            .defaultValue(false)
            .onChanged(value -> {
                if (value)
                    clearData();
            })
            .build());

    private final Setting<Boolean> logDetails = sgGeneral.add(new BoolSetting.Builder()
            .name("log-details")
            .description("Log ALL ACK packets (even unmatched ones) to chat.")
            .defaultValue(false)
            .build());

    // ==================== 内部数据结构 ====================

    private enum PointType {
        A, B, C
    }

    private record PacketRecord(PointType type, long sendTimeNano) {
    }

    private final Map<Integer, PacketRecord> pendingPackets = new ConcurrentHashMap<>();
    private final List<Double> latenciesA = new ArrayList<>();
    private final List<Double> latenciesB = new ArrayList<>();
    private final List<Double> latenciesC = new ArrayList<>();

    // 状态控制
    private boolean isSending = false;
    private int ticksPassed = 0;
    private long batchStartTimeNano = 0;
    private AtomicInteger globalSeqCounter;

    // 预定义的蛇形模式：A -> B -> C -> C -> B -> A
    // 这样可以抵消先发后至的延迟偏差
    private final PointType[] SNAKE_PATTERN = {
            PointType.A, PointType.B, PointType.C,
            PointType.C, PointType.B, PointType.A
    };

    public BlockPlaceTest() {
        super(Categories.World, "timing-attack-test", "High precision side-channel timing analysis.");
    }

    @Override
    public void onActivate() {
        clearData();
        isSending = false;
        sendButton.set(false);
    }

    @Override
    public void onDeactivate() {
        isSending = false;
        sendButton.set(false);
    }

    private void clearData() {
        pendingPackets.clear();
        latenciesA.clear();
        latenciesB.clear();
        latenciesC.clear();
        if (isActive())
            info("Timing data cleared.");
        clearStats.set(false);
    }

    /**
     * 初始化批次测试状态
     */
    private void startBatch() {
        if (mc.player == null || mc.world == null) {
            sendButton.set(false);
            return;
        }

        isSending = true;
        ticksPassed = 0;
        batchStartTimeNano = System.nanoTime();

        // 初始化序列号
        int currentId;
        if (manualSeq.get()) {
            currentId = startSeqId.get();
            info("Starting test with MANUAL Start ID: %d", currentId);
        } else {
            currentId = mc.world.getPendingUpdateManager().getSequence();
            info("Starting test with CLIENT Sequence: %d", currentId);
        }
        globalSeqCounter = new AtomicInteger(currentId);

        info("Test started. Duration: %d ticks. Pattern: Snake (A-B-C-C-B-A).", durationTicks.get());
    }

    private void stopBatch() {
        if (!isSending)
            return;
        isSending = false;
        sendButton.set(false);
        info("Test finished. Sent for %d ticks. Total ID reached: %d", ticksPassed, globalSeqCounter.get());
        printSummary();
    }

    /**
     * Tick 事件循环：在每个 Tick 分发数据包
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!isSending || mc.player == null)
            return;

        // 检查是否达到设定时长
        if (ticksPassed >= durationTicks.get()) {
            stopBatch();
            return;
        }

        // 准备坐标
        BlockPos posA = new BlockPos(ax.get(), ay.get(), az.get());
        BlockPos posB = new BlockPos(bx.get(), by.get(), bz.get());
        BlockPos posC = new BlockPos(cx.get(), cy.get(), cz.get());
        Vec3d cursorFn = new Vec3d(0.5, 1.0, 0.5);
        Direction face = Direction.UP;

        // 执行发包循环
        // patternsPerTick = 1 意味着发送 A,B,C,C,B,A (共6包)
        for (int i = 0; i < patternsPerTick.get(); i++) {
            for (PointType p : SNAKE_PATTERN) {
                BlockPos targetPos = switch (p) {
                    case A -> posA;
                    case B -> posB;
                    case C -> posC;
                };
                sendOne(targetPos, cursorFn, face, p, globalSeqCounter.getAndIncrement());
            }
        }

        ticksPassed++;
    }

    /**
     * 发送单个探测包
     */
    private void sendOne(BlockPos pos, Vec3d cursor, Direction face, PointType type, int sequence) {
        Vec3d hitPos = Vec3d.of(pos).add(cursor);
        BlockHitResult hitResult = new BlockHitResult(hitPos, face, pos, false);

        PlayerInteractBlockC2SPacket packet = new PlayerInteractBlockC2SPacket(
                Hand.MAIN_HAND,
                hitResult,
                sequence);

        long sendTime = System.nanoTime();
        pendingPackets.put(sequence, new PacketRecord(type, sendTime));

        // 依然需要发 Swing 包，否则可能被服务端忽略
        mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
        mc.getNetworkHandler().sendPacket(packet);
    }

    /**
     * 监听回包 (ACK)
     */
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null)
            return;
        long receiveTime = System.nanoTime();

        if (event.packet instanceof PlayerActionResponseS2CPacket packet) {
            int seq = packet.sequence();

            boolean matched = pendingPackets.containsKey(seq);
            double latencyMicros = 0;
            PacketRecord record = null;

            if (matched) {
                record = pendingPackets.remove(seq);
                latencyMicros = (receiveTime - record.sendTimeNano) / 1000.0;
                recordStat(record.type, latencyMicros);
            }

            if (logDetails.get()) {
                // 计算距离整个测试开始的时间，用于过滤
                double timeFromStart = (receiveTime - batchStartTimeNano) / 1_000_000.0;

                // 过滤器：如果距离测试开始已经过了 (持续时间 + 3秒宽容度)，则不打印
                // 这样可以防止下一轮测试开始前还在弹上一轮的日志
                double maxLogTime = (durationTicks.get() * 50) + 3000;

                if (timeFromStart > maxLogTime) {
                    return;
                }

                if (matched && record != null) {
                    String color = switch (record.type) {
                        case A -> "(green)";
                        case B -> "(red)";
                        case C -> "(blue)";
                    };
                    info("%s[%s] Seq:%d +%.0fµs", color, record.type, seq, latencyMicros);
                } else {
                    info("(gray)[?] Seq:%d (Unmatched)", seq);
                }
            }
        }
    }

    private void recordStat(PointType type, double latency) {
        switch (type) {
            case A -> latenciesA.add(latency);
            case B -> latenciesB.add(latency);
            case C -> latenciesC.add(latency);
        }
        // 注意：现在统计报告改为在 stopBatch() 时统一输出，不再在中间输出，减少刷屏
    }

    private void printSummary() {
        info("=== Batch Statistics (Microseconds) ===");
        info("Samples: A=%d, B=%d, C=%d", latenciesA.size(), latenciesB.size(), latenciesC.size());
        printStatLine("Point A (Control)", latenciesA);
        printStatLine("Point B (Target) ", latenciesB);
        printStatLine("Point C (Control)", latenciesC);
    }

    private void printStatLine(String name, List<Double> data) {
        if (data.isEmpty()) {
            info("%s: No Data", name);
            return;
        }

        List<Double> sortedData = new ArrayList<>(data);
        sortedData.sort(Double::compare);

        double avg = data.stream().mapToDouble(d -> d).average().orElse(0);

        double median;
        int size = sortedData.size();
        if (size % 2 == 1) {
            median = sortedData.get(size / 2);
        } else {
            median = (sortedData.get(size / 2 - 1) + sortedData.get(size / 2)) / 2.0;
        }

        double variance = data.stream().map(d -> Math.pow(d - avg, 2)).mapToDouble(d -> d).average().orElse(0);
        double stdDev = Math.sqrt(variance);

        info("%s: Avg: (highlight)%.0f µs (gray)| Median: %.0f µs | Jitter: %.2f", name, avg, median, stdDev);
    }
}