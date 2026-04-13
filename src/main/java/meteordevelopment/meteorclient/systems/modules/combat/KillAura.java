/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.ZombifiedPiglinEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public class KillAura extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTargeting = settings.createGroup("Targeting");
    private final SettingGroup sgTiming = settings.createGroup("Timing");

    // General

    private final Setting<Weapon> weapon = sgGeneral.add(new EnumSetting.Builder<Weapon>()
        .name("weapon")
        .description("Only attacks an entity when a specified weapon is in your hand.")
        .defaultValue(Weapon.All)
        .build()
    );

    private final Setting<RotationMode> rotation = sgGeneral.add(new EnumSetting.Builder<RotationMode>()
        .name("rotate")
        .description("Determines when you should rotate towards the target.")
        .defaultValue(RotationMode.Always)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Switches to your selected weapon when attacking the target.")
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

    private final Setting<ShieldMode> shieldMode = sgGeneral.add(new EnumSetting.Builder<ShieldMode>()
        .name("shield-mode")
        .description("Will try and use an axe to break target shields.")
        .defaultValue(ShieldMode.Break)
        .visible(() -> autoSwitch.get() && weapon.get() != Weapon.Axe)
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
        .description("The maximum range the entity can be to attack it.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> wallsRange = sgTargeting.add(new DoubleSetting.Builder()
        .name("walls-range")
        .description("The maximum range the entity can be attacked through walls.")
        .defaultValue(3.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<EntityAge> mobAgeFilter = sgTargeting.add(new EnumSetting.Builder<EntityAge>()
        .name("mob-age-filter")
        .description("Determines the age of the mobs to target (baby, adult, or both).")
        .defaultValue(EntityAge.Adult)
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

    private final List<Entity> targets = new ArrayList<>();
    private int switchTimer, hitTimer;
    private boolean wasPathing = false;
    public boolean attacking, swapped;
    public static int previousSlot;

    // α-β 滤波器状态 — 平滑相对速度 + 估计相对加速度 (二阶预测)
    private Vec3d trackedVel = Vec3d.ZERO;
    private Vec3d trackedAcc = Vec3d.ZERO;
    private Entity trackedEntity = null;
    private static final double TRACK_ALPHA = 0.5;   // 速度响应灵敏度: 半衰期 ~2 tick
    private static final double TRACK_BETA = 0.15;   // 加速度响应灵敏度: 半衰期 ~5 tick

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
        stopAttacking();
        trackedEntity = null;
        trackedVel = Vec3d.ZERO;
        trackedAcc = Vec3d.ZERO;
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
        } else {
            targets.clear();
            TargetUtils.getList(targets, this::entityCheck, priority.get(), maxTargets.get());
        }

        if (targets.isEmpty()) {
            stopAttacking();
            return;
        }

        Entity primary = targets.getFirst();

        if (autoSwitch.get()) {
            Predicate<ItemStack> predicate = switch (weapon.get()) {
                case Axe -> stack -> stack.getItem() instanceof AxeItem;
                case Sword -> stack -> stack.getItem() instanceof SwordItem;
                case Mace -> stack -> stack.getItem() instanceof MaceItem;
                case Trident -> stack -> stack.getItem() instanceof TridentItem;
                case All -> stack -> stack.getItem() instanceof AxeItem || stack.getItem() instanceof SwordItem || stack.getItem() instanceof MaceItem || stack.getItem() instanceof TridentItem;
                default -> o -> true;
            };
            FindItemResult weaponResult = InvUtils.findInHotbar(predicate);

            if (shouldShieldBreak()) {
                FindItemResult axeResult = InvUtils.findInHotbar(itemStack -> itemStack.getItem() instanceof AxeItem);
                if (axeResult.found()) weaponResult = axeResult;
            }

            if (!swapped) {
                previousSlot  = mc.player.getInventory().selectedSlot;
                swapped = true;
            }
            InvUtils.swap(weaponResult.slot(), false);
        }

        if (!itemInHand()) {
            stopAttacking();
            return;
        }

        attacking = true;
        if (pauseOnCombat.get() && PathManagers.get().isPathing() && !wasPathing) {
            PathManagers.get().pause();
            wasPathing = true;
        }

        if (delayCheck()) {
            // 就绪: 用 AimResolver 提交旋转, SEND_FINAL 时从 post-physics 位置重建 aim
            if (rotation.get() != RotationMode.None) {
                Rotations.rotateWith(
                    phase -> solveEntityAim(primary),
                    100, () -> commitAttack(primary)
                );
            } else {
                commitAttack(primary);
            }
        } else if (rotation.get() == RotationMode.Always) {
            // 未就绪: 软追踪, 保持朝向目标 (低优先级)
            Rotations.rotateWith(
                phase -> solveEntityAim(primary),
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
                if (player.blockedByShield(mc.world.getDamageSources().playerAttack(mc.player)) && shieldMode.get() == ShieldMode.Break) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean entityCheck(Entity entity) {
        if (entity.equals(mc.player) || entity.equals(mc.cameraEntity)) return false;
        if ((entity instanceof LivingEntity livingEntity && livingEntity.isDead()) || !entity.isAlive()) return false;

        // Eye-based distance to entity AABB — matches server-side reach semantics
        Vec3d eyePos = mc.player.getEyePos();
        Box hitbox = entity.getBoundingBox();
        double dx = MathHelper.clamp(eyePos.x, hitbox.minX, hitbox.maxX) - eyePos.x;
        double dy = MathHelper.clamp(eyePos.y, hitbox.minY, hitbox.maxY) - eyePos.y;
        double dz = MathHelper.clamp(eyePos.z, hitbox.minZ, hitbox.maxZ) - eyePos.z;
        double distSq = dx * dx + dy * dy + dz * dz;

        double effectiveRange = getEffectiveRange();
        if (distSq > effectiveRange * effectiveRange) return false;

        if (!entities.get().contains(entity.getType())) return false;
        if (ignoreNamed.get() && entity.hasCustomName()) return false;
        double effectiveWallsRange = Math.min(wallsRange.get(), effectiveRange);
        if (!PlayerUtils.canSeeEntity(entity) && distSq > effectiveWallsRange * effectiveWallsRange) return false;
        if (ignoreTamed.get()) {
            if (entity instanceof Tameable tameable
                && tameable.getOwnerUuid() != null
                && tameable.getOwnerUuid().equals(mc.player.getUuid())
            ) return false;
        }
        if (ignorePassive.get()) {
            if (entity instanceof EndermanEntity enderman && !enderman.isAngry()) return false;
            if (entity instanceof ZombifiedPiglinEntity piglin && !piglin.isAttacking()) return false;
            if (entity instanceof WolfEntity wolf && !wolf.isAttacking()) return false;
        }
        if (entity instanceof PlayerEntity player) {
            if (player.isCreative()) return false;
            if (!Friends.get().shouldAttack(player)) return false;
            if (shieldMode.get() == ShieldMode.Ignore && player.blockedByShield(mc.world.getDamageSources().playerAttack(mc.player))) return false;
        }
        if (entity instanceof AnimalEntity animal) {
            return switch (mobAgeFilter.get()) {
                case Baby -> animal.isBaby();
                case Adult -> !animal.isBaby();
                case Both -> true;
            };
        }
        return true;
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

        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);
        hitTimer = 0;
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
     */
    private Rotations.AimSolution solveEntityAim(Entity entity) {
        Vec3d aim = computeAimPoint(entity);
        return new Rotations.AimSolution(
            (float) Rotations.getYaw(aim),
            (float) Rotations.getPitch(aim)
        );
    }

    /**
     * 计算给定实体的最优瞄准点。
     * <p>
     * 算法:
     * 1. 从位置差分导出目标速度 (修复 entity.getVelocity() 对远程玩家无效的问题)
     * 2. α-β 滤波: 平滑相对速度 + 估计加速度 (捕捉鞘翅飞行曲率)
     * 3. 二阶预测: v·t + ½a·t² (比纯匀速更精确)
     * 4. 动态 Horizon: 从网络延迟自动计算预测时域
     * 5. X/Z: clamp(eye, box) + 30% 中心混合
     * 6. Y: 优选上胸区 (62% 身高)
     */
    private Vec3d computeAimPoint(Entity entity) {
        Vec3d eyePos = mc.player.getEyePos();
        Box box = entity.getBoundingBox();

        if (predictMovement.get()) {
            // === 改进 1: 位置差分导出速度 ===
            // entity.getVelocity() 对远程玩家返回 ~(0,0,0), 因为服务端仅在击退时发送速度包。
            // 改用 (currentPos - prevPos) 导出实际每 tick 位移, 对所有实体类型可靠。
            Vec3d targetVel = new Vec3d(
                entity.getX() - entity.prevX,
                entity.getY() - entity.prevY,
                entity.getZ() - entity.prevZ
            );
            Vec3d selfVel = mc.player.getVelocity();
            Vec3d relVel = targetVel.subtract(selfVel);

            // === 改进 2: α-β 滤波器 ===
            // 从相对速度序列中同时提取平滑速度和加速度估计。
            // 解决单帧 position delta 噪声, 并捕捉鞘翅飞行的曲率 (拉升/俯冲/转弯)。
            // 当目标切换时重置滤波器状态, 避免旧目标状态污染。
            if (entity == trackedEntity) {
                Vec3d predicted = trackedVel.add(trackedAcc);      // 预测: v + a
                Vec3d residual = relVel.subtract(predicted);       // 残差: 观测 - 预测
                trackedVel = predicted.add(residual.multiply(TRACK_ALPHA));  // 速度修正
                trackedAcc = trackedAcc.add(residual.multiply(TRACK_BETA));  // 加速度修正
            } else {
                trackedVel = relVel;
                trackedAcc = Vec3d.ZERO;
                trackedEntity = entity;
            }

            // === 改进 3: 动态 Horizon ===
            // 从网络延迟自动计算预测时域, 替代用户手动设置的固定 tick 数。
            // horizon = RTT/2 (oneWay) + 25ms (服务端排队偏置), 转为 tick 单位。
            // 若获取不到延迟信息, 回退到用户设置的 predictionTicks。
            double t;
            int pingMs = PlayerUtils.getPing();
            if (pingMs > 0) {
                double horizonMs = pingMs * 0.5 + 25.0;
                t = Math.max(horizonMs / 50.0, 0.5);
            } else {
                t = predictionTicks.get();
            }

            // === 改进 2 (cont): 二阶预测 v·t + ½a·t² ===
            // 纯匀速 (v·t) 在直线运动时足够, 但鞘翅拉升/俯冲/转弯时
            // 加速度项 (½a·t²) 显著提升曲线路径的预测精度。
            box = box.offset(
                trackedVel.x * t + 0.5 * trackedAcc.x * t * t,
                trackedVel.y * t + 0.5 * trackedAcc.y * t * t,
                trackedVel.z * t + 0.5 * trackedAcc.z * t * t
            );
        }

        // X/Z: 最近点 + 30% 中心混合
        double aimX = MathHelper.clamp(eyePos.x, box.minX, box.maxX);
        double aimZ = MathHelper.clamp(eyePos.z, box.minZ, box.maxZ);
        double cx = (box.minX + box.maxX) * 0.5;
        double cz = (box.minZ + box.maxZ) * 0.5;
        aimX += (cx - aimX) * 0.3;
        aimZ += (cz - aimZ) * 0.3;

        // Y: 优选上胸区 (62% 身高), 再做 30% 中心混合
        double preferY = box.minY + (box.maxY - box.minY) * 0.62;
        double aimY = MathHelper.clamp(preferY, box.minY, box.maxY);
        double cy = (box.minY + box.maxY) * 0.5;
        aimY += (cy - aimY) * 0.3;

        return new Vec3d(aimX, aimY, aimZ);
    }

    private boolean itemInHand() {
        if (shouldShieldBreak()) return mc.player.getMainHandStack().getItem() instanceof AxeItem;

        return switch (weapon.get()) {
            case Axe -> mc.player.getMainHandStack().getItem() instanceof AxeItem;
            case Sword -> mc.player.getMainHandStack().getItem() instanceof SwordItem;
            case Mace -> mc.player.getMainHandStack().getItem() instanceof MaceItem;
            case Trident -> mc.player.getMainHandStack().getItem() instanceof TridentItem;
            case All -> mc.player.getMainHandStack().getItem() instanceof AxeItem || mc.player.getMainHandStack().getItem() instanceof SwordItem || mc.player.getMainHandStack().getItem() instanceof MaceItem || mc.player.getMainHandStack().getItem() instanceof TridentItem;
            default -> true;
        };
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

    public enum Weapon {
        Sword,
        Axe,
        Mace,
        Trident,
        All,
        Any
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
