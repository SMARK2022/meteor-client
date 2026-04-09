/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.ClientPlayerInteractionManagerAccessor;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.math.BlockPos;

import java.util.List;

import static net.minecraft.entity.effect.StatusEffects.HASTE;

public class SpeedMine extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .defaultValue(Mode.Damage)
        .onChanged(mode -> removeHaste())
        .build()
    );

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Selected blocks.")
        .filter(block -> block.getHardness() > 0)
        .visible(() -> mode.get() != Mode.Haste)
        .build()
    );

    private final Setting<ListMode> blocksFilter = sgGeneral.add(new EnumSetting.Builder<ListMode>()
        .name("blocks-filter")
        .description("How to use the blocks setting.")
        .defaultValue(ListMode.Blacklist)
        .visible(() -> mode.get() != Mode.Haste)
        .build()
    );

    public final Setting<Double> modifier = sgGeneral.add(new DoubleSetting.Builder()
        .name("modifier")
        .description("Mining speed modifier. An additional value of 0.2 is equivalent to one haste level (1.2 = haste 1).")
        .defaultValue(1.4)
        .visible(() -> mode.get() == Mode.Normal)
        .min(0)
        .build()
    );

    private final Setting<Integer> hasteAmplifier = sgGeneral.add(new IntSetting.Builder()
        .name("haste-amplifier")
        .description("What value of haste to give you. Above 2 not recommended.")
        .defaultValue(2)
        .min(1)
        .visible(() -> mode.get() == Mode.Haste)
        .onChanged(i -> removeHaste())
        .build()
    );

    private final Setting<Boolean> instamine = sgGeneral.add(new BoolSetting.Builder()
        .name("instamine")
        .description("Whether or not to instantly mine blocks under certain conditions.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Damage)
        .build()
    );

    // ======================== Grim State Machine ========================

    private final SettingGroup sgGrim = settings.createGroup("Grim");

    private final Setting<Boolean> grimAware = sgGrim.add(new BoolSetting.Builder()
        .name("grim-aware")
        .description("Grim-aware state machine: cycles between acceleration and cooldown to stay below detection threshold.")
        .defaultValue(false)
        .visible(() -> mode.get() == Mode.Damage)
        .build()
    );

    private final Setting<Integer> grimBalanceBudget = sgGrim.add(new IntSetting.Builder()
        .name("balance-budget")
        .description("Maximum allowed blockBreakBalance (ms) before entering cooldown. Grim flags at 1000ms.")
        .defaultValue(900)
        .min(0)
        .sliderMax(1000)
        .visible(() -> mode.get() == Mode.Damage && grimAware.get())
        .build()
    );

    private final Setting<Integer> rechargeBuffer = sgGrim.add(new IntSetting.Builder()
        .name("recharge-buffer")
        .description("How far below budget (ms) the balance must drop before re-accelerating. 0 = resume as soon as below budget.")
        .defaultValue(240)
        .min(0)
        .sliderMax(500)
        .visible(() -> mode.get() == Mode.Damage && grimAware.get())
        .build()
    );

    // Grim state machine fields
    private GrimPhase grimPhase = GrimPhase.ACCELERATING;
    private double blockBreakBalance = 0;
    private double blockDelayBalance = 0;
    private long lastBreakFinishMs = 0;
    private long grimStartMs = 0;
    private BlockPos grimCurrentPos = null;
    private double grimMaxDelta = 0;

    public SpeedMine() {
        super(Categories.Player, "speed-mine", "Allows you to quickly mine blocks.");
    }

    @Override
    public void onActivate() {
        grimPhase = GrimPhase.ACCELERATING;
        blockBreakBalance = 0;
        blockDelayBalance = 0;
        lastBreakFinishMs = 0;
        grimStartMs = 0;
        grimCurrentPos = null;
        grimMaxDelta = 0;
    }

    @Override
    public void onDeactivate() {
        removeHaste();
        grimPhase = GrimPhase.ACCELERATING;
        blockBreakBalance = 0;
        blockDelayBalance = 0;
        grimCurrentPos = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Utils.canUpdate()) return;

        if (mode.get() == Mode.Haste) {
            StatusEffectInstance haste = mc.player.getStatusEffect(HASTE);

            if (haste == null || haste.getAmplifier() <= hasteAmplifier.get() - 1) {
                mc.player.setStatusEffect(new StatusEffectInstance(HASTE, -1, hasteAmplifier.get() - 1, false, false, false), null);
            }
        }
        else if (mode.get() == Mode.Damage) {
            ClientPlayerInteractionManagerAccessor im = (ClientPlayerInteractionManagerAccessor) mc.interactionManager;
            float progress = im.getBreakingProgress();
            BlockPos pos = im.getCurrentBreakingBlockPos();

            if (pos == null || progress <= 0) return;

            double delta = mc.world.getBlockState(pos).calcBlockBreakingDelta(mc.player, mc.world, pos);

            // 每 tick 更新 Grim 追踪的 maxDelta（镜像 Grim 的 per-tick maximumBlockDamage 更新）
            if (grimAware.get() && grimCurrentPos != null) {
                grimMaxDelta = Math.max(grimMaxDelta, delta);
            }

            // 动态判断是否可以提前完成挖掘
            if (shouldAccelerate(progress, delta)) {
                im.setCurrentBreakingProgress(1f);
            }
        }
    }

    /**
     * 判断是否可以在当前 tick 将挖掘进度强制设为 1.0（跳过剩余挖掘时间）。
     *
     * <p>两种模式：
     * <ul>
     *   <li>无 Grim 感知：当 progress + delta >= 0.7 时直接跳过（旧行为，恒定跳过最后 30%）</li>
     *   <li>Grim 感知：计算 Grim 视角下的跳过量 diff = predictedTime - realTime，
     *       确保 diff <= headroom 或 diff < 25（Grim 衰减区，无条件安全）</li>
     * </ul>
     */
    private boolean shouldAccelerate(float progress, double delta) {
        if (delta <= 0) return false;

        if (!grimAware.get()) {
            // 无 Grim 感知：固定 0.7 阈值（经验值，对多数服务端安全）
            return progress + delta >= 0.7;
        }

        // Grim 冷却期不加速
        if (grimPhase == GrimPhase.COOLING_DOWN) return false;

        // Grim 追踪未初始化：保守回退
        if (grimCurrentPos == null || grimMaxDelta <= 0 || grimStartMs <= 0) {
            return progress + delta >= 0.7;
        }

        // Grim 模型：predictedTime = ceil(1.0 / maxDelta) * 50
        // realTime = 实际已经过去的 wall-clock 时间
        // diff = predictedTime - realTime（提前量，即 Grim 会记入 blockBreakBalance 的值）
        double predictedTime = Math.ceil(1.0 / grimMaxDelta) * 50;
        double realTime = System.currentTimeMillis() - grimStartMs;
        double diff = predictedTime - realTime;

        // diff < 25: Grim 走衰减路径（blockBreakBalance *= 0.9），天然安全
        if (diff < 25) return true;

        // diff >= 25: 提前量不超出当前余量即可
        return diff <= getBreakBudgetHeadroom();
    }

    @EventHandler
    private void onPacket(PacketEvent.Send event) {
        if (mode.get() != Mode.Damage) return;
        if (!(event.packet instanceof PlayerActionC2SPacket packet)) return;

        // Grim balance tracking — catches ALL START/STOP/ABORT packets (normal mining, instamine, PacketMine)
        if (grimAware.get()) {
            if (packet.getAction() == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK) {
                grimTrackStart(packet.getPos());
            } else if (packet.getAction() == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK) {
                grimTrackFinish();
            } else if (packet.getAction() == PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK) {
                grimTrackAbort();
            }
        }
    }

    private void removeHaste() {
        if (!Utils.canUpdate()) return;

        StatusEffectInstance haste = mc.player.getStatusEffect(HASTE);
        if (haste != null && !haste.shouldShowIcon()) mc.player.removeStatusEffect(HASTE);
    }

    // ======================== Grim Balance Tracking ========================

    /**
     * Mirror Grim FastBreak START_DIGGING: update blockDelayBalance and reset per-block state.
     */
    private void grimTrackStart(BlockPos pos) {
        if (mc.world == null) return;
        long now = System.currentTimeMillis();

        // blockDelayBalance update (mirrors Grim)
        double breakDelay = now - lastBreakFinishMs;
        if (breakDelay >= 275) {
            blockDelayBalance *= 0.9;
        } else {
            blockDelayBalance += (300 - breakDelay);
        }

        // 直接使用 now（镜像 Grim: startBreak = System.currentTimeMillis()）
        grimStartMs = now;
        grimCurrentPos = pos.toImmutable();
        grimMaxDelta = mc.world.getBlockState(pos).calcBlockBreakingDelta(mc.player, mc.world, pos);

        clampGrimBalance();
    }

    /**
     * Mirror Grim FastBreak FINISHED_DIGGING: update blockBreakBalance and transition state machine.
     */
    private void grimTrackFinish() {
        long now = System.currentTimeMillis();

        if (grimCurrentPos != null && grimMaxDelta > 0) {
            double predictedTime = Math.ceil(1.0 / grimMaxDelta) * 50;
            double realTime = now - grimStartMs;
            double diff = predictedTime - realTime;

            if (diff < 25) {
                blockBreakBalance *= 0.9;
            } else {
                blockBreakBalance += diff;
            }
            clampGrimBalance();
        }

        lastBreakFinishMs = grimStartMs = now;

        updateGrimPhase();
    }

    /**
     * ABORT_DESTROY_BLOCK: Grim 不更新 balance，但需要重置当前目标。
     * 如果不重置，下一次 FINISH 会误算时间差。
     */
    private void grimTrackAbort() {
        grimCurrentPos = null;
        grimMaxDelta = 0;
    }

    private void updateGrimPhase() {
        if (grimPhase == GrimPhase.ACCELERATING) {
            if (blockBreakBalance >= grimBalanceBudget.get()) {
                grimPhase = GrimPhase.COOLING_DOWN;
            }
        } else if (grimPhase == GrimPhase.COOLING_DOWN) {
            if ((grimBalanceBudget.get() - blockBreakBalance) >= rechargeBuffer.get()) {
                grimPhase = GrimPhase.ACCELERATING;
            }
        }
    }

    private void clampGrimBalance() {
        double max = 1000;
        blockBreakBalance = Math.max(-max, Math.min(blockBreakBalance, max));
        blockDelayBalance = Math.max(-max, Math.min(blockDelayBalance, max));
    }

    // ======================== Public API (for PacketMine integration) ========================

    /** Whether the Grim-aware state machine is active. */
    public boolean isGrimAware() {
        return isActive() && mode.get() == Mode.Damage && grimAware.get();
    }

    /** Whether currently in cooldown phase (not boosting). */
    public boolean isGrimCoolingDown() {
        return isGrimAware() && grimPhase == GrimPhase.COOLING_DOWN;
    }

    /** Current estimated Grim blockBreakBalance. */
    public double getBlockBreakBalance() {
        return blockBreakBalance;
    }

    /** Current estimated Grim blockDelayBalance. */
    public double getBlockDelayBalance() {
        return blockDelayBalance;
    }

    /**
     * 返回当前可用的 blockBreakBalance 余量（ms）。
     *
     * <p>即 grimBalanceBudget - blockBreakBalance。
     * PacketMine 用此值计算可以提前发送 STOP 的时间量。
     * 值 <= 0 表示没有余量。
     */
    public double getBreakBudgetHeadroom() {
        return Math.max(0, grimBalanceBudget.get() - blockBreakBalance);
    }

    public boolean filter(Block block) {
        if (blocksFilter.get() == ListMode.Blacklist && !blocks.get().contains(block)) return true;
        return blocksFilter.get() == ListMode.Whitelist && blocks.get().contains(block);
    }

    public boolean instamine() {
        if (!isActive() || mode.get() != Mode.Damage || !instamine.get()) return false;
        if (grimAware.get() && grimPhase == GrimPhase.COOLING_DOWN) return false;
        return true;
    }

    public enum Mode {
        Normal,
        Haste,
        Damage
    }

    public enum ListMode {
        Whitelist,
        Blacklist
    }

    public enum GrimPhase {
        ACCELERATING,
        COOLING_DOWN
    }
}
