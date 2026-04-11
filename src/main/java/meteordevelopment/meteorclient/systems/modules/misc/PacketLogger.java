/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.mixin.CloseHandledScreenC2SPacketAccessor;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.PacketListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
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
import java.util.concurrent.atomic.AtomicLong;

public class PacketLogger extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDigging = settings.createGroup("Digging");
    private final SettingGroup sgMovement = settings.createGroup("Movement");
    private final SettingGroup sgInteraction = settings.createGroup("Interaction");
    private final SettingGroup sgSlots = settings.createGroup("Slots & Swing");
    private final SettingGroup sgS2C = settings.createGroup("S2C (Receive)");
    private final SettingGroup sgContainerFill = settings.createGroup("Container Fill");
    private final SettingGroup sgAdvancedC2S = settings.createGroup("Advanced C2S");

    // ======================== General ========================

    private final Setting<Boolean> detailed = sgGeneral.add(new BoolSetting.Builder()
        .name("detailed")
        .description("Print detailed field values for each packet.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> logToFile = sgGeneral.add(new BoolSetting.Builder()
        .name("log-to-file")
        .description("Write packet log to a file in logs/packet-logger/ directory.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showTimestamp = sgGeneral.add(new BoolSetting.Builder()
        .name("show-timestamp")
        .description("Prefix each log line with tick, elapsed ms, and sequence number.")
        .defaultValue(true)
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
        super(Categories.Misc, "packet-logger", "Logs selected packets to chat with detailed field output.");
    }

    // ======================== State ========================

    private final AtomicLong seqCounter = new AtomicLong();
    private long activateMs;
    private BufferedWriter fileWriter;
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    @Override
    public void onActivate() {
        seqCounter.set(0);
        activateMs = System.currentTimeMillis();
        if (logToFile.get()) openLogFile();
    }

    @Override
    public void onDeactivate() {
        closeLogFile();
    }

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

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        Packet<?> packet = event.packet;

        // Dedicated S2C toggles
        if (packet instanceof BlockUpdateS2CPacket) {
            if (logBlockUpdate.get()) logPacket("[S2C]", packet, packet.getClass());
            return;
        }
        if (packet instanceof PlayerPositionLookS2CPacket) {
            if (logPlayerPosLook.get()) logPacket("[S2C]", packet, packet.getClass());
            return;
        }
        if (packet instanceof HealthUpdateS2CPacket) {
            if (logHealthUpdate.get()) logPacket("[S2C]", packet, packet.getClass());
            return;
        }
        if (packet instanceof PlaySoundS2CPacket) {
            if (logPlaySound.get()) logPacket("[S2C]", packet, packet.getClass());
            return;
        }

        // Container Fill S2C toggles
        if (packet instanceof OpenScreenS2CPacket) {
            if (logOpenScreen.get()) logPacket("[S2C]", packet, packet.getClass());
            return;
        }
        if (packet instanceof InventoryS2CPacket) {
            if (logInventorySync.get()) logPacket("[S2C]", packet, packet.getClass());
            return;
        }
        if (packet instanceof ScreenHandlerSlotUpdateS2CPacket) {
            if (logSlotUpdate.get()) logPacket("[S2C]", packet, packet.getClass());
            return;
        }
        if (packet instanceof CloseScreenS2CPacket) {
            if (logCloseScreen.get()) logPacket("[S2C]", packet, packet.getClass());
            return;
        }
        if (packet instanceof BlockEntityUpdateS2CPacket) {
            if (logBlockEntityUpdate.get()) logPacket("[S2C]", packet, packet.getClass());
            return;
        }

        // Fallback: generic S2C list
        @SuppressWarnings("unchecked")
        Class<? extends Packet<?>> packetClass = (Class<? extends Packet<?>>) packet.getClass();
        if (s2cLogAll.get() || s2cPackets.get().contains(packetClass)) {
            logPacket("[S2C]", packet, packetClass);
        }
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        Packet<?> packet = event.packet;

        // Dedicated toggles for common C2S packets
        if (packet instanceof PlayerActionC2SPacket p) {
            if (shouldLogAction(p.getAction())) {
                logPacket("[C2S]", packet, PlayerActionC2SPacket.class);
            }
            return;
        }
        if (packet instanceof PlayerMoveC2SPacket) {
            if (logMovement.get()) logPacket("[C2S]", packet, packet.getClass());
            return;
        }
        if (packet instanceof PlayerInteractBlockC2SPacket) {
            if (logInteractBlock.get()) logPacket("[C2S]", packet, packet.getClass());
            return;
        }
        if (packet instanceof PlayerInteractEntityC2SPacket) {
            if (logInteractEntity.get()) logPacket("[C2S]", packet, packet.getClass());
            return;
        }
        if (packet instanceof UpdateSelectedSlotC2SPacket) {
            if (logSlotChange.get()) logPacket("[C2S]", packet, packet.getClass());
            return;
        }
        if (packet instanceof HandSwingC2SPacket) {
            if (logHandSwing.get()) logPacket("[C2S]", packet, packet.getClass());
            return;
        }

        // Container Fill C2S toggles
        if (packet instanceof ClientCommandC2SPacket) {
            if (logClientCommand.get()) logPacket("[C2S]", packet, packet.getClass());
            return;
        }
        if (packet instanceof ClickSlotC2SPacket) {
            if (logClickSlot.get()) logPacket("[C2S]", packet, packet.getClass());
            return;
        }
        if (packet instanceof CloseHandledScreenC2SPacket) {
            if (logCloseHandledScreen.get()) logPacket("[C2S]", packet, packet.getClass());
            return;
        }
        if (packet instanceof PlayerInteractItemC2SPacket) {
            if (logInteractItem.get()) logPacket("[C2S]", packet, packet.getClass());
            return;
        }
        if (packet instanceof PlayerInputC2SPacket) {
            if (logPlayerInput.get()) logPacket("[C2S]", packet, packet.getClass());
            return;
        }

        // Fallback: advanced C2S list
        @SuppressWarnings("unchecked")
        Class<? extends Packet<?>> packetClass = (Class<? extends Packet<?>>) packet.getClass();
        if (c2sPacketsExtra.get().contains(packetClass)) {
            logPacket("[C2S]", packet, packetClass);
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

    // ======================== Formatting ========================

    @SuppressWarnings("unchecked")
    private void logPacket(String direction, Packet<?> packet, Class<?> packetClass) {
        long seq = seqCounter.incrementAndGet();
        long elapsedMs = System.currentTimeMillis() - activateMs;
        long tick = mc.world != null ? mc.world.getTime() : -1;

        String name = PacketUtils.getName((Class<? extends Packet<?>>) packetClass);
        if (name == null) name = packetClass.getSimpleName();

        // Build timestamp prefix
        String tsPrefix = "";
        if (showTimestamp.get()) {
            tsPrefix = String.format("#%d t%d +%dms ", seq, tick, elapsedMs);
        }

        // Build detail suffix
        String detail = "";
        if (detailed.get()) {
            String d = formatSpecial(packet);
            if (d == null) d = formatReflective(packet);
            if (d != null && !d.isEmpty()) detail = d;
        }

        // Chat output
        if (!detail.isEmpty()) {
            info("(highlight)%s(default) %s%s %s (gray)%s", direction, tsPrefix, name, "", detail);
        } else {
            info("(highlight)%s(default) %s%s", direction, tsPrefix, name);
        }

        // File output
        if (fileWriter != null) {
            try {
                fileWriter.write(String.format("%s %s%s %s%n", direction, tsPrefix, name, detail));
                fileWriter.flush();
            } catch (IOException ignored) {}
        }
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
            sb.append("syncId=").append(p.getSyncId());
            sb.append(" revision=").append(p.getRevision());
            sb.append(" slot=").append(p.getSlot());
            sb.append(" button=").append(p.getButton());
            sb.append(" actionType=").append(p.getActionType());
            sb.append(" stack=").append(p.getStack().isEmpty() ? "<empty>" : p.getStack().getItem() + "x" + p.getStack().getCount());
            sb.append(" modifiedSlots=").append(p.getModifiedStacks().size());
            return sb.toString();
        }
        if (packet instanceof CloseHandledScreenC2SPacket p) {
            return "syncId=" + ((CloseHandledScreenC2SPacketAccessor) p).getSyncId();
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
            List<ItemStack> contents = p.getContents();
            int nonEmpty = 0;
            for (ItemStack s : contents) { if (!s.isEmpty()) nonEmpty++; }
            StringBuilder sb = new StringBuilder();
            sb.append("syncId=").append(p.getSyncId());
            sb.append(" revision=").append(p.getRevision());
            sb.append(" totalSlots=").append(contents.size());
            sb.append(" nonEmpty=").append(nonEmpty);
            sb.append(" cursor=").append(p.getCursorStack().isEmpty() ? "<empty>" : p.getCursorStack().getItem() + "x" + p.getCursorStack().getCount());
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
