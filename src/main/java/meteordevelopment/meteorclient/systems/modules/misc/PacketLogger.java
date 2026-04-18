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
import net.minecraft.entity.data.DataTracker;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Vec3d;

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
 * <h3>预设系统</h3>
 * 底部 Presets 组提供一键场景配置（战斗抓包、水晶 PvP、容器调试等），
 * 切换时批量设置开关，用户可继续微调。
 */
public class PacketLogger extends Module {

    // ════════════════════════════════════════════════════════════
    //  Enums
    // ════════════════════════════════════════════════════════════

    public enum ChatMode {
        Never,
        Statistics,
        Details
    }

    public enum Preset {
        None,
        Clear,
        CombatCapture,
        CrystalPvP,
        ContainerDebug,
        FullCapture
    }

    // ════════════════════════════════════════════════════════════
    //  Setting Groups
    // ════════════════════════════════════════════════════════════

    private final SettingGroup sgGeneral        = settings.getDefaultGroup();
    private final SettingGroup sgC2SAction      = settings.createGroup("C2S: Action");
    private final SettingGroup sgC2SMovement    = settings.createGroup("C2S: Movement");
    private final SettingGroup sgC2SInteraction = settings.createGroup("C2S: Interaction");
    private final SettingGroup sgC2SContainer   = settings.createGroup("C2S: Container");
    private final SettingGroup sgS2CCombat      = settings.createGroup("S2C: Combat");
    private final SettingGroup sgS2CEntity      = settings.createGroup("S2C: Entity Tracking");
    private final SettingGroup sgS2CWorld       = settings.createGroup("S2C: World");
    private final SettingGroup sgS2CContainer   = settings.createGroup("S2C: Container");
    private final SettingGroup sgAdvanced       = settings.createGroup("Advanced");
    private final SettingGroup sgPresets        = settings.createGroup("Presets");

    // ════════════════════════════════════════════════════════════
    //  General
    // ════════════════════════════════════════════════════════════

    private final Setting<ChatMode> chatMode = sgGeneral.add(new EnumSetting.Builder<ChatMode>()
        .name("chat-mode")
        .description("Chat output: Never (silent), Statistics (1/sec summary), Details (per-packet).")
        .defaultValue(ChatMode.Statistics)
        .build()
    );

    private final Setting<Boolean> showTimestamp = sgGeneral.add(new BoolSetting.Builder()
        .name("show-timestamp")
        .description("Prefix each detail line with sequence, tick, and elapsed ms.")
        .defaultValue(true)
        .visible(() -> chatMode.get() == ChatMode.Details)
        .build()
    );

