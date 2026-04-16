/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.entity.fakeplayer.FakePlayerEntity;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import org.joml.Vector3d;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.passive.FrogEntity;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.*;
import java.util.function.Predicate;

public class KillAura extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTargeting = settings.createGroup("Targeting");
    private final SettingGroup sgTiming = settings.createGroup("Timing");
    private final SettingGroup sgRender = settings.createGroup("渲染");

    // General

    private final Setting<AttackItems> attackWhenHolding = sgGeneral.add(new EnumSetting.Builder<AttackItems>()
        .name("attack-when-holding")
        .description("Only attacks an entity when a specified item is in your hand.")
        .defaultValue(AttackItems.Weapons)
        .build()
    );

    private final Setting<List<Item>> weapons = sgGeneral.add(new ItemListSetting.Builder()
        .name("selected-weapon-types")
        .description("Which types of weapons to attack with (if you select the diamond sword, any type of sword may be used to attack).")
        .defaultValue(Items.DIAMOND_SWORD, Items.DIAMOND_AXE, Items.TRIDENT)
        .filter(FILTER::contains)
        .visible(() -> attackWhenHolding.get() == AttackItems.Weapons)
        .build()
    );

    private final Setting<RotationMode> rotation = sgGeneral.add(new EnumSetting.Builder<RotationMode>()
        .name("rotate")
        .description("Determines when you should rotate towards the target. GrimAC validates attack direction — Always recommended.")
        .defaultValue(RotationMode.Always)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Switches to an acceptable weapon when attacking the target.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Switches to your previous slot when done attacking the target.")
        .defaultValue(false)
        .visible(autoSwitch::get)
        .build()
    );

    private final Setting<ShieldMode> shieldMode = sgGeneral.add(new EnumSetting.Builder<ShieldMode>()
        .name("shield-mode")
        .description("""
            What to do when your target is blocking with a shield:
            - Ignore:   Don't attack them if they are blocking
            - Break:    Swap to an axe to disable the shield (Only if Auto Switch is enabled)
            - None:     Attack them as normal
        """)
        .defaultValue(ShieldMode.None)
        .build()
    );

    private final Setting<Boolean> onlyOnClick = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-click")
        .description("Only attacks when holding left click.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onlyOnLook = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-look")
        .description("Only attacks when looking at an entity.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pauseOnCombat = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-baritone")
        .description("Freezes Baritone temporarily until you are finished attacking the entity.")
        .defaultValue(true)
        .build()
    );

    // Targeting

    private final Setting<Set<EntityType<?>>> entities = sgTargeting.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Entities to attack.")
        .onlyAttackable()
        .defaultValue(EntityType.PLAYER)
        .build()
    );

    private final Setting<SortPriority> priority = sgTargeting.add(new EnumSetting.Builder<SortPriority>()
        .name("priority")
        .description("How to filter targets within range.")
        .defaultValue(SortPriority.ClosestAngle)
        .build()
    );

    private final Setting<Integer> maxTargets = sgTargeting.add(new IntSetting.Builder()
        .name("max-targets")
        .description("How many entities to target at once.")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 5)
        .visible(() -> !onlyOnLook.get())
        .build()
    );

    private final Setting<Double> range = sgTargeting.add(new DoubleSetting.Builder()
        .name("range")
        .description("The maximum range the entity can be to attack it. Clamped to vanilla getEntityInteractionRange() (3.0 survival). GrimAC Reach checks eye→AABB surface ≤ 3.03.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> wallsRange = sgTargeting.add(new DoubleSetting.Builder()
        .name("walls-range")
        .description("The maximum range the entity can be attacked through walls. GrimAC has no wall detection — set equal to range. Clamped to min(this, effectiveRange).")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<EntityAge> passiveMobAgeFilter = sgTargeting.add(new EnumSetting.Builder<EntityAge>()
        .name("passive-mob-age-filter")
        .description("Determines the age of passive mobs to target (animals, villagers).")
        .defaultValue(EntityAge.Adult)
        .build()
    );

    private final Setting<EntityAge> hostileMobAgeFilter = sgTargeting.add(new EnumSetting.Builder<EntityAge>()
        .name("hostile-mob-age-filter")
        .description("Determines the age of hostile mobs to target (zombies, piglins, hoglins, zoglins).")
        .defaultValue(EntityAge.Both)
        .build()
    );

    private final Setting<Boolean> predictMovement = sgTargeting.add(new BoolSetting.Builder()
        .name("predict-movement")
        .description("Predicts target movement based on relative velocity, aiming ahead for better hit accuracy on moving targets.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> predictionTicks = sgTargeting.add(new IntSetting.Builder()
        .name("prediction-ticks")
        .description("How many ticks ahead to predict target position. Higher values lead targets more aggressively.")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 5)
        .visible(predictMovement::get)
        .build()
    );

    private final Setting<Boolean> ignoreNamed = sgTargeting.add(new BoolSetting.Builder()
        .name("ignore-named")
        .description("Whether or not to attack mobs with a name.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> ignorePassive = sgTargeting.add(new BoolSetting.Builder()
        .name("ignore-passive")
        .description("Will only attack sometimes passive mobs if they are targeting you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreTamed = sgTargeting.add(new BoolSetting.Builder()
        .name("ignore-tamed")
        .description("Will avoid attacking mobs you tamed.")
        .defaultValue(false)
        .build()
    );

    // Timing

    private final Setting<Boolean> pauseOnLag = sgTiming.add(new BoolSetting.Builder()
        .name("pause-on-lag")
        .description("Pauses if the server is lagging.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnUse = sgTiming.add(new BoolSetting.Builder()
        .name("pause-on-use")
        .description("Does not attack while using an item.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pauseOnCA = sgTiming.add(new BoolSetting.Builder()
        .name("pause-on-CA")
        .description("Does not attack while CA is placing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> tpsSync = sgTiming.add(new BoolSetting.Builder()
        .name("TPS-sync")
        .description("Tries to sync attack delay with the server's TPS.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> customDelay = sgTiming.add(new BoolSetting.Builder()
        .name("custom-delay")
        .description("Use a custom delay instead of the vanilla cooldown.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> hitDelay = sgTiming.add(new IntSetting.Builder()
        .name("hit-delay")
        .description("How fast you hit the entity in ticks.")
        .defaultValue(11)
        .min(0)
        .sliderMax(60)
        .visible(customDelay::get)
        .build()
    );

    private final Setting<Integer> switchDelay = sgTiming.add(new IntSetting.Builder()
        .name("switch-delay")
        .description("How many ticks to wait before hitting an entity after switching hotbar slots.")
        .defaultValue(0)
        .min(0)
        .sliderMax(10)
        .build()
    );

    private final static ArrayList<Item> FILTER = new ArrayList<>(List.of(Items.DIAMOND_SWORD, Items.DIAMOND_AXE, Items.DIAMOND_PICKAXE, Items.DIAMOND_SHOVEL, Items.DIAMOND_HOE, Items.MACE, Items.DIAMOND_SPEAR, Items.TRIDENT));

    // 渲染

    private final Setting<Boolean> renderTrajectory = sgRender.add(new BoolSetting.Builder()
        .name("render-trajectory")
        .description("开启预测运动后, 渲染目标未来 1 秒的预测轨迹线。")
        .defaultValue(true)
        .visible(predictMovement::get)
        .build()
    );

    private final Setting<SettingColor> trajectoryStartColor = sgRender.add(new ColorSetting.Builder()
        .name("trajectory-start-color")
        .description("轨迹线起点颜色。")
        .defaultValue(new SettingColor(0, 255, 255, 255))
        .visible(() -> predictMovement.get() && renderTrajectory.get())
        .build()
    );

    private final Setting<SettingColor> trajectoryEndColor = sgRender.add(new ColorSetting.Builder()
        .name("trajectory-end-color")
        .description("轨迹线终点颜色 (渐变到此)。")
        .defaultValue(new SettingColor(0, 255, 255, 25))
        .visible(() -> predictMovement.get() && renderTrajectory.get())
        .build()
    );

    private final Setting<Boolean> renderSpeed = sgRender.add(new BoolSetting.Builder()
        .name("render-speed")
        .description("在目标头顶显示其移动速度 (m/s)。")
        .defaultValue(true)
        .visible(predictMovement::get)
        .build()
    );

    private final Setting<Double> speedTextScale = sgRender.add(new DoubleSetting.Builder()
        .name("speed-text-scale")
        .description("速度文字缩放比例。")
        .defaultValue(1.0)
        .min(0.5)
        .sliderRange(0.5, 3.0)
        .visible(() -> predictMovement.get() && renderSpeed.get())
        .build()
    );

    private final List<Entity> targets = new ArrayList<>();
    private final List<Entity> renderCandidates = new ArrayList<>();
    private int switchTimer, hitTimer;
    private boolean wasPathing = false;
    public boolean attacking, swapped;
    public static int previousSlot;

    // 多实体 α-β 追踪器: 为视野内最近 4 个实体各维护独立的速度/加速度估计
    private static class TrackState {
        Vec3d vel = Vec3d.ZERO;   // 平滑后的实体绝对速度 (blocks/tick)
        Vec3d acc = Vec3d.ZERO;   // 估计的加速度 (blocks/tick²)
        int lastTick;             // 上次更新的 tick 序号
        TrackState(int tick) { this.lastTick = tick; }
    }
    private final Map<Integer, TrackState> trackMap = new HashMap<>();
    private static final double TRACK_ALPHA = 0.5;   // 速度响应灵敏度: 半衰期 ~2 tick
    private static final double TRACK_BETA  = 0.15;  // 加速度响应灵敏度: 半衰期 ~5 tick

    public KillAura() {
        super(Categories.Combat, "kill-aura", "Attacks specified entities around you.");
    }

    @Override
    public void onActivate() {
        previousSlot = -1;
        swapped = false;
    }

    @Override
    public void onDeactivate() {
        targets.clear();
        renderCandidates.clear();
        trackMap.clear();
        stopAttacking();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!mc.player.isAlive() || PlayerUtils.getGameMode() == GameMode.SPECTATOR) {
            stopAttacking();
            return;
        }
        if (pauseOnUse.get() && (mc.interactionManager.isBreakingBlock() || mc.player.isUsingItem())) {
            stopAttacking();
            return;
        }
        if (onlyOnClick.get() && !mc.options.attackKey.isPressed()) {
            stopAttacking();
            return;
        }
        if (TickRate.INSTANCE.getTimeSinceLastTick() >= 1f && pauseOnLag.get()) {
            stopAttacking();
            return;
        }
        if (pauseOnCA.get() && Modules.get().get(CrystalAura.class).isActive() && Modules.get().get(CrystalAura.class).kaTimer > 0) {
            stopAttacking();
            return;
        }
        if (onlyOnLook.get()) {
            Entity targeted = mc.targetedEntity;

            if (targeted == null || !entityCheck(targeted)) {
                stopAttacking();
                return;
            }

            targets.clear();
            targets.add(mc.targetedEntity);
            // onlyOnLook: renderCandidates = targets
            renderCandidates.clear();
            renderCandidates.add(mc.targetedEntity);
        } else {
            // === 单遍收集: renderCandidates (最近 4 个合格实体) + targets (在攻击范围内) ===
            collectEntities();
        }

        // 更新所有渲染候选的 α-β 追踪状态
        int tick = mc.player.age;
        for (Entity e : renderCandidates) updateTrack(e, tick);
        cleanStaleTrack(tick);

        if (targets.isEmpty()) {
            stopAttacking();

            // 前瞻预瞄: 在攻击范围外但接近的候选实体, 提前旋转到位（使用前置预测）
            if (rotation.get() == RotationMode.Always && !renderCandidates.isEmpty()) {
                Entity approaching = findApproaching();
                if (approaching != null) {
                    Rotations.rotateWith(
                        phase -> solveEntityAim(approaching, true),
                        30, null   // 低优先级, 无 callback (不攻击)
                    );
                }
            }
            return;
        }

        Entity primary = targets.getFirst();

        if (autoSwitch.get()) {
            FindItemResult weaponResult = new FindItemResult(mc.player.getInventory().getSelectedSlot(), -1);
            if (attackWhenHolding.get() == AttackItems.Weapons) weaponResult = InvUtils.find(this::acceptableWeapon, 0, 8);

            if (shouldShieldBreak()) {
                FindItemResult axeResult = InvUtils.find(itemStack -> itemStack.getItem() instanceof AxeItem, 0, 8);
                if (axeResult.found()) weaponResult = axeResult;
            }

            if (!swapped) {
                previousSlot  = mc.player.getInventory().getSelectedSlot();
                swapped = true;
            }

            InvUtils.swap(weaponResult.slot(), false);
        }

        if (!acceptableWeapon(mc.player.getMainHandStack())) {
            stopAttacking();
            return;
        }

        attacking = true;
        if (pauseOnCombat.get() && PathManagers.get().isPathing() && !wasPathing) {
            PathManagers.get().pause();
            wasPathing = true;
        }

        if (delayCheck()) {
            // 就绪: 攻击瞄准使用实体当前 AABB（不做前置预测），100% GrimAC-safe
            if (rotation.get() != RotationMode.None) {
                Rotations.rotateWith(
                    phase -> solveEntityAim(primary, false),
                    100, () -> commitAttack(primary)
                );
            } else {
                commitAttack(primary);
            }
        } else if (rotation.get() == RotationMode.Always) {
            // 未就绪: 用前置预测做软追踪，减少攻击瞬间旋转角
            Rotations.rotateWith(
                phase -> solveEntityAim(primary, true),
                50, null
            );
        }
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (event.packet instanceof UpdateSelectedSlotC2SPacket) {
            switchTimer = switchDelay.get();
        }
    }

    private void stopAttacking() {
        if (!attacking) return;

        attacking = false;
        if (wasPathing) {
            PathManagers.get().resume();
            wasPathing = false;
        }
        if (swapBack.get() && swapped) {
            InvUtils.swap(previousSlot, false);
            swapped = false;
        }
    }

    private boolean shouldShieldBreak() {
        for (Entity target : targets) {
            if (target instanceof PlayerEntity player) {
                if (player.isBlocking() && shieldMode.get() == ShieldMode.Break) {
                    return true;
                }
            }
        }

        return false;
    }

    // ==================== 实体收集 ====================

    /**
     * 单遍遍历所有实体, 同时填充 renderCandidates (最近 4 个) 和 targets (在攻击范围内)。
     * renderCandidates 不受 range 限制, 只看实体类型/存活/好友等基础过滤。
     * targets 额外要求在 range 内 + 墙壁距离判定。
     */
    private void collectEntities() {
        renderCandidates.clear();
        targets.clear();

        Vec3d eyePos = mc.player.getEyePos();
        double effectiveRange = getEffectiveRange();
        double effectiveRangeSq = effectiveRange * effectiveRange;
        double effectiveWallsRange = Math.min(wallsRange.get(), effectiveRange);
        double effectiveWallsRangeSq = effectiveWallsRange * effectiveWallsRange;

        for (Entity entity : mc.world.getEntities()) {
            if (!entityFilterBase(entity)) continue;

            double distSq = eyeDistSqToAABB(eyePos, entity.getBoundingBox());
            renderCandidates.add(entity);

            // 攻击目标: 在范围内 + 墙壁视线
            if (distSq <= effectiveRangeSq) {
                if (PlayerUtils.canSeeEntity(entity) || distSq <= effectiveWallsRangeSq) {
                    targets.add(entity);
                }
            }
        }

        // 渲染候选: 按距离排序, 取最近 4 个
        renderCandidates.sort(Comparator.comparingDouble(e -> eyeDistSqToAABB(eyePos, e.getBoundingBox())));
        if (renderCandidates.size() > 4) renderCandidates.subList(4, renderCandidates.size()).clear();

        // 攻击目标: 按优先级排序, 取 maxTargets
        targets.sort(priority.get());
        if (targets.size() > maxTargets.get()) targets.subList(maxTargets.get(), targets.size()).clear();
    }

    /**
     * 基础实体过滤 (不含距离判定)。
     * 用于渲染候选和攻击候选的共享前置过滤。
     */
    private boolean entityFilterBase(Entity entity) {
        if (entity.equals(mc.player) || entity.equals(mc.getCameraEntity())) return false;
        if ((entity instanceof LivingEntity livingEntity && livingEntity.isDead()) || !entity.isAlive()) return false;
        if (!entities.get().contains(entity.getType())) return false;
        if (ignoreNamed.get() && entity.hasCustomName()) return false;
        if (ignoreTamed.get()) {
            if (entity instanceof Tameable tameable
                && tameable.getOwner() != null
                && tameable.getOwner().equals(mc.player)
            ) return false;
        }
        if (ignorePassive.get()) {
            if (entity instanceof EndermanEntity enderman && !enderman.isAngry()) return false;
            if ((entity instanceof PiglinEntity || entity instanceof ZombifiedPiglinEntity || entity instanceof WolfEntity) && !((MobEntity) entity).isAttacking()) return false;
        }
        if (entity instanceof PlayerEntity player) {
            if (player.isCreative()) return false;
            if (!Friends.get().shouldAttack(player)) return false;
            if (shieldMode.get() == ShieldMode.Ignore && player.isBlocking()) return false;
            if (player instanceof FakePlayerEntity fakePlayer && fakePlayer.noHit) return false;
        }
        if (entity instanceof LivingEntity livingEntity) {
            // Hostile mobs with baby variants (zombies, piglins, hoglins, zoglins)
            if (entity instanceof ZombieEntity || entity instanceof PiglinEntity
                || entity instanceof HoglinEntity || entity instanceof ZoglinEntity) {
                return switch (hostileMobAgeFilter.get()) {
                    case Baby -> livingEntity.isBaby();
                    case Adult -> !livingEntity.isBaby();
                    case Both -> true;
                };
            }
            // Passive mobs with baby variants (animals, villagers)
            if (entity instanceof PassiveEntity && (!(entity instanceof FrogEntity || entity instanceof ParrotEntity))) {
                return switch (passiveMobAgeFilter.get()) {
                    case Baby -> livingEntity.isBaby();
                    case Adult -> !livingEntity.isBaby();
                    case Both -> true;
                };
            }
        }
        return true;
    }

    /** 眼睛位置到 AABB 最近表面的欧几里得距离² */
    private static double eyeDistSqToAABB(Vec3d eyePos, Box box) {
        double dx = MathHelper.clamp(eyePos.x, box.minX, box.maxX) - eyePos.x;
        double dy = MathHelper.clamp(eyePos.y, box.minY, box.maxY) - eyePos.y;
        double dz = MathHelper.clamp(eyePos.z, box.minZ, box.maxZ) - eyePos.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** 完整实体检测 (含距离) — 用于 onlyOnLook + isStillValidTarget */
    private boolean entityCheck(Entity entity) {
        if (!entityFilterBase(entity)) return false;
        Vec3d eyePos = mc.player.getEyePos();
        double distSq = eyeDistSqToAABB(eyePos, entity.getBoundingBox());
        double effectiveRange = getEffectiveRange();
        if (distSq > effectiveRange * effectiveRange) return false;
        double effectiveWallsRange = Math.min(wallsRange.get(), effectiveRange);
        if (!PlayerUtils.canSeeEntity(entity) && distSq > effectiveWallsRange * effectiveWallsRange) return false;
        return true;
    }

    // ==================== α-β 追踪器 ====================

    /** 更新指定实体的 α-β 滤波状态: 跟踪实体绝对速度 + 加速度 */
    private void updateTrack(Entity entity, int tick) {
        // 实体绝对速度 (position delta, 修复远程玩家 getVelocity()≈0 的问题)
        Vec3d vel = new Vec3d(
            entity.getX() - entity.lastRenderX,
            entity.getY() - entity.lastRenderY,
            entity.getZ() - entity.lastRenderZ
        );

        int id = entity.getId();
        TrackState state = trackMap.get(id);

        if (state != null && state.lastTick == tick - 1) {
            // 连续 tick: α-β 滤波
            Vec3d predicted = state.vel.add(state.acc);
            Vec3d residual = vel.subtract(predicted);
            state.vel = predicted.add(residual.multiply(TRACK_ALPHA));
            state.acc = state.acc.add(residual.multiply(TRACK_BETA));
        } else {
            // 首次或间断: 重新初始化
            state = new TrackState(tick);
            state.vel = vel;
            trackMap.put(id, state);
        }
        state.lastTick = tick;
    }

    /** 清理 5 tick 内未更新的过时追踪条目 */
    private void cleanStaleTrack(int tick) {
        trackMap.values().removeIf(s -> tick - s.lastTick > 5);
    }

    /**
     * 从渲染候选中找到正在接近攻击范围的实体 (用于前瞻预瞄)。
     * 如果候选实体的距离 ≤ attackRange + 2.0, 返回最近的那个。
     */
    private Entity findApproaching() {
        double threshold = getEffectiveRange() + 2.0;
        double thSq = threshold * threshold;
        Vec3d eyePos = mc.player.getEyePos();

        for (Entity e : renderCandidates) {
            if (targets.contains(e)) continue;  // 已在攻击范围, 跳过
            double dSq = eyeDistSqToAABB(eyePos, e.getBoundingBox());
            if (dSq <= thSq) return e;          // renderCandidates 已按距离排序
        }
        return null;
    }

    private boolean delayCheck() {
        if (switchTimer > 0) {
            switchTimer--;
            return false;
        }

        float delay = (customDelay.get()) ? hitDelay.get() : 0.5f;
        if (tpsSync.get()) delay /= (TickRate.INSTANCE.getTickRate() / 20);

        if (customDelay.get()) {
            if (hitTimer < delay) {
                hitTimer++;
                return false;
            } else return true;
        } else return mc.player.getAttackCooldownProgress(delay) >= 1;
    }

    private void commitAttack(Entity target) {
        if (!isActive() || !isStillValidTarget(target)) return;

        // 服务端距离预测: 避免在服务端处理时实体已超出攻击范围的情况出手
        if (predictMovement.get() && isServerRangeExceeded(target)) return;

        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);
        hitTimer = 0;
    }

    /**
     * 估算服务端处理攻击时, GrimAC 射线检测是否可能因距离超标而失败。
     * <p>
     * GrimAC Reach 判定逻辑 (源码 Reach.java):
     * <ol>
     *   <li>maxReach = ENTITY_INTERACTION_RANGE = 3.0</li>
     *   <li>hitboxMargin = threshold(0.0005) + movementThreshold(0.03) = 0.0305</li>
     *   <li>targetBox = getPossibleCollisionBoxes() (old∪new 插值走廊)</li>
     *   <li>targetBox.expand(hitboxMargin)</li>
     *   <li>射线 eye+look × (maxReach+3) 与 targetBox 做交点检测</li>
     *   <li>if (交点距离 > maxReach) → Reach FLAG</li>
     * </ol>
     * <p>
     * 走廊 (old∪new) 不增加 maxReach, 只让 targetBox 在移动方向上拉长使射线更容易命中。
     * hitboxMargin 膨胀 targetBox 约 0.03, 等效让 maxReach 增加 ~0.03。
     * <p>
     * 本方法估算: 服务端 eye→AABB 表面距离 ≈ 客户端距离 + 径向相对速度 × 总延迟 ticks。
     * 如果超过 GrimAC 有效上限, 放弃攻击。
     */
    private boolean isServerRangeExceeded(Entity target) {
        TrackState state = trackMap.get(target.getId());
        if (state == null) return false;

        int pingMs = PlayerUtils.getPing();
        if (pingMs <= 0) return false; // 本地服务器无延迟, 不需预测

        Vec3d eyePos = mc.player.getEyePos();

        // 客户端当前 eye→AABB 表面距离
        Box box = target.getBoundingBox();
        double clientDist = Math.sqrt(eyeDistSqToAABB(eyePos, box));

        // 实体速度 (客户端插值速度, α-β 平滑后)
        Vec3d entityVel = state.vel;
        // 我自己的速度
        Vec3d myVel = mc.player.getVelocity();
        // 相对速度 (实体相对于我)
        Vec3d relVel = entityVel.subtract(myVel);

        // eye→实体中心方向
        double cx = (box.minX + box.maxX) * 0.5 - eyePos.x;
        double cy = (box.minY + box.maxY) * 0.5 - eyePos.y;
        double cz = (box.minZ + box.maxZ) * 0.5 - eyePos.z;
        double dist = Math.sqrt(cx * cx + cy * cy + cz * cz);

        // 径向相对速度投影 (正 = 远离)
        double radialRelVel = dist > 0.01 ? (relVel.x * cx + relVel.y * cy + relVel.z * cz) / dist : 0;

        // 总延迟 ticks: 客户端实体插值滞后(2t for LivingEntity) + 攻击包单程(ping/2/50)
        double oneWayTicks = pingMs / 100.0;
        double interpLag = (target instanceof LivingEntity) ? 2.0 : 1.0;
        double totalLagTicks = interpLag + oneWayTicks;

        // 估算 GrimAC 视角下的 eye→targetBox 表面距离
        double serverDist = clientDist + radialRelVel * totalLagTicks;

        // GrimAC 有效上限:
        // maxReach = 3.0 (ENTITY_INTERACTION_RANGE)
        // + hitboxMargin expand ≈ 0.03 (threshold 0.0005 + movementThreshold 0.03)
        // 走廊 (old∪new) 在移动方向拉长 targetBox, 不增加 maxReach 但让表面更近
        // 保守估计走廊使表面近了 ~entity_speed × 1-2 ticks, 但我们已用表面距离而非中心距离
        double grimMaxReach = 3.0;
        double grimMargin = 0.03; // threshold + movementThreshold
        return serverDist > grimMaxReach + grimMargin;
    }

    private boolean isStillValidTarget(Entity target) {
        if (target == null || target.isRemoved() || !target.isAlive()) return false;
        if (target instanceof LivingEntity living && living.isDead()) return false;
        Vec3d eyePos = mc.player.getEyePos();
        Box hitbox = target.getBoundingBox();
        double dx = MathHelper.clamp(eyePos.x, hitbox.minX, hitbox.maxX) - eyePos.x;
        double dy = MathHelper.clamp(eyePos.y, hitbox.minY, hitbox.maxY) - eyePos.y;
        double dz = MathHelper.clamp(eyePos.z, hitbox.minZ, hitbox.maxZ) - eyePos.z;
        double effectiveRange = getEffectiveRange();
        return dx * dx + dy * dy + dz * dz <= effectiveRange * effectiveRange;
    }

    private double getEffectiveRange() {
        return Math.min(range.get(), mc.player.getEntityInteractionRange());
    }

    /**
     * 构建实体追踪的瞄准解。
     * <p>
     * AimResolver 在两个阶段分别调用本方法:
     * <ul>
     *   <li>PREVIEW — pre-physics 位置, mc.player.getEyePos() 为旧位置, 角度为近似</li>
     *   <li>SEND_FINAL — post-physics 位置, mc.player.getEyePos() 为新位置, 角度为精确</li>
     * </ul>
     * 每次调用都从当前状态重新计算完整的 aim point + yaw/pitch,
     * 而非复用 Pre 阶段的旧结果。这消除了 sprint/鞘翅下 self 位移导致的系统性角度偏差。
     *
     * @param applyPrediction true=使用前置预测（软追踪用），false=瞄当前AABB（攻击用，GrimAC-safe）
     */
    private Rotations.AimSolution solveEntityAim(Entity entity, boolean applyPrediction) {
        Vec3d aim = computeAimPoint(entity, applyPrediction);
        return new Rotations.AimSolution(
            (float) Rotations.getYaw(aim),
            (float) Rotations.getPitch(aim)
        );
    }

    /**
     * 计算给定实体的最优瞄准点。
     * <p>
     * 两种模式:
     * <ul>
     *   <li><b>applyPrediction=true</b> (软追踪): α-β 速度 + 二阶外推, 瞄准实体未来位置,
     *       用于非攻击阶段的前瞻预瞄，减少攻击瞬间的旋转角</li>
     *   <li><b>applyPrediction=false</b> (攻击): 瞄准实体当前 AABB + 前缘微偏,
     *       确保射线 100% 穿过 GrimAC 插值框, 同时微偏向运动前缘提高服务端命中概率</li>
     * </ul>
     * <p>
     * X/Z: clamp(eye, box) + 30% 中心混合<br>
     * Y: 优选上胸区 (62% 身高)
     *
     * @param applyPrediction true=前置预测(软追踪), false=当前AABB(攻击)
     */
    private Vec3d computeAimPoint(Entity entity, boolean applyPrediction) {
        Vec3d eyePos = mc.player.getEyePos();
        Box box = entity.getBoundingBox();

        if (applyPrediction && predictMovement.get()) {
            // 从 trackMap 获取实体的平滑绝对速度 + 加速度
            TrackState state = trackMap.get(entity.getId());
            Vec3d vel = state != null ? state.vel : Vec3d.ZERO;
            Vec3d acc = state != null ? state.acc : Vec3d.ZERO;

            // === 动态 Horizon (P3) ===
            // 基础 horizon = ping + 25ms (服务端排队), 但需要根据相对运动方向调整:
            // - 追击(同向): 相对速度小, 实体位置变化慢, horizon 可以较短
            // - 对冲(反向): 相对速度大, 需要更多前置, 但限制上限避免过度外推
            double t;
            int pingMs = PlayerUtils.getPing();
            if (pingMs > 0) {
                double baseHorizonMs = pingMs + 25.0;
                double baseTicks = baseHorizonMs / 50.0;

                // 自身速度
                Vec3d myVel = mc.player.getVelocity();
                // eye→实体方向
                double dx = (box.minX + box.maxX) * 0.5 - eyePos.x;
                double dz = (box.minZ + box.maxZ) * 0.5 - eyePos.z;
                double dist = Math.sqrt(dx * dx + dz * dz);

                if (dist > 0.01) {
                    // 实体相对于我在 eye→target 方向上的速度 (正=远离, 负=靠近)
                    Vec3d relVel = vel.subtract(myVel);
                    double radialRel = (relVel.x * dx + relVel.z * dz) / dist;

                    // 远离: 需要更多前置 (up to 1.5×), 靠近: 减少前置 (down to 0.6×)
                    double scaleFactor = MathHelper.clamp(1.0 + radialRel * 2.0, 0.6, 1.5);
                    t = Math.max(baseTicks * scaleFactor, 0.5);
                } else {
                    t = Math.max(baseTicks, 0.5);
                }
            } else {
                // 本地服务器 / ping 未知 → 不预测（实体位置已是实时）
                t = 0;
            }

            // 二阶预测: v·t + ½a·t²
            if (t > 0) {
                box = box.offset(
                    vel.x * t + 0.5 * acc.x * t * t,
                    vel.y * t + 0.5 * acc.y * t * t,
                    vel.z * t + 0.5 * acc.z * t * t
                );
            }
        }

        // X/Z: 最近点 + 30% 中心混合
        double aimX = MathHelper.clamp(eyePos.x, box.minX, box.maxX);
        double aimZ = MathHelper.clamp(eyePos.z, box.minZ, box.maxZ);
        double cx = (box.minX + box.maxX) * 0.5;
        double cz = (box.minZ + box.maxZ) * 0.5;
        aimX += (cx - aimX) * 0.3;
        aimZ += (cz - aimZ) * 0.3;

        // 攻击模式: 在当前 AABB 内微偏向运动前缘，使射线更接近 GrimAC 插值框的新端
        // GrimAC 走廊在移动方向上的拉长 ≈ velXZ blocks/tick, 偏移量按速度比例缩放
        if (!applyPrediction && predictMovement.get()) {
            TrackState state = trackMap.get(entity.getId());
            if (state != null) {
                double velXZ = Math.sqrt(state.vel.x * state.vel.x + state.vel.z * state.vel.z);
                if (velXZ > 0.01) {
                    // 速度比例偏移: 0.5 × velXZ 方向, clamp 不出 AABB
                    // 低速(0.04): 偏移 0.02  中速(0.1): 偏移 0.05  高速(0.3): 偏移 0.15
                    double halfWidth = (box.maxX - box.minX) * 0.5;
                    double bias = Math.min(velXZ * 0.5, halfWidth);
                    double dirX = state.vel.x / velXZ;
                    double dirZ = state.vel.z / velXZ;
                    aimX = MathHelper.clamp(aimX + dirX * bias, box.minX, box.maxX);
                    aimZ = MathHelper.clamp(aimZ + dirZ * bias, box.minZ, box.maxZ);
                }
            }
        }

        // Y: 优选上胸区 (62% 身高), 再做 30% 中心混合
        double preferY = box.minY + (box.maxY - box.minY) * 0.62;
        double aimY = MathHelper.clamp(preferY, box.minY, box.maxY);
        double cy = (box.minY + box.maxY) * 0.5;
        aimY += (cy - aimY) * 0.3;

        return new Vec3d(aimX, aimY, aimZ);
    }

    // --- 渲染 ---

    private final Color gradientA = new Color();
    private final Color gradientB = new Color();
    private final Vector3d vec3 = new Vector3d();

    /**
     * 渲染所有渲染候选实体 (最多 4 个) 的预测轨迹线。
     * 使用各实体独立的 α-β 追踪状态进行二阶外推,
     * 线段颜色从 trajectoryStartColor 渐变到 trajectoryEndColor。
     * 碰撞裁剪: 外推点如果落在实心方块内则截断。
     */
    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!predictMovement.get() || !renderTrajectory.get()) return;
        if (renderCandidates.isEmpty()) return;

        Color start = trajectoryStartColor.get();
        Color end = trajectoryEndColor.get();
        int segments = 20;

        for (Entity entity : renderCandidates) {
            TrackState state = trackMap.get(entity.getId());
            if (state == null) continue;

            // 插值当前渲染位置
            double baseX = MathHelper.lerp(event.tickDelta, entity.lastRenderX, entity.getX());
            double baseY = MathHelper.lerp(event.tickDelta, entity.lastRenderY, entity.getY());
            double baseZ = MathHelper.lerp(event.tickDelta, entity.lastRenderZ, entity.getZ());

            // 实体绝对速度/加速度 (trackMap 已是绝对速度)
            double vx = state.vel.x, vy = state.vel.y, vz = state.vel.z;
            double ax = state.acc.x, ay = state.acc.y, az = state.acc.z;

            double prevX = baseX, prevY = baseY, prevZ = baseZ;

            for (int i = 1; i <= segments; i++) {
                double t = i;
                double nextX = baseX + vx * t + 0.5 * ax * t * t;
                double nextY = baseY + vy * t + 0.5 * ay * t * t;
                double nextZ = baseZ + vz * t + 0.5 * az * t * t;

                // 碰撞裁剪: 外推点所在方块为实心则截断
                BlockPos bp = BlockPos.ofFloored(nextX, nextY, nextZ);
                if (mc.world.getBlockState(bp).isFullCube(mc.world, bp)) break;

                float f1 = (i - 1) / (float) segments;
                float f2 = i / (float) segments;
                lerpColor(gradientA, start, end, f1);
                lerpColor(gradientB, start, end, f2);

                event.renderer.line(prevX, prevY, prevZ, nextX, nextY, nextZ, gradientA, gradientB);

                prevX = nextX;
                prevY = nextY;
                prevZ = nextZ;
            }
        }
    }

    /**
     * 在所有渲染候选实体 (最多 4 个) 头顶渲染移动速度 (m/s)。
     */
    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (!predictMovement.get() || !renderSpeed.get()) return;
        if (renderCandidates.isEmpty()) return;

        for (Entity entity : renderCandidates) {
            TrackState state = trackMap.get(entity.getId());
            if (state == null) continue;

            // 实体绝对速度 (trackMap 已是绝对速度)
            double vx = state.vel.x, vy = state.vel.y, vz = state.vel.z;
            double speedMps = Math.sqrt(vx * vx + vy * vy + vz * vz) * 20.0; // blocks/tick → m/s

            // 投影实体头顶到屏幕坐标
            Utils.set(vec3, entity, event.tickDelta);
            double height = entity.getBoundingBox().maxY - entity.getBoundingBox().minY;
            vec3.y += height + 0.5;

            if (NametagUtils.to2D(vec3, speedTextScale.get())) {
                NametagUtils.begin(vec3);
                TextRenderer text = TextRenderer.get();
                text.begin(1, false, true);
                String speedText = String.format("%.1f m/s", speedMps);
                double w = text.getWidth(speedText) / 2;
                text.render(speedText, -w, 0, trajectoryStartColor.get(), true);
                text.end();
                NametagUtils.end();
            }
        }
    }

    private void lerpColor(Color out, Color a, Color b, float f) {
        out.set(
            (int) (a.r + (b.r - a.r) * f),
            (int) (a.g + (b.g - a.g) * f),
            (int) (a.b + (b.b - a.b) * f),
            (int) (a.a + (b.a - a.a) * f)
        );
    }

    private boolean acceptableWeapon(ItemStack stack) {
        if (shouldShieldBreak()) return stack.getItem() instanceof AxeItem;
        if (attackWhenHolding.get() == AttackItems.All) return true;

        if (weapons.get().contains(Items.DIAMOND_SWORD) && stack.isIn(ItemTags.SWORDS)) return true;
        if (weapons.get().contains(Items.DIAMOND_AXE) && stack.isIn(ItemTags.AXES)) return true;
        if (weapons.get().contains(Items.DIAMOND_PICKAXE) && stack.isIn(ItemTags.PICKAXES)) return true;
        if (weapons.get().contains(Items.DIAMOND_SHOVEL) && stack.isIn(ItemTags.SHOVELS)) return true;
        if (weapons.get().contains(Items.DIAMOND_HOE) && stack.isIn(ItemTags.HOES)) return true;
        if (weapons.get().contains(Items.MACE) && stack.getItem() instanceof MaceItem) return true;
        if (weapons.get().contains(Items.DIAMOND_SPEAR) && stack.isIn(ItemTags.SPEARS)) return true;
        return weapons.get().contains(Items.TRIDENT) && stack.getItem() instanceof TridentItem;
    }

    public Entity getTarget() {
        if (!targets.isEmpty()) return targets.getFirst();
        return null;
    }

    @Override
    public String getInfoString() {
        if (!targets.isEmpty()) return EntityUtils.getName(getTarget());
        return null;
    }

    public enum AttackItems {
        Weapons,
        All
    }

    public enum RotationMode {
        Always,
        OnHit,
        None
    }

    public enum ShieldMode {
        Ignore,
        Break,
        None
    }

    public enum EntityAge {
        Baby,
        Adult,
        Both
    }
}
