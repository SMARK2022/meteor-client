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
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * BlockPlaceTest - 用于测试远距离方块放置和追踪服务器响应
 *
 * 功能：
 * 1. 向指定坐标发送方块放置数据包
 * 2. 追踪服务器返回的所有响应包（BlockChange, AcknowledgeBlockChanges, ChunkData 等）
 * 3. 在 GUI 中显示完整的数据包序列，用于分析 GhostBlockMitigation 侧信道
 *
 * 测试目标：
 * - 验证在载具中（船/矿车）向远距离（如 10000 格外）发送放置包是否会触发侧信道
 * - 对比不同区块状态（未加载/空地/有建筑）下的服务器响应差异
 */
public class BlockPlaceTest extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTarget = settings.createGroup("Target Coordinates");
    private final SettingGroup sgCursor = settings.createGroup("Cursor Position");
    private final SettingGroup sgTracking = settings.createGroup("Packet Tracking");

    // ==================== 目标坐标设置 ====================

    // 目标方块的 X 坐标
    private final Setting<Integer> targetX = sgTarget.add(new IntSetting.Builder()
        .name("target-x")
        .description("Target block X coordinate.")
        .defaultValue(10000)
        .range(-30000000, 30000000)
        .sliderRange(-10000, 10000)
        .build()
    );

    // 目标方块的 Y 坐标
    private final Setting<Integer> targetY = sgTarget.add(new IntSetting.Builder()
        .name("target-y")
        .description("Target block Y coordinate.")
        .defaultValue(64)
        .range(-64, 320)
        .sliderRange(-64, 320)
        .build()
    );

    // 目标方块的 Z 坐标
    private final Setting<Integer> targetZ = sgTarget.add(new IntSetting.Builder()
        .name("target-z")
        .description("Target block Z coordinate.")
        .defaultValue(10000)
        .range(-30000000, 30000000)
        .sliderRange(-10000, 10000)
        .build()
    );

    // 交互面选择（默认为顶面 UP）
    private final Setting<Direction> targetFace = sgTarget.add(new EnumSetting.Builder<Direction>()
        .name("target-face")
        .description("Which face of the block to interact with.")
        .defaultValue(Direction.UP)
        .build()
    );

    // ==================== 光标位置设置 ====================

    // 光标 X 坐标（方块内部的相对位置，0.0-1.0）
    private final Setting<Double> cursorX = sgCursor.add(new DoubleSetting.Builder()
        .name("cursor-x")
        .description("Cursor X position within the block (0.0-1.0).")
        .defaultValue(0.5)
        .range(0.0, 1.0)
        .sliderRange(0.0, 1.0)
        .build()
    );

    // 光标 Y 坐标（方块内部的相对位置，0.0-1.0）
    private final Setting<Double> cursorY = sgCursor.add(new DoubleSetting.Builder()
        .name("cursor-y")
        .description("Cursor Y position within the block (0.0-1.0).")
        .defaultValue(1.0)
        .range(0.0, 1.0)
        .sliderRange(0.0, 1.0)
        .build()
    );

    // 光标 Z 坐标（方块内部的相对位置，0.0-1.0）
    private final Setting<Double> cursorZ = sgCursor.add(new DoubleSetting.Builder()
        .name("cursor-z")
        .description("Cursor Z position within the block (0.0-1.0).")
        .defaultValue(0.5)
        .range(0.0, 1.0)
        .sliderRange(0.0, 1.0)
        .build()
    );

    // ==================== 操作按钮 ====================

    // 发送数据包按钮
    private final Setting<Boolean> sendButton = sgGeneral.add(new BoolSetting.Builder()
        .name("send-packet")
        .description("Click to send a block placement packet once.")
        .defaultValue(false)
        .onChanged(value -> {
            if (value) {
                sendBlockPlacePacket();
            }
        })
        .build()
    );

    // 清空追踪记录按钮
    private final Setting<Boolean> clearButton = sgGeneral.add(new BoolSetting.Builder()
        .name("clear-sequence")
        .description("Click to clear packet tracking sequence.")
        .defaultValue(false)
        .onChanged(value -> {
            if (value) {
                clearSequence();
            }
        })
        .build()
    );

    // ==================== 追踪设置 ====================

    // 是否在聊天中显示数据包信息
    private final Setting<Boolean> logToChat = sgTracking.add(new BoolSetting.Builder()
        .name("log-to-chat")
        .description("Log packet information to chat.")
        .defaultValue(true)
        .build()
    );

    // 是否追踪 BlockChange 包
    private final Setting<Boolean> trackBlockChange = sgTracking.add(new BoolSetting.Builder()
        .name("track-block-change")
        .description("Track BlockUpdate packets (indicates block resync).")
        .defaultValue(true)
        .build()
    );

    // 是否追踪 AcknowledgeBlockChanges 包
    private final Setting<Boolean> trackAcknowledge = sgTracking.add(new BoolSetting.Builder()
        .name("track-acknowledge")
        .description("Track AcknowledgeBlockChanges packets (1.19+).")
        .defaultValue(true)
        .build()
    );

    // 是否追踪 ChunkData 包
    private final Setting<Boolean> trackChunkData = sgTracking.add(new BoolSetting.Builder()
        .name("track-chunk-data")
        .description("Track ChunkData packets.")
        .defaultValue(false)
        .build()
    );

    // 是否追踪 SetSlot 包
    private final Setting<Boolean> trackSetSlot = sgTracking.add(new BoolSetting.Builder()
        .name("track-set-slot")
        .description("Track SetSlot packets (item resync).")
        .defaultValue(true)
        .build()
    );

    // ==================== 内部状态 ====================

    // 数据包序列追踪列表
    private final List<String> packetSequence = new ArrayList<>();

    // 最后一次发送的时间戳
    private long lastSendTime = 0;

    // 序列号计数器（用于追踪每次发送）
    private int sequenceId = 0;

    public BlockPlaceTest() {
        super(Categories.World, "block-place-test", "Test remote block placement and track server responses.");
    }

    @Override
    public void onActivate() {
        packetSequence.clear();
        sequenceId = 0;

        if (logToChat.get()) {
            info("Block Place Test activated. Current position: (highlight)%.1f, %.1f, %.1f",
                mc.player.getX(), mc.player.getY(), mc.player.getZ());
            info("Target: (highlight)%d, %d, %d", targetX.get(), targetY.get(), targetZ.get());
            info("In vehicle: (highlight)%s", mc.player.hasVehicle());
        }
    }

    @Override
    public void onDeactivate() {
        // 模块关闭时打印完整的数据包序列摘要
        if (logToChat.get() && !packetSequence.isEmpty()) {
            info("=== Packet Sequence Summary ===");
            info("Total packets: (highlight)%d", packetSequence.size());

            // 统计不同类型的数据包数量
            long blockChangeCount = packetSequence.stream().filter(s -> s.contains("BlockUpdate")).count();
            long acknowledgeCount = packetSequence.stream().filter(s -> s.contains("Acknowledge")).count();
            long chunkDataCount = packetSequence.stream().filter(s -> s.contains("ChunkData")).count();
            long setSlotCount = packetSequence.stream().filter(s -> s.contains("SetSlot")).count();

            info("BlockUpdate: (highlight)%d", blockChangeCount);
            info("Acknowledge: (highlight)%d", acknowledgeCount);
            info("ChunkData: (highlight)%d", chunkDataCount);
            info("SetSlot: (highlight)%d", setSlotCount);
        }
    }

    /**
     * 发送方块放置数据包
     *
     * 关键点：
     * 1. 使用 PlayerInteractBlockC2SPacket 而不是直接调用 interactBlock()
     * 2. 这样可以绕过客户端的距离检查，直接发送到服务器
     * 3. 服务器会依次执行所有的反作弊检查（包括 FarPlace 和 GhostBlockMitigation）
     */
    private void sendBlockPlacePacket() {
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        // 构造目标坐标
        BlockPos targetPos = new BlockPos(targetX.get(), targetY.get(), targetZ.get());

        // 构造光标位置（方块内部的点击位置）
        Vec3d cursorPos = new Vec3d(cursorX.get(), cursorY.get(), cursorZ.get());

        // 构造 BlockHitResult
        Vec3d hitPos = Vec3d.of(targetPos).add(cursorPos);
        BlockHitResult hitResult = new BlockHitResult(
            hitPos,              // 命中点的世界坐标
            targetFace.get(),    // 点击的面
            targetPos,           // 方块坐标
            false                // 是否在方块内部
        );

        // 构造数据包
        PlayerInteractBlockC2SPacket packet = new PlayerInteractBlockC2SPacket(
            Hand.MAIN_HAND,      // 使用主手
            hitResult,           // 命中结果
            0                    // 序列号（用于 1.19+ 的同步）
        );

        // 发送数据包
        mc.getNetworkHandler().sendPacket(packet);

        // 记录发送信息
        lastSendTime = System.currentTimeMillis();
        sequenceId++;

        // 添加到序列追踪
        String sendLog = String.format("[#%d] SENT PlayerInteractBlock -> (%d, %d, %d) face=%s cursor=(%.2f, %.2f, %.2f)",
            sequenceId, targetX.get(), targetY.get(), targetZ.get(),
            targetFace.get().name(), cursorX.get(), cursorY.get(), cursorZ.get());

        packetSequence.add(sendLog);

        if (logToChat.get()) {
            info(sendLog);

            // 显示当前玩家状态（用于分析载具豁免）
            if (mc.player.hasVehicle()) {
                info("Player in vehicle: (highlight)%s", mc.player.getVehicle().getType().getName().getString());
            }

            // 计算距离
            double distance = Math.sqrt(
                Math.pow(targetX.get() - mc.player.getX(), 2) +
                Math.pow(targetY.get() - mc.player.getY(), 2) +
                Math.pow(targetZ.get() - mc.player.getZ(), 2)
            );
            info("Distance: (highlight)%.1f blocks", distance);
        }

        // 重置按钮状态（在方法结束后，避免自引用）
        sendButton.set(false);
    }

    /**
     * 数据包接收事件处理（追踪服务器响应）
     *
     * 关键数据包类型：
     * 1. BlockUpdateS2CPacket - 服务器强制同步方块状态（表示检测到幽灵方块）
     * 2. BlockBreakingProgressS2CPacket - 方块破坏进度更新
     * 3. ChunkDataS2CPacket - 区块数据更新（可能表示区块加载）
     * 4. ScreenHandlerSlotUpdateS2CPacket - 物品栏同步（可能表示物品被回弹）
     *
     * 侧信道的关键点：
     * - 如果收到 BlockUpdateS2CPacket → 服务器认为是幽灵方块，调用了 place.resync()
     * - 如果什么都没收到 → 服务器认为是合法放置，或者 GhostBlockMitigation 提前 return
     */
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null) return;

        String packetInfo = null;
        long timeSinceSend = System.currentTimeMillis() - lastSendTime;

        // 追踪 BlockUpdate（方块状态回弹）
        if (trackBlockChange.get() && event.packet instanceof BlockUpdateS2CPacket packet) {
            BlockPos pos = packet.getPos();
            packetInfo = String.format("[#%d +%dms] RECV BlockUpdate -> (%d, %d, %d) state=%s",
                sequenceId, timeSinceSend, pos.getX(), pos.getY(), pos.getZ(),
                packet.getState().getBlock().getName().getString());
        }

        // 追踪 AcknowledgeBlockChanges（1.19+ 的确认包）
        else if (trackAcknowledge.get() && event.packet instanceof PlayerActionResponseS2CPacket packet) {
            packetInfo = String.format("[#%d +%dms] RECV AcknowledgeBlockChanges -> sequence=%d",
                sequenceId, timeSinceSend, packet.sequence());
        }

        // 追踪 ChunkData（区块数据更新）
        else if (trackChunkData.get() && event.packet instanceof ChunkDataS2CPacket packet) {
            packetInfo = String.format("[#%d +%dms] RECV ChunkData -> chunk=(%d, %d)",
                sequenceId, timeSinceSend, packet.getChunkX(), packet.getChunkZ());
        }

        // 追踪 SetSlot（物品栏同步）
        else if (trackSetSlot.get() && event.packet instanceof ScreenHandlerSlotUpdateS2CPacket packet) {
            packetInfo = String.format("[#%d +%dms] RECV SetSlot -> slot=%d item=%s",
                sequenceId, timeSinceSend, packet.getSlot(),
                packet.getStack().isEmpty() ? "EMPTY" : packet.getStack().getItem().getName().getString());
        }

        // 如果检测到目标数据包，记录并显示
        if (packetInfo != null) {
            packetSequence.add(packetInfo);

            if (logToChat.get()) {
                // 使用不同颜色高亮关键信息
                if (packetInfo.contains("BlockUpdate")) {
                    info("(red)%s", packetInfo);  // 红色 - 方块回弹（侧信道的关键证据）
                } else if (packetInfo.contains("Acknowledge")) {
                    info("(green)%s", packetInfo);  // 绿色 - 确认包
                } else if (packetInfo.contains("ChunkData")) {
                    info("(yellow)%s", packetInfo);  // 黄色 - 区块数据
                } else {
                    info("(gray)%s", packetInfo);  // 灰色 - 其他包
                }
            }
        }
    }

    /**
     * 手动触发一次方块放置（绑定快捷键使用）
     */
    public void sendOnce() {
        sendBlockPlacePacket();
    }

    /**
     * 清空数据包序列追踪记录
     */
    public void clearSequence() {
        packetSequence.clear();
        sequenceId = 0;
        if (logToChat.get()) {
            info("Packet sequence cleared.");
        }

        // 重置按钮状态（在方法结束后，避免自引用）
        clearButton.set(false);
    }

    /**
     * 获取数据包序列（供外部调用，如 GUI 显示）
     */
    public List<String> getPacketSequence() {
        return new ArrayList<>(packetSequence);
    }

    /**
     * 获取最新的数据包信息（供 HUD 显示）
     */
    public String getLatestPacketInfo() {
        if (packetSequence.isEmpty()) {
            return "No packets tracked yet";
        }
        return packetSequence.get(packetSequence.size() - 1);
    }

    /**
     * 分析当前的数据包序列，判断是否存在侧信道
     *
     * 侧信道特征：
     * - 发送了 PlayerInteractBlock 包
     * - 但没有收到任何 BlockUpdate 或 Acknowledge 包
     * - 这表示 GhostBlockMitigation 在第 57 行 return 了（检测到非空气方块）
     *
     * 返回值：
     * - "NO_RESPONSE" - 可能存在侧信道（服务器静默接受）
     * - "BLOCK_UPDATE" - 收到方块回弹（正常的幽灵方块检测）
     * - "ACKNOWLEDGED" - 收到确认包（1.19+）
     * - "UNKNOWN" - 无法判断
     */
    public String analyzeResponse() {
        if (packetSequence.isEmpty() || sequenceId == 0) {
            return "UNKNOWN";
        }

        // 检查最后一次发送后是否有响应
        String lastSend = null;
        boolean hasResponse = false;

        for (int i = packetSequence.size() - 1; i >= 0; i--) {
            String entry = packetSequence.get(i);

            if (entry.contains("SENT")) {
                lastSend = entry;
                break;
            }

            if (entry.contains("RECV")) {
                hasResponse = true;
            }
        }

        if (lastSend == null) {
            return "UNKNOWN";
        }

        if (!hasResponse) {
            // 没有收到任何响应 → 可能是侧信道
            return "NO_RESPONSE (SIDE_CHANNEL?)";
        }

        // 检查响应类型
        for (int i = packetSequence.size() - 1; i >= 0; i--) {
            String entry = packetSequence.get(i);

            if (entry.contains("SENT")) {
                break;
            }

            if (entry.contains("BlockUpdate")) {
                return "BLOCK_UPDATE (Ghost block detected)";
            }

            if (entry.contains("Acknowledge")) {
                return "ACKNOWLEDGED";
            }
        }

        return "UNKNOWN";
    }
}