    private final Setting<Boolean> logToFile = sgGeneral.add(new BoolSetting.Builder()
        .name("log-to-file")
        .description("Write complete detailed log to logs/packet-logger/ via async I/O thread.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> clearOnStart = sgGeneral.add(new BoolSetting.Builder()
        .name("clear-on-start")
        .description("Clear chat and insert separator line when starting the logger.")
        .defaultValue(false)
        .build()
    );

    // ════════════════════════════════════════════════════════════
    //  C2S: Action (PlayerActionC2SPacket sub-actions)
    // ════════════════════════════════════════════════════════════

    private final Setting<Boolean> logStartDig = sgC2SAction.add(new BoolSetting.Builder()
        .name("start-destroy")
        .description("Log START_DESTROY_BLOCK actions.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logStopDig = sgC2SAction.add(new BoolSetting.Builder()
        .name("stop-destroy")
        .description("Log STOP_DESTROY_BLOCK actions.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logAbortDig = sgC2SAction.add(new BoolSetting.Builder()
        .name("abort-destroy")
        .description("Log ABORT_DESTROY_BLOCK actions.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logDropItem = sgC2SAction.add(new BoolSetting.Builder()
        .name("drop-item")
        .description("Log DROP_ITEM and DROP_ALL_ITEMS actions.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logReleaseUse = sgC2SAction.add(new BoolSetting.Builder()
        .name("release-use-item")
        .description("Log RELEASE_USE_ITEM action.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logSwapOffhand = sgC2SAction.add(new BoolSetting.Builder()
        .name("swap-offhand")
        .description("Log SWAP_ITEM_WITH_OFFHAND action.")
        .defaultValue(false)
        .build()
    );

    // ════════════════════════════════════════════════════════════
    //  C2S: Movement
    // ════════════════════════════════════════════════════════════

    private final Setting<Boolean> logMovement = sgC2SMovement.add(new BoolSetting.Builder()
        .name("player-move")
        .description("Log all PlayerMoveC2SPacket variants (Full, Position, Look, OnGround).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logClientCommand = sgC2SMovement.add(new BoolSetting.Builder()
        .name("client-command")
        .description("Log ClientCommandC2SPacket (sprint/sneak toggle, fall flying).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logPlayerInput = sgC2SMovement.add(new BoolSetting.Builder()
        .name("player-input")
        .description("Log PlayerInputC2SPacket (movement/sneak/sprint input state).")
        .defaultValue(false)
        .build()
    );

    // ════════════════════════════════════════════════════════════
    //  C2S: Interaction
    // ════════════════════════════════════════════════════════════

    private final Setting<Boolean> logInteractBlock = sgC2SInteraction.add(new BoolSetting.Builder()
        .name("interact-block")
        .description("Log PlayerInteractBlockC2SPacket.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logInteractEntity = sgC2SInteraction.add(new BoolSetting.Builder()
        .name("interact-entity")
        .description("Log PlayerInteractEntityC2SPacket.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logInteractItem = sgC2SInteraction.add(new BoolSetting.Builder()
        .name("interact-item")
        .description("Log PlayerInteractItemC2SPacket (use item in air).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logHandSwing = sgC2SInteraction.add(new BoolSetting.Builder()
        .name("hand-swing")
        .description("Log HandSwingC2SPacket.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logSlotChange = sgC2SInteraction.add(new BoolSetting.Builder()
        .name("slot-change")
        .description("Log UpdateSelectedSlotC2SPacket.")
        .defaultValue(false)
        .build()
    );

    // ════════════════════════════════════════════════════════════
    //  C2S: Container
    // ════════════════════════════════════════════════════════════

    private final Setting<Boolean> logClickSlot = sgC2SContainer.add(new BoolSetting.Builder()
        .name("click-slot")
        .description("Log ClickSlotC2SPacket (container slot operations).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logCloseHandledScreen = sgC2SContainer.add(new BoolSetting.Builder()
        .name("close-handled-screen")
        .description("Log CloseHandledScreenC2SPacket (client closes container).")
        .defaultValue(false)
        .build()
    );

    // ════════════════════════════════════════════════════════════
    //  S2C: Combat
    // ════════════════════════════════════════════════════════════

    private final Setting<Boolean> logHealthUpdate = sgS2CCombat.add(new BoolSetting.Builder()
        .name("health-update")
        .description("Log HealthUpdateS2CPacket.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logPlaySound = sgS2CCombat.add(new BoolSetting.Builder()
        .name("play-sound")
        .description("Log PlaySoundS2CPacket.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logDamageTilt = sgS2CCombat.add(new BoolSetting.Builder()
        .name("damage-tilt")
        .description("Log DamageTiltS2CPacket (damage camera tilt direction).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logEntityAnimation = sgS2CCombat.add(new BoolSetting.Builder()
        .name("entity-animation")
        .description("Log EntityAnimationS2CPacket (swing, crit, enchanted hit, etc.).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logEntityVelocity = sgS2CCombat.add(new BoolSetting.Builder()
        .name("entity-velocity")
        .description("Log EntityVelocityUpdateS2CPacket (knockback vectors).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logEntityStatus = sgS2CCombat.add(new BoolSetting.Builder()
        .name("entity-status")
        .description("Log EntityStatusS2CPacket (totem pop, death, etc.).")
        .defaultValue(false)
        .build()
    );

    // ════════════════════════════════════════════════════════════
    //  S2C: Entity Tracking
    // ════════════════════════════════════════════════════════════

    private final Setting<Boolean> logEntityMove = sgS2CEntity.add(new BoolSetting.Builder()
        .name("entity-move")
        .description("Log EntityS2CPacket (MoveRelative, Rotate, RotateAndMoveRelative).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logEntityPosition = sgS2CEntity.add(new BoolSetting.Builder()
        .name("entity-position")
        .description("Log EntityPositionS2CPacket (entity absolute position sync).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logEntityTrackerUpdate = sgS2CEntity.add(new BoolSetting.Builder()
        .name("entity-tracker-update")
        .description("Log EntityTrackerUpdateS2CPacket (entity metadata updates).")
        .defaultValue(false)
        .build()
    );

    // ════════════════════════════════════════════════════════════
    //  S2C: World
    // ════════════════════════════════════════════════════════════

    private final Setting<Boolean> logBlockUpdate = sgS2CWorld.add(new BoolSetting.Builder()
        .name("block-update")
        .description("Log BlockUpdateS2CPacket.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logPlayerPosLook = sgS2CWorld.add(new BoolSetting.Builder()
        .name("player-pos-look")
        .description("Log PlayerPositionLookS2CPacket (server corrections).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logParticle = sgS2CWorld.add(new BoolSetting.Builder()
        .name("particle")
        .description("Log ParticleS2CPacket (crit/sweep/explosion particles).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logExplosion = sgS2CWorld.add(new BoolSetting.Builder()
        .name("explosion")
        .description("Log ExplosionS2CPacket (crystal/TNT/bed explosions).")
        .defaultValue(false)
        .build()
    );

    // ════════════════════════════════════════════════════════════
    //  S2C: Container
    // ════════════════════════════════════════════════════════════

    private final Setting<Boolean> logOpenScreen = sgS2CContainer.add(new BoolSetting.Builder()
        .name("open-screen")
        .description("Log OpenScreenS2CPacket (server opens container GUI).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logInventorySync = sgS2CContainer.add(new BoolSetting.Builder()
        .name("inventory-sync")
        .description("Log InventoryS2CPacket (server sends container contents).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logSlotUpdate = sgS2CContainer.add(new BoolSetting.Builder()
        .name("slot-update")
        .description("Log ScreenHandlerSlotUpdateS2CPacket.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logCloseScreen = sgS2CContainer.add(new BoolSetting.Builder()
        .name("close-screen")
        .description("Log CloseScreenS2CPacket (server closes container).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> logBlockEntityUpdate = sgS2CContainer.add(new BoolSetting.Builder()
        .name("block-entity-update")
        .description("Log BlockEntityUpdateS2CPacket.")
        .defaultValue(false)
        .build()
    );

    // ════════════════════════════════════════════════════════════
    //  Advanced
    // ════════════════════════════════════════════════════════════

    private final Setting<Set<Class<? extends Packet<?>>>> c2sPacketsExtra = sgAdvanced.add(new PacketListSetting.Builder()
        .name("extra-C2S-packets")
        .description("Additional C2S packet types not covered by the dedicated toggles above.")
        .filter(aClass -> PacketUtils.getC2SPackets().contains(aClass))
        .build()
    );

    private final Setting<Set<Class<? extends Packet<?>>>> s2cPackets = sgAdvanced.add(new PacketListSetting.Builder()
        .name("extra-S2C-packets")
        .description("Additional S2C packets not covered by dedicated toggles above.")
        .filter(aClass -> PacketUtils.getS2CPackets().contains(aClass))
        .build()
    );

    private final Setting<Boolean> s2cLogAll = sgAdvanced.add(new BoolSetting.Builder()
        .name("log-all-S2C")
        .description("Log all S2C packets.")
        .defaultValue(false)
        .build()
    );

    // ════════════════════════════════════════════════════════════
    //  Presets
    // ════════════════════════════════════════════════════════════

    @SuppressWarnings("unused")
    private final Setting<Preset> preset = sgPresets.add(new EnumSetting.Builder<Preset>()
        .name("preset")
        .description("One-click scenario profiles. Applying a preset sets toggles then resets to None.")
        .defaultValue(Preset.None)
        .onChanged(this::applyPreset)
        .build()
    );

    public PacketLogger() {
        super(Categories.Misc, "packet-logger", "Logs selected packets with configurable chat/file output.");
    }

    // ════════════════════════════════════════════════════════════
    //  Preset Application
    // ════════════════════════════════════════════════════════════

    private void applyPreset(Preset p) {
        if (p == Preset.None) return;

        // Clear all packet toggles first
        clearAllToggles();

        switch (p) {
            case Clear -> {} // already cleared
            case CombatCapture -> {
                // S2C: Combat — full
                logHealthUpdate.set(true);
                logPlaySound.set(true);
                logDamageTilt.set(true);
                logEntityAnimation.set(true);
                logEntityVelocity.set(true);
                logEntityStatus.set(true);
                // S2C: Entity Tracking — full
                logEntityMove.set(true);
                logEntityPosition.set(true);
                logEntityTrackerUpdate.set(true);
                // S2C: World — partial
                logPlayerPosLook.set(true);
                logParticle.set(true);
            }
            case CrystalPvP -> {
                // CombatCapture base
                logHealthUpdate.set(true);
                logPlaySound.set(true);
                logDamageTilt.set(true);
                logEntityAnimation.set(true);
                logEntityVelocity.set(true);
                logEntityStatus.set(true);
                logEntityMove.set(true);
                logEntityPosition.set(true);
                logEntityTrackerUpdate.set(true);
                logPlayerPosLook.set(true);
                logParticle.set(true);
                // Crystal PvP additions
                logExplosion.set(true);
                logBlockUpdate.set(true);
                // C2S: Interaction (self-view)
                logInteractBlock.set(true);
                logInteractEntity.set(true);
                logInteractItem.set(true);
                logHandSwing.set(true);
                logSlotChange.set(true);
                // C2S: Movement
                logMovement.set(true);
                logClientCommand.set(true);
            }
            case ContainerDebug -> {
                // C2S: Container
                logClickSlot.set(true);
                logCloseHandledScreen.set(true);
                // C2S: Interaction — relevant
                logInteractItem.set(true);
                logInteractBlock.set(true);
                // S2C: Container — full
                logOpenScreen.set(true);
                logInventorySync.set(true);
                logSlotUpdate.set(true);
                logCloseScreen.set(true);
                logBlockEntityUpdate.set(true);
            }
            case FullCapture -> {
                enableAllToggles();
                s2cLogAll.set(true);
            }
            default -> {}
        }

        // Reset preset selector to None after applying
        // (use direct field set to avoid re-triggering onChanged)
        preset.set(Preset.None);
    }

    private void clearAllToggles() {
        // C2S: Action
        logStartDig.set(false);
        logStopDig.set(false);
        logAbortDig.set(false);
        logDropItem.set(false);
        logReleaseUse.set(false);
        logSwapOffhand.set(false);
        // C2S: Movement
        logMovement.set(false);
        logClientCommand.set(false);
        logPlayerInput.set(false);
        // C2S: Interaction
        logInteractBlock.set(false);
        logInteractEntity.set(false);
        logInteractItem.set(false);
        logHandSwing.set(false);
        logSlotChange.set(false);
        // C2S: Container
        logClickSlot.set(false);
        logCloseHandledScreen.set(false);
        // S2C: Combat
        logHealthUpdate.set(false);
        logPlaySound.set(false);
        logDamageTilt.set(false);
        logEntityAnimation.set(false);
        logEntityVelocity.set(false);
        logEntityStatus.set(false);
        // S2C: Entity Tracking
        logEntityMove.set(false);
        logEntityPosition.set(false);
        logEntityTrackerUpdate.set(false);
        // S2C: World
        logBlockUpdate.set(false);
        logPlayerPosLook.set(false);
        logParticle.set(false);
        logExplosion.set(false);
        // S2C: Container
        logOpenScreen.set(false);
        logInventorySync.set(false);
        logSlotUpdate.set(false);
        logCloseScreen.set(false);
        logBlockEntityUpdate.set(false);
        // Advanced
        s2cLogAll.set(false);
    }

    private void enableAllToggles() {
        // C2S: Action
        logStartDig.set(true);
        logStopDig.set(true);
        logAbortDig.set(true);
        logDropItem.set(true);
        logReleaseUse.set(true);
        logSwapOffhand.set(true);
        // C2S: Movement
        logMovement.set(true);
        logClientCommand.set(true);
        logPlayerInput.set(true);
        // C2S: Interaction
        logInteractBlock.set(true);
        logInteractEntity.set(true);
        logInteractItem.set(true);
        logHandSwing.set(true);
        logSlotChange.set(true);
        // C2S: Container
        logClickSlot.set(true);
        logCloseHandledScreen.set(true);
        // S2C: Combat
        logHealthUpdate.set(true);
        logPlaySound.set(true);
        logDamageTilt.set(true);
        logEntityAnimation.set(true);
        logEntityVelocity.set(true);
        logEntityStatus.set(true);
        // S2C: Entity Tracking
        logEntityMove.set(true);
        logEntityPosition.set(true);
        logEntityTrackerUpdate.set(true);
        // S2C: World
        logBlockUpdate.set(true);
        logPlayerPosLook.set(true);
        logParticle.set(true);
        logExplosion.set(true);
        // S2C: Container
        logOpenScreen.set(true);
        logInventorySync.set(true);
        logSlotUpdate.set(true);
        logCloseScreen.set(true);
        logBlockEntityUpdate.set(true);
    }

    // ════════════════════════════════════════════════════════════
    //  Internal State
    // ════════════════════════════════════════════════════════════

    private final AtomicLong seqCounter = new AtomicLong();
    private final AtomicLong c2sTotal = new AtomicLong();
    private final AtomicLong s2cTotal = new AtomicLong();
    private final AtomicLong c2sDelta = new AtomicLong();
    private final AtomicLong s2cDelta = new AtomicLong();

    private long activateMs;
    private int statsTicks;

    // Async file I/O
    private final ConcurrentLinkedQueue<String> fileQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean ioRunning;
    private Thread ioThread;
    private BufferedWriter fileWriter;

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final long IO_DRAIN_INTERVAL_MS = 200;

    // ════════════════════════════════════════════════════════════
    //  Lifecycle
    // ════════════════════════════════════════════════════════════

    @Override
    public void onActivate() {
        seqCounter.set(0);
        c2sTotal.set(0);
        s2cTotal.set(0);
        c2sDelta.set(0);
        s2cDelta.set(0);
        activateMs = System.currentTimeMillis();
        statsTicks = 0;

        if (clearOnStart.get() && mc.inGameHud != null) {
            mc.inGameHud.getChatHud().clear(false);
            info("═══════════════ PacketLogger started ═══════════════");
        }

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
        ioRunning = false;
        if (ioThread != null) {
            ioThread.interrupt();
            try { ioThread.join(2000); } catch (InterruptedException ignored) {}
            ioThread = null;
        }

        drainQueueToFile();
        closeLogFile();
        fileQueue.clear();

        if (chatMode.get() != ChatMode.Never) {
            info("Session total: (highlight)%d C2S(default) | (highlight)%d S2C(default)",
                c2sTotal.get(), s2cTotal.get());
        }
    }

    // ════════════════════════════════════════════════════════════
    //  File I/O
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

    private void ioLoop() {
        while (ioRunning) {
            drainQueueToFile();
            try {
                //noinspection BusyWait
                Thread.sleep(IO_DRAIN_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

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

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (chatMode.get() != ChatMode.Statistics) return;

        if (++statsTicks < 20) return;
        statsTicks = 0;

        long dc2s = c2sDelta.getAndSet(0);
        long ds2c = s2cDelta.getAndSet(0);
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

        // S2C: Combat
        if (packet instanceof HealthUpdateS2CPacket) {
            if (logHealthUpdate.get()) recordPacket("[S2C]", packet, packet.getClass());
            return;
        }
        if (packet instanceof PlaySoundS2CPacket) {
            if (logPlaySound.get()) recordPacket("[S2C]", packet, packet.getClass());
            return;
        }
        if (packet instanceof DamageTiltS2CPacket) {
            if (logDamageTilt.get()) recordPacket("[S2C]", packet, packet.getClass());
            return;
        }
        if (packet instanceof EntityAnimationS2CPacket) {
            if (logEntityAnimation.get()) recordPacket("[S2C]", packet, packet.getClass());
            return;
        }
        if (packet instanceof EntityVelocityUpdateS2CPacket) {
            if (logEntityVelocity.get()) recordPacket("[S2C]", packet, packet.getClass());
            return;
        }
        if (packet instanceof EntityStatusS2CPacket) {
            if (logEntityStatus.get()) recordPacket("[S2C]", packet, packet.getClass());
            return;
        }

        // S2C: Entity Tracking
        if (packet instanceof EntityS2CPacket) {
            if (logEntityMove.get()) recordPacket("[S2C]", packet, packet.getClass());
            return;
        }
        if (packet instanceof EntityPositionS2CPacket) {
            if (logEntityPosition.get()) recordPacket("[S2C]", packet, packet.getClass());
            return;
        }
        if (packet instanceof EntityTrackerUpdateS2CPacket) {
            if (logEntityTrackerUpdate.get()) recordPacket("[S2C]", packet, packet.getClass());
            return;
        }

        // S2C: World
        if (packet instanceof BlockUpdateS2CPacket) {
            if (logBlockUpdate.get()) recordPacket("[S2C]", packet, packet.getClass());
            return;
        }
        if (packet instanceof PlayerPositionLookS2CPacket) {
            if (logPlayerPosLook.get()) recordPacket("[S2C]", packet, packet.getClass());
            return;
        }
        if (packet instanceof ParticleS2CPacket) {
            if (logParticle.get()) recordPacket("[S2C]", packet, packet.getClass());
            return;
        }
        if (packet instanceof ExplosionS2CPacket) {
            if (logExplosion.get()) recordPacket("[S2C]", packet, packet.getClass());
            return;
        }

        // S2C: Container
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

        // C2S: Action
        if (packet instanceof PlayerActionC2SPacket p) {
            if (shouldLogAction(p.getAction())) {
                recordPacket("[C2S]", packet, PlayerActionC2SPacket.class);
            }
            return;
        }

        // C2S: Movement
        if (packet instanceof PlayerMoveC2SPacket) {
            if (logMovement.get()) recordPacket("[C2S]", packet, packet.getClass());
            return;
        }
        if (packet instanceof ClientCommandC2SPacket) {
            if (logClientCommand.get()) recordPacket("[C2S]", packet, packet.getClass());
            return;
        }
        if (packet instanceof PlayerInputC2SPacket) {
            if (logPlayerInput.get()) recordPacket("[C2S]", packet, packet.getClass());
            return;
        }

        // C2S: Interaction
        if (packet instanceof PlayerInteractBlockC2SPacket) {
            if (logInteractBlock.get()) recordPacket("[C2S]", packet, packet.getClass());
            return;
        }
        if (packet instanceof PlayerInteractEntityC2SPacket) {
            if (logInteractEntity.get()) recordPacket("[C2S]", packet, packet.getClass());
            return;
        }
        if (packet instanceof PlayerInteractItemC2SPacket) {
            if (logInteractItem.get()) recordPacket("[C2S]", packet, packet.getClass());
            return;
        }
        if (packet instanceof HandSwingC2SPacket) {
            if (logHandSwing.get()) recordPacket("[C2S]", packet, packet.getClass());
            return;
        }
        if (packet instanceof UpdateSelectedSlotC2SPacket) {
            if (logSlotChange.get()) recordPacket("[C2S]", packet, packet.getClass());
            return;
        }

        // C2S: Container
        if (packet instanceof ClickSlotC2SPacket) {
            if (logClickSlot.get()) recordPacket("[C2S]", packet, packet.getClass());
            return;
        }
        if (packet instanceof CloseHandledScreenC2SPacket) {
            if (logCloseHandledScreen.get()) recordPacket("[C2S]", packet, packet.getClass());
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
    //  Core Recording
    // ════════════════════════════════════════════════════════════

    private void recordPacket(String direction, Packet<?> packet, Class<?> packetClass) {
        boolean isC2S = "[C2S]".equals(direction);

        (isC2S ? c2sTotal : s2cTotal).incrementAndGet();
        (isC2S ? c2sDelta : s2cDelta).incrementAndGet();

        boolean needsFormat = chatMode.get() == ChatMode.Details || logToFile.get();
        if (!needsFormat) return;

        String formatted = formatLine(direction, packet, packetClass);

        if (chatMode.get() == ChatMode.Details) {
            info(formatted);
        }

        if (logToFile.get()) {
            fileQueue.offer(formatted);
        }
    }

    // ════════════════════════════════════════════════════════════
    //  Formatting
    // ════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private String formatLine(String direction, Packet<?> packet, Class<?> packetClass) {
        long seq = seqCounter.incrementAndGet();
        long elapsedMs = System.currentTimeMillis() - activateMs;
        long tick = mc.world != null ? mc.world.getTime() : -1;

        String name = PacketUtils.getName((Class<? extends Packet<?>>) packetClass);
        if (name == null) name = packetClass.getSimpleName();

        StringBuilder sb = new StringBuilder(128);
        sb.append(direction).append(' ');

        if (showTimestamp.get() || logToFile.get()) {
            sb.append(String.format("#%d t%d +%dms ", seq, tick, elapsedMs));
        }

        sb.append(name);

        String detail = formatSpecial(packet);
        if (detail == null) detail = formatReflective(packet);
        if (detail != null && !detail.isEmpty()) {
            sb.append(' ').append(detail);
        }

        return sb.toString();
    }

    private String formatSpecial(Packet<?> packet) {
        // ── C2S formatters ──
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
        if (packet instanceof PlayerInteractBlockC2SPacket p) {
            BlockHitResult hit = p.getBlockHitResult();
            StringBuilder sb = new StringBuilder();
            sb.append("hand=").append(p.getHand());
            sb.append(" pos=").append(hit.getBlockPos().toShortString());
            sb.append(" face=").append(hit.getSide());
            sb.append(" hitVec=(").append(fmt(hit.getPos().x)).append(", ").append(fmt(hit.getPos().y)).append(", ").append(fmt(hit.getPos().z)).append(")");
            sb.append(" insideBlock=").append(hit.isInsideBlock());
            sb.append(" seq=").append(p.getSequence());
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

        // ── S2C formatters ──

        // Combat
        if (packet instanceof HealthUpdateS2CPacket p) {
            return "health=" + fmt(p.getHealth()) + " food=" + p.getFood() + " saturation=" + fmt(p.getSaturation());
        }
        if (packet instanceof PlaySoundS2CPacket p) {
            return "sound=" + p.getSound().value().id() + " pos=(" + fmt(p.getX()) + ", " + fmt(p.getY()) + ", " + fmt(p.getZ()) + ") vol=" + fmt(p.getVolume()) + " pitch=" + fmt(p.getPitch());
        }
        if (packet instanceof DamageTiltS2CPacket p) {
            return "entityId=" + p.id() + " yaw=" + fmt(p.yaw());
        }
        if (packet instanceof EntityAnimationS2CPacket p) {
            return "entityId=" + p.getEntityId() + " type=" + p.getAnimationId() + "(" + animationName(p.getAnimationId()) + ")";
        }
        if (packet instanceof EntityVelocityUpdateS2CPacket p) {
            return "entityId=" + p.getEntityId()
                + " velocity=(" + fmt(p.getVelocityX()) + ", " + fmt(p.getVelocityY()) + ", " + fmt(p.getVelocityZ()) + ")";
        }
        if (packet instanceof EntityStatusS2CPacket p) {
            int status = p.getStatus();
            String entityInfo = "";
            if (mc.world != null) {
                var entity = p.getEntity(mc.world);
                if (entity != null) entityInfo = " entity=" + entity.getId() + "(" + entity.getType().getUntranslatedName() + ")";
            }
            return "status=" + status + "(" + entityStatusName(status) + ")" + entityInfo;
        }

        // Entity Tracking
        if (packet instanceof EntityS2CPacket p) {
            StringBuilder sb = new StringBuilder();
            if (mc.world != null) {
                var entity = p.getEntity(mc.world);
                if (entity != null) sb.append("entityId=").append(entity.getId()).append(' ');
            }
            if (p.isPositionChanged()) {
                sb.append("delta=(").append(p.getDeltaX()).append(", ").append(p.getDeltaY()).append(", ").append(p.getDeltaZ()).append(") ");
            }
            if (p.hasRotation()) {
                sb.append("yaw=").append(p.getYaw()).append(" pitch=").append(p.getPitch()).append(' ');
            }
            sb.append("onGround=").append(p.isOnGround());
            return sb.toString().trim();
        }
        if (packet instanceof EntityPositionS2CPacket p) {
            return "entityId=" + p.entityId() + " relatives=" + p.relatives() + " onGround=" + p.onGround();
        }
        if (packet instanceof EntityTrackerUpdateS2CPacket p) {
            List<DataTracker.SerializedEntry<?>> entries = p.trackedValues();
            StringBuilder sb = new StringBuilder();
            sb.append("entityId=").append(p.id());
            sb.append(" entries=").append(entries.size());
            if (!entries.isEmpty()) {
                sb.append(" [");
                int shown = Math.min(entries.size(), 5);
                for (int i = 0; i < shown; i++) {
                    if (i > 0) sb.append(", ");
                    var entry = entries.get(i);
                    sb.append("id=").append(entry.id()).append(":").append(abbreviate(entry.value()));
                }
                if (entries.size() > shown) sb.append(", ...");
                sb.append("]");
            }
            return sb.toString();
        }

        // World
        if (packet instanceof BlockUpdateS2CPacket p) {
            return "pos=" + p.getPos().toShortString() + " state=" + p.getState();
        }
        if (packet instanceof PlayerPositionLookS2CPacket p) {
            return "teleportId=" + p.teleportId() + " relatives=" + p.relatives();
        }
        if (packet instanceof ParticleS2CPacket p) {
            return "type=" + p.getParameters().getType()
                + " pos=(" + fmt(p.getX()) + ", " + fmt(p.getY()) + ", " + fmt(p.getZ()) + ")"
                + " count=" + p.getCount()
                + " speed=" + fmt(p.getSpeed());
        }
        if (packet instanceof ExplosionS2CPacket p) {
            Vec3d center = p.center();
            StringBuilder sb = new StringBuilder();
            sb.append("pos=(").append(fmt(center.x)).append(", ").append(fmt(center.y)).append(", ").append(fmt(center.z)).append(")");
            p.playerKnockback().ifPresent(kb ->
                sb.append(" knockback=(").append(fmt(kb.x)).append(", ").append(fmt(kb.y)).append(", ").append(fmt(kb.z)).append(")")
            );
            return sb.toString();
        }

        // Container S2C
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

    // ── Animation type mapping ──

    private static String animationName(int id) {
        return switch (id) {
            case 0 -> "SWING_MAIN_HAND";
            case 2 -> "WAKE_UP";
            case 3 -> "SWING_OFF_HAND";
            case 4 -> "CRIT";
            case 5 -> "ENCHANTED_HIT";
            default -> "UNKNOWN_" + id;
        };
    }

    // ── Entity status code mapping ──

    private static String entityStatusName(int status) {
        return switch (status) {
            case 3 -> "DEATH";
            case 9 -> "USE_ITEM_COMPLETE";
            case 29 -> "PULL_HOOKED_ENTITY";
            case 30 -> "TIPPED_ARROW_PARTICLE";
            case 35 -> "TOTEM_OF_UNDYING";
            case 46 -> "SHIELD_BLOCK";
            case 47 -> "SHIELD_BREAK";
            default -> "STATUS_" + status;
        };
    }

    // ── Reflective fallback ──

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
