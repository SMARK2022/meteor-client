/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.PacketListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.network.PacketUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.*;

import java.lang.reflect.RecordComponent;
import java.util.Set;
import java.util.StringJoiner;

public class PacketLogger extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDigging = settings.createGroup("Digging");
    private final SettingGroup sgMovement = settings.createGroup("Movement");
    private final SettingGroup sgInteraction = settings.createGroup("Interaction");
    private final SettingGroup sgSlots = settings.createGroup("Slots & Swing");
    private final SettingGroup sgS2C = settings.createGroup("S2C (Receive)");
    private final SettingGroup sgAdvancedC2S = settings.createGroup("Advanced C2S");

    // ======================== General ========================

    private final Setting<Boolean> detailed = sgGeneral.add(new BoolSetting.Builder()
        .name("detailed")
        .description("Print detailed field values for each packet.")
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

    // ======================== S2C ========================

    private final Setting<Set<Class<? extends Packet<?>>>> s2cPackets = sgS2C.add(new PacketListSetting.Builder()
        .name("S2C-packets")
        .description("Server-to-client packets to log when received.")
        .filter(aClass -> PacketUtils.getS2CPackets().contains(aClass))
        .build()
    );

    private final Setting<Boolean> s2cLogAll = sgS2C.add(new BoolSetting.Builder()
        .name("log-all-S2C")
        .description("Log all S2C packets regardless of the selection above.")
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

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        @SuppressWarnings("unchecked")
        Class<? extends Packet<?>> packetClass = (Class<? extends Packet<?>>) event.packet.getClass();
        if (s2cLogAll.get() || s2cPackets.get().contains(packetClass)) {
            logPacket("[S2C]", event.packet, packetClass);
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
        String name = PacketUtils.getName((Class<? extends Packet<?>>) packetClass);
        if (name == null) name = packetClass.getSimpleName();

        if (!detailed.get()) {
            info("(highlight)%s(default) %s", direction, name);
            return;
        }

        // Try special formatters first, then fallback to reflection
        String detail = formatSpecial(packet);
        if (detail == null) detail = formatReflective(packet);

        if (detail != null && !detail.isEmpty()) {
            info("(highlight)%s(default) %s (gray)%s", direction, name, detail);
        } else {
            info("(highlight)%s(default) %s", direction, name);
        }
    }

    /**
     * Special formatters for well-known packets with meaningful human-readable fields.
     */
    private String formatSpecial(Packet<?> packet) {
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
        return null;
    }

    /**
     * Reflective formatter: dump all record components or declared fields.
     */
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
