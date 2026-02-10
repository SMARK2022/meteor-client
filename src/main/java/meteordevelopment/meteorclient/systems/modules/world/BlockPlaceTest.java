/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerActionResponseS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TimingAttackTest - 三点时序侧信道探测工具
 *
 * 功能：
 * 1. 允许设置 A, B, C 三个探测点
 * 2. 在一个 Tick 内高频交错发送放置包 (A->B->C->A->B->C...)
 * 3. 精确测量并统计每个点的 ACK 回包延迟（微秒级）
 *
 * 用途：
 * 用于检测 GhostBlockMitigation 等反作弊逻辑是否存在 CPU 耗时差异或逻辑分支延迟。
 */
public class BlockPlaceTest extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPointA = settings.createGroup("Point A (Control)");
    private final SettingGroup sgPointB = settings.createGroup("Point B (Target)");
    private final SettingGroup sgPointC = settings.createGroup("Point C (Control)");
    private final SettingGroup sgStats = settings.createGroup("Statistics");

    // ==================== 坐标设置 (A, B, C) ====================

    // Point A
    private final Setting<Integer> ax = sgPointA.add(new IntSetting.Builder().name("a-x").defaultValue(100).build());
    private final Setting<Integer> ay = sgPointA.add(new IntSetting.Builder().name("a-y").defaultValue(64).build());
    private final Setting<Integer> az = sgPointA.add(new IntSetting.Builder().name("a-z").defaultValue(100).build());

    // Point B
    private final Setting<Integer> bx = sgPointB.add(new IntSetting.Builder().name("b-x").defaultValue(10000).build());
    private final Setting<Integer> by = sgPointB.add(new IntSetting.Builder().name("b-y").defaultValue(64).build());
    private final Setting<Integer> bz = sgPointB.add(new IntSetting.Builder().name("b-z").defaultValue(10000).build());

    // Point C
    private final Setting<Integer> cx = sgPointC.add(new IntSetting.Builder().name("c-x").defaultValue(-100).build());
    private final Setting<Integer> cy = sgPointC.add(new IntSetting.Builder().name("c-y").defaultValue(64).build());
    private final Setting<Integer> cz = sgPointC.add(new IntSetting.Builder().name("c-z").defaultValue(-100).build());

    // ==================== 发送配置 ====================

    private final Setting<Integer> iterations = sgGeneral.add(new IntSetting.Builder()
            .name("iterations")
            .description("How many times to repeat the A-B-C pattern in one batch.")
            .defaultValue(50)
            .min(1)
            .sliderMax(200)
            .build());

    private final Setting<Boolean> sendButton = sgGeneral.add(new BoolSetting.Builder()
            .name("send-batch")
            .description("Click to send the packet batch (A-B-C looped).")
            .defaultValue(false)
            .onChanged(value -> {
                if (value)
                    sendBatch();
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
            .description("Log every single ACK delay to chat (Spam warning!).")
            .defaultValue(false)
            .build());

    // ==================== 内部数据结构 ====================

    private enum PointType {
        A, B, C
    }

    // 记录发送出去的包信息
    private record PacketRecord(PointType type, long sendTimeNano) {
    }

    // 序列号 -> 发送记录 映射表
    private final Map<Integer, PacketRecord> pendingPackets = new ConcurrentHashMap<>();

    // 统计结果存储
    private final List<Double> latenciesA = new ArrayList<>();
    private final List<Double> latenciesB = new ArrayList<>();
    private final List<Double> latenciesC = new ArrayList<>();

    public BlockPlaceTest() {
        super(Categories.World, "timing-attack-test", "High precision side-channel timing analysis.");
    }

    @Override
    public void onActivate() {
        clearData();
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
     * 执行批量发送逻辑
     * 顺序：A -> B -> C -> A -> B -> C ...
     */
    private void sendBatch() {
        if (mc.player == null || mc.getNetworkHandler() == null || mc.world == null) {
            sendButton.set(false);
            return;
        }

        // 1. 准备坐标对象
        BlockPos posA = new BlockPos(ax.get(), ay.get(), az.get());
        BlockPos posB = new BlockPos(bx.get(), by.get(), bz.get());
        BlockPos posC = new BlockPos(cx.get(), cy.get(), cz.get());

        // 2. 准备固定的光标和朝向
        // 强制使用顶面中心，模拟最标准的放置
        Vec3d cursorFn = new Vec3d(0.5, 1.0, 0.5);
        Direction face = Direction.UP;

        int totalPackets = iterations.get() * 3;
        info("Sending batch of %d packets (Iter: %d)...", totalPackets, iterations.get());

        long batchStartTime = System.nanoTime();

        // 3. 循环发送
        for (int i = 0; i < iterations.get(); i++) {
            sendOne(posA, cursorFn, face, PointType.A);
            sendOne(posB, cursorFn, face, PointType.B);
            sendOne(posC, cursorFn, face, PointType.C);
        }

        long batchEndTime = System.nanoTime();
        double batchDurationMs = (batchEndTime - batchStartTime) / 1_000_000.0;

        info("Batch sent in %.2f ms.", batchDurationMs);
        sendButton.set(false);
    }

    /**
     * 发送单个探测包
     */
    private void sendOne(BlockPos pos, Vec3d cursor, Direction face, PointType type) {
        // 1. 构造 HitResult
        // 这里的 hitPos 是世界绝对坐标
        Vec3d hitPos = Vec3d.of(pos).add(cursor);
        BlockHitResult hitResult = new BlockHitResult(hitPos, face, pos, false);

        // 2. 获取并递增序列号 (关键步骤)
        int sequence = mc.world.getPendingUpdateManager().getSequence();

        // 3. 构造交互包
        PlayerInteractBlockC2SPacket packet = new PlayerInteractBlockC2SPacket(
                Hand.MAIN_HAND,
                hitResult,
                sequence);

        // 4. 记录发送时间 (纳秒级精度)
        long sendTime = System.nanoTime();
        pendingPackets.put(sequence, new PacketRecord(type, sendTime));

        // 5. 发送数据包 (挥手 + 交互)
        // 必须发送挥手包以绕过 NoSwing 检查
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
        long receiveTime = System.nanoTime(); // 收到包的第一时间记录时间戳

        // 关注 ACK 包 (1.19+ 的确认机制)
        if (event.packet instanceof PlayerActionResponseS2CPacket packet) {
            int seq = packet.sequence();

            // 检查这个序列号是否是我们发出的探测包
            if (pendingPackets.containsKey(seq)) {
                PacketRecord record = pendingPackets.remove(seq);

                // 计算 RTT (Round Trip Time) - 微秒
                double latencyMicros = (receiveTime - record.sendTimeNano) / 1000.0;

                // 记录统计
                recordStat(record.type, latencyMicros);

                // 日志输出 (可选)
                if (logDetails.get()) {
                    String color = switch (record.type) {
                        case A -> "(green)"; // Control
                        case B -> "(red)"; // Target
                        case C -> "(blue)"; // Control
                    };
                    info("%s[%s] Seq: %d | Delay: %.2f µs", color, record.type, seq, latencyMicros);
                }
            }
        }
        // 兼容性处理：如果收到 BlockUpdate (老版本或 Grim 拦截回弹)，虽然没有序列号，
        // 但意味着该位置被拒绝。这通常比 ACK 慢 (主线程处理)。
        // 由于没有序列号，很难精确匹配到具体是哪一次 A/B/C，这里暂不统计 RTT，
        // 只做定性分析：收到 BlockUpdate = 失败/回弹。
    }

    private void recordStat(PointType type, double latency) {
        switch (type) {
            case A -> latenciesA.add(latency);
            case B -> latenciesB.add(latency);
            case C -> latenciesC.add(latency);
        }

        // 实时更新统计摘要 (每收到 10 个包更新一次，避免刷屏)
        int total = latenciesA.size() + latenciesB.size() + latenciesC.size();
        if (total > 0 && total % (iterations.get()) == 0) {
            printSummary();
        }
    }

    private void printSummary() {
        info("=== Batch Statistics (Microseconds) ===");
        printStatLine("Point A (Control)", latenciesA);
        printStatLine("Point B (Target) ", latenciesB);
        printStatLine("Point C (Control)", latenciesC);
    }

    private void printStatLine(String name, List<Double> data) {
        if (data.isEmpty()) {
            info("%s: No Data", name);
            return;
        }

        double min = data.stream().mapToDouble(d -> d).min().orElse(0);
        double max = data.stream().mapToDouble(d -> d).max().orElse(0);
        double avg = data.stream().mapToDouble(d -> d).average().orElse(0);

        // 计算标准差 (Jitter)
        double variance = data.stream().map(d -> Math.pow(d - avg, 2)).mapToDouble(d -> d).average().orElse(0);
        double stdDev = Math.sqrt(variance);

        info("%s: Avg: (highlight)%.2f µs (gray)| Min: %.0f | Max: %.0f | Jitter: %.2f",
                name, avg, min, max, stdDev);
    }
}