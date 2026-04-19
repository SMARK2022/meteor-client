/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.longs.LongSet;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.entity.EntityRemovedEvent;
import meteordevelopment.meteorclient.events.entity.player.InteractItemEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IBox;
import meteordevelopment.meteorclient.mixininterface.IPlayerInteractEntityC2SPacket;
import meteordevelopment.meteorclient.mixininterface.IRaycastContext;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.Target;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.meteorclient.utils.printer.BlockUtilHelper;
import meteordevelopment.meteorclient.utils.printer.PlacementContext;
import meteordevelopment.meteorclient.utils.printer.PlacementOption;
import meteordevelopment.meteorclient.utils.printer.ResolverRegistry;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class CrystalAura extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSwitch = settings.createGroup("Switch");
    private final SettingGroup sgPlace = settings.createGroup("Place");
    private final SettingGroup sgFacePlace = settings.createGroup("Face Place");
    private final SettingGroup sgBreak = settings.createGroup("Break");
    private final SettingGroup sgPause = settings.createGroup("Pause");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // 通用

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("Range in which to target entities. Targets beyond this range are not considered for damage evaluation.")
        .defaultValue(10)
        .min(0)
        .sliderMax(16)
        .build()
    );

    private final Setting<Boolean> predictMovement = sgGeneral.add(new BoolSetting.Builder()
        .name("predict-movement")
        .description("Adds target velocity to current position to predict next tick location. Improves damage calculation accuracy against moving targets.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> minDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-damage")
        .description("Minimum damage the crystal needs to deal to your target. Positions below this are skipped. Auto-lowers to 1.5 during face-place.")
        .defaultValue(4)
        .min(0)
        .build()
    );

    private final Setting<Double> maxDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-damage")
        .description("Maximum damage crystals can deal to yourself. Positions exceeding this are rejected. Auto-relaxes 15% at low TPS (<18).")
        .defaultValue(8)
        .range(0, 36)
        .sliderMax(36)
        .build()
    );

    private final Setting<Boolean> antiSuicide = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-suicide")
        .description("Will not place and break crystals if they will kill you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> safetyMargin = sgGeneral.add(new DoubleSetting.Builder()
        .name("safety-buffer")
        .description("Safety buffer HP. Refuses to place/break if remaining health after self-damage would fall below this. 2.0=one heart.")
        .defaultValue(2.0)
        .min(0.0)
        .sliderMax(10.0)
        .visible(antiSuicide::get)
        .build()
    );

    private final Setting<Double> minDamageRatio = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-damage-ratio")
        .description("Minimum (target damage / self damage) ratio per detonation. Active when self-damage >= 1.0. Set 0 to disable.")
        .defaultValue(0.8)
        .min(0.0)
        .sliderMax(3.0)
        .build()
    );

    private final Setting<Boolean> ignoreNakeds = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-nakeds")
        .description("Ignore players with no items equipped.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates server-side towards the crystals being hit/placed. Required for GrimAC RotationPlace/RotationBreak.")
        .defaultValue(true)
        .build()
    );

    private final Setting<YawStepMode> yawStepMode = sgGeneral.add(new EnumSetting.Builder<YawStepMode>()
        .name("yaw-steps-mode")
        .description("When to run the yaw steps check. Break=only on break, Both=place and break.")
        .defaultValue(YawStepMode.Break)
        .visible(rotate::get)
        .build()
    );

    private final Setting<Double> yawSteps = sgGeneral.add(new DoubleSetting.Builder()
        .name("yaw-steps")
        .description("Maximum degrees allowed to rotate in one tick. 180=unlimited.")
        .defaultValue(180)
        .range(1, 180)
        .visible(rotate::get)
        .build()
    );

    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Entities to attack.")
        .onlyAttackable()
        .defaultValue(EntityType.PLAYER, EntityType.WARDEN, EntityType.WITHER)
        .build()
    );

    // Switch

    private final Setting<AutoSwitchMode> autoSwitch = sgSwitch.add(new EnumSetting.Builder<AutoSwitchMode>()
        .name("auto-switch")
        .description("Switches to crystals in your hotbar once a target is found. Normal/Silent/None.")
        .defaultValue(AutoSwitchMode.Silent)
        .build()
    );

    private final Setting<Integer> switchDelay = sgSwitch.add(new IntSetting.Builder()
        .name("switch-delay")
        .description("Delay in ticks after switching hotbar slot before breaking crystal.")
        .defaultValue(0)
        .min(0)
        .build()
    );

    private final Setting<Boolean> noGapSwitch = sgSwitch.add(new BoolSetting.Builder()
        .name("no-gap-switch")
        .description("Won't auto switch if you're holding a gapple.")
        .defaultValue(true)
        .visible(() -> autoSwitch.get() == AutoSwitchMode.Normal)
        .build()
    );

    private final Setting<Boolean> noBowSwitch = sgSwitch.add(new BoolSetting.Builder()
        .name("no-bow-switch")
        .description("Won't auto switch if you're holding a bow.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> antiWeakness = sgSwitch.add(new BoolSetting.Builder()
        .name("anti-weakness")
        .description("Switches to tools to break crystals when you have the weakness effect.")
        .defaultValue(true)
        .build()
    );

    // 放置

    private final Setting<Boolean> doPlace = sgPlace.add(new BoolSetting.Builder()
        .name("place")
        .description("If the CA should place crystals.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Integer> placeDelay = sgPlace.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Delay in ticks between crystal placements. 0=every tick.")
        .defaultValue(0)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Double> placeRange = sgPlace.add(new DoubleSetting.Builder()
        .name("place-range")
        .description("Range in which to place crystals.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> placeWallsRange = sgPlace.add(new DoubleSetting.Builder()
        .name("place-walls-range")
        .description("Range in which to place crystals when behind blocks.")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Boolean> strictPlaceLOS = sgPlace.add(new BoolSetting.Builder()
        .name("strict-line-of-sight")
        .description("Performs OUTLINE ray occlusion check on placement face. Recommended for NCP servers.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> placement112 = sgPlace.add(new BoolSetting.Builder()
        .name("1.12-placement")
        .description("Uses 1.12 crystal placement (requires 2 blocks above base).")
        .defaultValue(false)
        .build()
    );

    private final Setting<SupportMode> support = sgPlace.add(new EnumSetting.Builder<SupportMode>()
        .name("support")
        .description("Places a support block in air if no other position have been found. Fast=first target only, Normal=all targets.")
        .defaultValue(SupportMode.Accurate)
        .build()
    );

    private final Setting<Integer> supportDelay = sgPlace.add(new IntSetting.Builder()
        .name("support-delay")
        .description("Delay in ticks after placing support block before placing crystal.")
        .defaultValue(1)
        .min(0)
        .visible(() -> support.get() != SupportMode.Disabled)
        .build()
    );

    private final Setting<Boolean> supportSafePlacement = sgPlace.add(new BoolSetting.Builder()
        .name("support-safe-place")
        .description("Uses printer's NCP/LOS safe placement system for support blocks.")
        .defaultValue(true)
        .visible(() -> support.get() != SupportMode.Disabled)
        .build()
    );

    private final Setting<Double> supportDiscount = sgPlace.add(new DoubleSetting.Builder()
        .name("support-discount")
        .description("Damage discount factor for support placements. Higher=less likely to place obsidian, 0=no discount.")
        .defaultValue(0.3)
        .min(0.0)
        .max(0.9)
        .sliderRange(0.0, 0.9)
        .visible(() -> support.get() != SupportMode.Disabled)
        .build()
    );

    // 贴脸放置

    private final Setting<Boolean> facePlace = sgFacePlace.add(new BoolSetting.Builder()
        .name("face-place")
        .description("Will face-place when target is below a certain health or armor durability threshold.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> facePlaceHealth = sgFacePlace.add(new DoubleSetting.Builder()
        .name("face-place-health")
        .description("The health the target has to be at to start face placing.")
        .defaultValue(8)
        .min(1)
        .sliderMin(1)
        .sliderMax(36)
        .visible(facePlace::get)
        .build()
    );

    private final Setting<Double> facePlaceDurability = sgFacePlace.add(new DoubleSetting.Builder()
        .name("face-place-durability")
        .description("The durability threshold percentage to be able to face-place.")
        .defaultValue(2)
        .min(1)
        .sliderMin(1)
        .sliderMax(100)
        .visible(facePlace::get)
        .build()
    );

    private final Setting<Boolean> facePlaceArmor = sgFacePlace.add(new BoolSetting.Builder()
        .name("face-place-missing-armor")
        .description("Automatically starts face placing when a target misses a piece of armor.")
        .defaultValue(true)
        .visible(facePlace::get)
        .build()
    );

    private final Setting<Keybind> forceFacePlace = sgFacePlace.add(new KeybindSetting.Builder()
        .name("force-face-place")
        .description("Starts face place when this button is pressed.")
        .defaultValue(Keybind.none())
        .build()
    );

    // 破坏

    private final Setting<Boolean> doBreak = sgBreak.add(new BoolSetting.Builder()
        .name("break")
        .description("If the CA should break crystals.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> breakDelay = sgBreak.add(new IntSetting.Builder()
        .name("break-delay")
        .description("Delay in ticks to wait to break a crystal after it's placed.")
        .defaultValue(0)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> smartDelay = sgBreak.add(new BoolSetting.Builder()
        .name("smart-delay")
        .description("Only breaks crystals when the target can receive damage (hurtTime=0).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> targetBreakLimit = sgBreak.add(new IntSetting.Builder()
        .name("burst-limit")
        .description("Maximum consecutive crystal breaks per target before cooldown.")
        .defaultValue(2)
        .min(1)
        .sliderMax(4)
        .build()
    );

    private final Setting<Integer> breakCycleCooldown = sgBreak.add(new IntSetting.Builder()
        .name("burst-cooldown")
        .description("Cooldown ticks after reaching burst limit for the same target.")
        .defaultValue(2)
        .min(1)
        .sliderMax(12)
        .build()
    );

    private final Setting<Boolean> autoRefillCrystals = sgBreak.add(new BoolSetting.Builder()
        .name("auto-replenish")
        .description("Auto-replenishes crystals from inventory when hotbar count is low.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> breakRange = sgBreak.add(new DoubleSetting.Builder()
        .name("break-range")
        .description("Range in which to break crystals.")
        .defaultValue(4.0)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> breakWallsRange = sgBreak.add(new DoubleSetting.Builder()
        .name("break-walls-range")
        .description("Range in which to break crystals when behind blocks.")
        .defaultValue(4.0)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Boolean> onlyBreakOwn = sgBreak.add(new BoolSetting.Builder()
        .name("only-own")
        .description("Only breaks own crystals.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> breakAttempts = sgBreak.add(new IntSetting.Builder()
        .name("break-attempts")
        .description("How many times to hit a crystal before stopping to target it.")
        .defaultValue(1)
        .sliderMin(1)
        .sliderMax(5)
        .build()
    );

    private final Setting<Integer> ticksExisted = sgBreak.add(new IntSetting.Builder()
        .name("ticks-existed")
        .description("Amount of ticks a crystal needs to have lived for it to be attacked.")
        .defaultValue(0)
        .min(0)
        .build()
    );

    private final Setting<Integer> attackFrequency = sgBreak.add(new IntSetting.Builder()
        .name("attack-frequency")
        .description("Maximum hits to do per second.")
        .defaultValue(25)
        .min(1)
        .sliderRange(1, 30)
        .build()
    );

    private final Setting<Boolean> fastBreak = sgBreak.add(new BoolSetting.Builder()
        .name("fast-break")
        .description("Ignores break delay and tries to break the crystal as soon as it's spawned.")
        .defaultValue(true)
        .build()
    );

    // 暂停

    public final Setting<PauseMode> pauseOnUse = sgPause.add(new EnumSetting.Builder<PauseMode>()
        .name("pause-on-use")
        .description("Which processes should be paused while using an item.")
        .defaultValue(PauseMode.Place)
        .build()
    );

    public final Setting<PauseMode> pauseOnMine = sgPause.add(new EnumSetting.Builder<PauseMode>()
        .name("pause-on-mine")
        .description("Which processes should be paused while mining a block.")
        .defaultValue(PauseMode.None)
        .build()
    );

    private final Setting<Boolean> pauseOnLag = sgPause.add(new BoolSetting.Builder()
        .name("pause-on-lag")
        .description("Whether to pause if the server is not responding.")
        .defaultValue(true)
        .build()
    );

    public final Setting<List<Module>> pauseModules = sgPause.add(new ModuleListSetting.Builder()
        .name("pause-modules")
        .description("Pauses crystal aura when any selected module is active.")
        .defaultValue(BedAura.class)
        .build()
    );

    public final Setting<Double> pauseHealth = sgPause.add(new DoubleSetting.Builder()
        .name("pause-health")
        .description("Pauses all processes when your total health falls below this value.")
        .defaultValue(5)
        .range(0,36)
        .sliderRange(0,36)
        .build()
    );

    // 渲染

    public final Setting<SwingMode> swingMode = sgRender.add(new EnumSetting.Builder<SwingMode>()
        .name("swing-mode")
        .description("Hand swing mode when placing crystals. Both/Client/Server/None.")
        .defaultValue(SwingMode.Both)
        .build()
    );

    private final Setting<RenderMode> renderMode = sgRender.add(new EnumSetting.Builder<RenderMode>()
        .name("render-mode")
        .description("Visual feedback mode for crystal positions. Normal/Smooth/Fading/Gradient/None.")
        .defaultValue(RenderMode.Normal)
        .build()
    );

    private final Setting<Boolean> renderPlace = sgRender.add(new BoolSetting.Builder()
        .name("render-place")
        .description("Renders overlay on crystal placement positions.")
        .defaultValue(true)
        .visible(() -> renderMode.get() == RenderMode.Normal)
        .build()
    );

    private final Setting<Integer> placeRenderTime = sgRender.add(new IntSetting.Builder()
        .name("place-render-time")
        .description("Duration in ticks for placement overlay.")
        .defaultValue(10)
        .min(0)
        .sliderMax(20)
        .visible(() -> renderMode.get() == RenderMode.Normal && renderPlace.get())
        .build()
    );

    private final Setting<Boolean> renderBreak = sgRender.add(new BoolSetting.Builder()
        .name("render-break")
        .description("Renders overlay on crystal break positions.")
        .defaultValue(false)
        .visible(() -> renderMode.get() == RenderMode.Normal)
        .build()
    );

    private final Setting<Integer> breakRenderTime = sgRender.add(new IntSetting.Builder()
        .name("break-render-time")
        .description("Duration in ticks for break overlay.")
        .defaultValue(13)
        .min(0)
        .sliderMax(20)
        .visible(() -> renderMode.get() == RenderMode.Normal && renderBreak.get())
        .build()
    );

    private final Setting<Integer> smoothness = sgRender.add(new IntSetting.Builder()
        .name("smoothness")
        .description("Transition smoothness for smooth render mode.")
        .defaultValue(10)
        .min(0)
        .sliderMax(20)
        .visible(() -> renderMode.get() == RenderMode.Smooth)
        .build()
    );

    private final Setting<Double> height = sgRender.add(new DoubleSetting.Builder()
        .name("gradient-height")
        .description("Overlay height for gradient render mode.")
        .defaultValue(0.7)
        .min(0)
        .sliderMax(1)
        .visible(() -> renderMode.get() == RenderMode.Gradient)
        .build()
    );

    private final Setting<Integer> renderTime = sgRender.add(new IntSetting.Builder()
        .name("render-time")
        .description("Duration in ticks for smooth/fading render modes.")
        .defaultValue(10)
        .min(0)
        .sliderMax(20)
        .visible(() -> renderMode.get() == RenderMode.Smooth || renderMode.get() == RenderMode.Fading)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered. Both/Sides/Lines.")
        .defaultValue(ShapeMode.Both)
        .visible(() -> renderMode.get() != RenderMode.None)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The color of the sides of the overlay.")
        .defaultValue(new SettingColor(255, 255, 255, 45))
        .visible(() -> shapeMode.get().sides() && renderMode.get() != RenderMode.None)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The color of the lines of the overlay.")
        .defaultValue(new SettingColor(255, 255, 255))
        .visible(() -> shapeMode.get().lines() && renderMode.get() != RenderMode.None)
        .build()
    );

    private final Setting<SettingColor> supportSideColor = sgRender.add(new ColorSetting.Builder()
        .name("support-side-color")
        .description("The side color for support candidate positions.")
        .defaultValue(new SettingColor(255, 170, 0, 45))
        .visible(() -> shapeMode.get().sides() && renderMode.get() != RenderMode.None && support.get() != SupportMode.Disabled)
        .build()
    );

    private final Setting<SettingColor> supportLineColor = sgRender.add(new ColorSetting.Builder()
        .name("support-line-color")
        .description("The line color for support candidate positions.")
        .defaultValue(new SettingColor(255, 170, 0))
        .visible(() -> shapeMode.get().lines() && renderMode.get() != RenderMode.None && support.get() != SupportMode.Disabled)
        .build()
    );

    private final Setting<Boolean> renderDamageText = sgRender.add(new BoolSetting.Builder()
        .name("show-damage")
        .description("Shows estimated damage numbers above the overlay.")
        .defaultValue(true)
        .visible(() -> renderMode.get() != RenderMode.None)
        .build()
    );

    private final Setting<SettingColor> damageColor = sgRender.add(new ColorSetting.Builder()
        .name("damage-text-color")
        .description("The color of damage number text.")
        .defaultValue(new SettingColor(255, 255, 255))
        .visible(() -> renderMode.get() != RenderMode.None && renderDamageText.get())
        .build()
    );

    private final Setting<Double> damageTextScale = sgRender.add(new DoubleSetting.Builder()
        .name("damage-text-scale")
        .description("Scale of damage number text.")
        .defaultValue(1.25)
        .min(1)
        .sliderMax(4)
        .visible(() -> renderMode.get() != RenderMode.None && renderDamageText.get())
        .build()
    );

    // 字段

    private Item mainItem, offItem;

    private int breakTimer, placeTimer, switchTimer, ticksPassed;
    private final List<LivingEntity> targets = new ArrayList<>();

    private final Vec3d vec3d = new Vec3d(0, 0, 0);
    private final Vec3d playerEyePos = new Vec3d(0, 0, 0);
    private final Vector3d vec3 = new Vector3d();
    private final BlockPos.Mutable blockPos = new BlockPos.Mutable();
    private final Box box = new Box(0, 0, 0, 0, 0, 0);

    private RaycastContext raycastContext;

    private final IntSet placedCrystals = new IntOpenHashSet();
    private boolean placing;
    private int placingTimer;
    public int kaTimer;
    private final BlockPos.Mutable placingCrystalBlockPos = new BlockPos.Mutable();

    private final IntSet removed = new IntOpenHashSet();
    private final Int2IntMap attemptedBreaks = new Int2IntOpenHashMap();
    private final Int2IntMap waitingToExplode = new Int2IntOpenHashMap();
    private int attacks;

    private double serverYaw;

    private LivingEntity bestTarget;
    private double bestTargetDamage;
    private int bestTargetTimer;

    private boolean didRotateThisTick;
    private boolean isLastRotationPos;
    private final Vec3d lastRotationPos = new Vec3d(0, 0 ,0);
    private double lastYaw, lastPitch;
    private int lastRotationTimer;

    private int placeRenderTimer, breakRenderTimer;
    private final BlockPos.Mutable placeRenderPos = new BlockPos.Mutable();
    private final BlockPos.Mutable breakRenderPos = new BlockPos.Mutable();
    private Box renderBoxOne, renderBoxTwo;

    private double renderDamage;
    private boolean renderIsSupport; // 当前渲染位置是否为 support 候选（用于颜色区分）

    // 水晶生命周期追踪（替代实体 mixin，所有状态集中管理）
    private boolean attackedThisTick;                                       // 本 tick 已攻击标志，防止重复攻击
    private final Int2LongMap crystalSpawnTimes = new Int2LongOpenHashMap(); // entityId → 生成时间戚，用于新生判定
    private final IntSet handledCrystals = new IntOpenHashSet();             // 已处理的水晶 id，避免重复处理
    // 双阶段 per-target break 节流
    // targetBreakCount   : 当前周期内已发送的 break 次数；达到 targetBreakLimit 后进入冷却并清零
    // targetBreakCooldown: 冷却倒计时（tick）；>0 时跳过对该目标的 break 评估，覆盖服务端 10-tick 无敌帧
    private final Int2IntMap targetBreakCount    = new Int2IntOpenHashMap();
    private final Int2IntMap targetBreakCooldown = new Int2IntOpenHashMap();
    // 自动补充水晶：防止每 tick 频繁执行背包操作的冷却计数器
    private int crystalRefillCooldown = 0;

    // 有效基座位置缓存 —— BlockUpdateEvent 时增量失效，过滤 ~90% 的无效位置
    private final LongSet validBasePosCache = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private boolean baseCacheDirty = true;
    private int baseCacheScanRange;
    private int baseCacheCenterX, baseCacheCenterY, baseCacheCenterZ;

    // 异步规划器 —— 将完整扫描委派给后台线程 (CrystalPlanner)
    private final CrystalPlanner planner = new CrystalPlanner();

    public CrystalAura() {
        super(Categories.Combat, "crystal-aura", "Automatically places and attacks end crystals.");
    }

    @Override
    public void onActivate() {
        breakTimer = 0;
        placeTimer = 0;
        ticksPassed = 0;

        raycastContext = new RaycastContext(new Vec3d(0, 0, 0), new Vec3d(0, 0, 0), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player);

        placing = false;
        placingTimer = 0;
        kaTimer = 0;

        attacks = 0;

        serverYaw = mc.player.getYaw();

        attackedThisTick = false;
        crystalSpawnTimes.clear();
        handledCrystals.clear();
        targetBreakCount.clear();
        targetBreakCooldown.clear();
        baseCacheDirty = true;
        planner.reset();

        bestTargetDamage = 0;
        bestTargetTimer = 0;

        lastRotationTimer = getLastRotationStopDelay();

        placeRenderTimer = 0;
        breakRenderTimer = 0;
    }

    @Override
    public void onDeactivate() {
        targets.clear();

        placedCrystals.clear();

        attemptedBreaks.clear();
        waitingToExplode.clear();

        removed.clear();
        crystalSpawnTimes.clear();
        handledCrystals.clear();
        targetBreakCount.clear();
        targetBreakCooldown.clear();
        validBasePosCache.clear();

        bestTarget = null;
    }

    private int getLastRotationStopDelay() {
        return Math.max(10, placeDelay.get() / 2 + breakDelay.get() / 2 + 10);
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onPreTick(TickEvent.Pre event) {
        // Update last rotation
        didRotateThisTick = false;
        // F4: attackedThisTick 延迟重置到 findTargets 之后
        // 使得 EntityAddedEvent → fastBreak 设置的标志在 doBreak+doPlace 间仍生效
        lastRotationTimer++;

        // Decrement placing timer
        if (placing) {
            if (placingTimer > 0) placingTimer--;
            else placing = false;
        }

        if (kaTimer > 0) kaTimer--;

        if (ticksPassed < 20) ticksPassed++;
        else {
            ticksPassed = 0;
            attacks = 0;
        }

        // Decrement best target timer
        if (bestTargetTimer > 0) bestTargetTimer--;
        bestTargetDamage = 0;

        // Decrement break, place and switch timers
        if (breakTimer > 0) breakTimer--;
        if (placeTimer > 0) placeTimer--;
        if (switchTimer > 0) switchTimer--;

        // 无敌帧冷却：每 tick 递减，归零时结束冷却（开始新周期）
        for (IntIterator it = targetBreakCooldown.keySet().iterator(); it.hasNext();) {
            int id = it.nextInt();
            int ticks = targetBreakCooldown.get(id);
            if (ticks <= 1) it.remove();
            else targetBreakCooldown.put(id, ticks - 1);
        }

        // 自动补充快捷栏水晶
        if (autoRefillCrystals.get()) {
            if (crystalRefillCooldown > 0) crystalRefillCooldown--;
            else tryCrystalRefill();
        }

        // Decrement render timers
        if (placeRenderTimer > 0) placeRenderTimer--;
        if (breakRenderTimer > 0) breakRenderTimer--;

        mainItem = mc.player.getMainHandStack().getItem();
        offItem = mc.player.getOffHandStack().getItem();

        // Update waiting to explode crystals and mark them as existing if reached threshold
        for (IntIterator it = waitingToExplode.keySet().iterator(); it.hasNext();) {
            int id = it.nextInt();
            int ticks = waitingToExplode.get(id);

            if (ticks > 3) {
                it.remove();
                removed.remove(id);
                handledCrystals.remove(id);
            }
            else {
                waitingToExplode.put(id, ticks + 1);
            }
        }

        // Set player eye pos
        ((IVec3d) playerEyePos).meteor$set(mc.player.getPos().x, mc.player.getPos().y + mc.player.getEyeHeight(mc.player.getPose()), mc.player.getPos().z);

        // 时间线约束：Pre 只做“读世界 + 提交规划”。
        // Why: Grim 在 Flying 后回溯校验交互包，若在 Pre 直接发包，结果与当 tick 最终位置可能错位。
        // 因此将副作用放到 Post，Pre 保持纯规划，避免 stale 结果在旋转排队后落到错误时刻。
        findTargets();

        if (!targets.isEmpty()) captureAndSubmitAsyncScan();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onPostTick(TickEvent.Post event) {
        // Post 执行面：保证交互包尽量贴近本 tick 的最终位姿。
        // 顺序固定为 break -> place，且受 attackedThisTick 互斥门控，规避同 tick attack+place 的包序风险。
        if (targets.isEmpty()) {
            if (placeRenderTimer > 0) renderDamage = 0;
            attackedThisTick = false;
            return;
        }

        if (!didRotateThisTick) doBreak();
        if (!didRotateThisTick) doPlace();

        // 渲染数字与本 tick 的世界状态同步：
        // planner 的候选伤害是“提交时快照值”，这里在主线程用当前位置重算一次，避免文本延迟。
        refreshRenderDamageLive();

        // 在本 tick 所有动作结束后再清理攻击标记，确保 fastBreak 标记在同 tick 内持续有效。
        attackedThisTick = false;
    }

    @EventHandler(priority = EventPriority.LOWEST - 666)
    private void onPreTickLast(TickEvent.Pre event) {
        // Rotate to last rotation
        if (rotate.get() && lastRotationTimer < getLastRotationStopDelay() && !didRotateThisTick) {
            Rotations.rotate(isLastRotationPos ? Rotations.getYaw(lastRotationPos) : lastYaw, isLastRotationPos ? Rotations.getPitch(lastRotationPos) : lastPitch, -100, null);
        }
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (!(event.entity instanceof EndCrystalEntity)) return;

        // Track spawn time for newborn window
        crystalSpawnTimes.put(event.entity.getId(), System.currentTimeMillis());

        boolean isOwnCrystal = placing && event.entity.getBlockPos().equals(placingCrystalBlockPos);

        if (isOwnCrystal) {
            placing = false;
            placingTimer = 0;
            placedCrystals.add(event.entity.getId());
        }

        // Spawn window: instant break with fresh damage evaluation
        // 自己放置的水晶跳过年龄检查；其他水晶走标准验证
        // Respects switchTimer for anti-cheat packet ordering
        if (fastBreak.get() && !attackedThisTick && switchTimer <= 0 && attacks < attackFrequency.get()) {
            float damage = getBreakDamage(event.entity, !isOwnCrystal);
            if (damage > 0) doBreak(event.entity);
        }
    }

    @EventHandler
    private void onEntityRemoved(EntityRemovedEvent event) {
        if (event.entity instanceof EndCrystalEntity) {
            int id = event.entity.getId();
            placedCrystals.remove(id);
            removed.remove(id);
            waitingToExplode.remove(id);
            crystalSpawnTimes.remove(id);
            handledCrystals.remove(id);
        }
    }

    private void setRotation(boolean isPos, Vec3d pos, double yaw, double pitch) {
        didRotateThisTick = true;
        isLastRotationPos = isPos;

        if (isPos) ((IVec3d) lastRotationPos).meteor$set(pos.x, pos.y, pos.z);
        else {
            lastYaw = yaw;
            lastPitch = pitch;
        }

        lastRotationTimer = 0;
    }

    // 破坏流程

    private void doBreak() {
        if (!doBreak.get() || breakTimer > 0 || switchTimer > 0 || attackedThisTick || attacks >= attackFrequency.get()) return;
        if (shouldPause(PauseMode.Break)) return;

        float bestScore = 0;
        Entity crystal = null;

        // Find best crystal — own + newborn bonuses (mio-style utility)
        long now = System.currentTimeMillis();
        for (Entity entity : mc.world.getEntities()) {
            float damage = getBreakDamage(entity, true);
            if (damage <= 0) continue;

            float score = damage;
            if (placedCrystals.contains(entity.getId())) score += 0.5f;
            // 新生窗口 (150ms)：给予新生成水晶巨大的优先级加分
            if (now - crystalSpawnTimes.getOrDefault(entity.getId(), 0L) <= 150) score += 2.0f;
            if (score > bestScore) {
                bestScore = score;
                crystal = entity;
            }
        }

        if (crystal != null) doBreak(crystal);
    }

    private float getBreakDamage(Entity entity, boolean checkCrystalAge) {
        if (!(entity instanceof EndCrystalEntity)) return 0;

        // Check only break own
        if (onlyBreakOwn.get() && !placedCrystals.contains(entity.getId())) return 0;

        // Check if it should already be removed
        if (removed.contains(entity.getId())) return 0;

        // Check attempted breaks
        if (attemptedBreaks.get(entity.getId()) >= breakAttempts.get()) return 0;

        // Check crystal age
        if (checkCrystalAge && entity.age < ticksExisted.get()) return 0;

        // Check range
        if (isOutOfRange(entity.getPos(), entity.getBlockPos(), false)) return 0;

        // Check damage to self and anti suicide
        // Low TPS safety margin: 15% reduction on maxDamage threshold
        blockPos.set(entity.getBlockPos()).move(0, -1, 0);
        float selfDamage = DamageUtils.crystalDamage(mc.player, entity.getPos(), false, blockPos);
        float effectiveMaxDmg = maxDamage.get().floatValue();
        if (TickRate.INSTANCE.getTickRate() < 18) effectiveMaxDmg *= 0.85f;
        if (selfDamage > effectiveMaxDmg || (antiSuicide.get() && selfDamage >= (EntityUtils.getTotalHealth(mc.player) - safetyMargin.get()))) return 0;

        // Check damage to targets and face place
        float damage = getDamageToTargets(entity.getPos(), blockPos, true, false);
        boolean shouldFacePlace = shouldFacePlace();
        double minimumDamage = shouldFacePlace ? Math.min(minDamage.get(), 1.5d) : minDamage.get();

        if (damage < minimumDamage) return 0f;

        // Damage ratio check — reject if self takes too much relative to target
        if (minDamageRatio.get() > 0 && selfDamage >= 1.0f && damage / selfDamage < minDamageRatio.get()) return 0f;

        return damage;
    }

    private void doBreak(Entity crystal) {
        // Anti weakness
        if (antiWeakness.get()) {
            StatusEffectInstance weakness = mc.player.getStatusEffect(StatusEffects.WEAKNESS);
            StatusEffectInstance strength = mc.player.getStatusEffect(StatusEffects.STRENGTH);

            // Check for strength
            if (weakness != null && (strength == null || strength.getAmplifier() <= weakness.getAmplifier())) {
                // Check if the item in your hand is already valid
                if (!isValidWeaknessItem(mc.player.getMainHandStack())) {
                    // Find valid item to break with
                    if (!InvUtils.swap(InvUtils.findInHotbar(this::isValidWeaknessItem).slot(), false)) return;

                    switchTimer = 1;
                    return;
                }
            }
        }

        // Rotate and attack
        boolean attacked = true;

        if (rotate.get()) {
            double yaw = Rotations.getYaw(crystal);
            double pitch = Rotations.getPitch(crystal, Target.Feet);

            if (doYawSteps(yaw, pitch)) {
                setRotation(true, crystal.getPos(), 0, 0);
                Rotations.rotateToward(crystal.getPos(), 120, () -> attackCrystal(crystal));

                breakTimer = breakDelay.get();
            }
            else {
                attacked = false;
            }
        }
        else {
            attackCrystal(crystal);
            breakTimer = breakDelay.get();
        }

        if (attacked) {
            // Update state & mark handled for render feedback
            attackedThisTick = true;
            handledCrystals.add(crystal.getId());
            removed.add(crystal.getId());
            attemptedBreaks.put(crystal.getId(), attemptedBreaks.get(crystal.getId()) + 1);
            waitingToExplode.put(crystal.getId(), 0);

            // 双阶段 per-target break 节流：break 成功后递增计数
            // 达到 targetBreakLimit 上限 → 进入冷却（覆盖服务端 10-tick 无敌帧），下一周期重新计数
            LivingEntity victim = findBestVictimFor(crystal);
            if (victim != null) {
                int count = targetBreakCount.getOrDefault(victim.getId(), 0) + 1;
                if (count >= targetBreakLimit.get()) {
                    // 到达连发上限 → 进入冷却，tick 数由 breakCycleCooldown 设置决定
                    targetBreakCooldown.put(victim.getId(), (int) breakCycleCooldown.get());
                    targetBreakCount.remove(victim.getId());
                } else {
                    targetBreakCount.put(victim.getId(), count);
                }
            }

            // Break render
            breakRenderPos.set(crystal.getBlockPos().down());
            breakRenderTimer = breakRenderTime.get();
        }
    }

    /**
     * 自动补充快捷栏水晶：当快捷栏水晶总数低于阈值时，
     * 从背包（主物品栏 9-35）找最大水晶堆，移入快捷栏。
     * 优先填入已有水晶的快捷栏槽位（合堆），次选空槽位。
     */
    private void tryCrystalRefill() {
        // MultiActionsC (experimental): CLICK_WINDOW + 疾跑会被部分严格服务器检测
        // 疾跑中跳过补充，等短暂停顿时再执行（精度不重要，只要安全）
        if (mc.player.isSprinting()) return;

        // 统计快捷栏（0-8）水晶总数
        int hotbarCount = 0;
        for (int i = SlotUtils.HOTBAR_START; i <= SlotUtils.HOTBAR_END; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == Items.END_CRYSTAL) hotbarCount += stack.getCount();
        }
        if (hotbarCount >= 8) return;

        // 背包中最大水晶堆（槽位 9-35）
        int invSlot = -1, invMax = 0;
        for (int i = SlotUtils.MAIN_START; i <= SlotUtils.MAIN_END; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == Items.END_CRYSTAL && stack.getCount() > invMax) {
                invSlot = i;
                invMax = stack.getCount();
            }
        }
        if (invSlot == -1) return;

        // 目标快捷栏槽位：优先选已有水晶的槽位（合堆），次选空槽，末选当前槽
        int targetSlot = -1;
        for (int i = SlotUtils.HOTBAR_START; i <= SlotUtils.HOTBAR_END; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.END_CRYSTAL) { targetSlot = i; break; }
        }
        if (targetSlot == -1) {
            for (int i = SlotUtils.HOTBAR_START; i <= SlotUtils.HOTBAR_END; i++) {
                if (mc.player.getInventory().getStack(i).isEmpty()) { targetSlot = i; break; }
            }
        }
        if (targetSlot == -1) targetSlot = mc.player.getInventory().getSelectedSlot();

        InvUtils.move().from(invSlot).to(targetSlot);
        crystalRefillCooldown = 5; // 5 tick 内不重复触发，等服务端同步槽位
    }

    /**
     * 返回给定水晶爆炸时对当前 targets 中伤害最高的目标实体。
     * 用于在 break 成功后写入 RTT 盲窗抑制：在服务端 hurtTime 确认到达之前，
     * 压低对该目标的后续 break 兑现率，防止无效连打。
     */
    private LivingEntity findBestVictimFor(Entity crystal) {
        float bestDmg = 0;
        LivingEntity best = null;
        Vec3d pos = crystal.getPos();
        blockPos.set(crystal.getBlockPos()).move(0, -1, 0);
        for (LivingEntity target : targets) {
            float dmg = DamageUtils.crystalDamage(target, pos, predictMovement.get(), blockPos);
            if (dmg > bestDmg) { bestDmg = dmg; best = target; }
        }
        return best;
    }

    private boolean isValidWeaknessItem(ItemStack itemStack) {
        return DamageUtils.getAttackDamage(mc.player, mc.player, itemStack) > 0;
    }

    private void attackCrystal(Entity entity) {
        Hand hand = InvUtils.findInHotbar(Items.END_CRYSTAL).getHand();
        if (hand == null) hand = Hand.MAIN_HAND;

        // MC 1.9+ 原版顺序: ANIMATION → ATTACK (GrimAC PacketOrderB 检查此顺序)
        if (swingMode.get().packet()) mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(hand));
        mc.player.networkHandler.sendPacket(PlayerInteractEntityC2SPacket.attack(entity, mc.player.isSneaking()));
        if (swingMode.get().client()) mc.player.swingHand(hand);

        attacks++;
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (event.packet instanceof UpdateSelectedSlotC2SPacket) {
            switchTimer = switchDelay.get();
        }
        // 拦截手动攻击包: 标记 attackedThisTick 防止同 tick 内 CA 再发 place 包
        // (PacketOrderJ: 同 tick attack+place = FLAG)
        if (event.packet instanceof IPlayerInteractEntityC2SPacket pkt
            && pkt.meteor$getType() == PlayerInteractEntityC2SPacket.InteractType.ATTACK) {
            attackedThisTick = true;
        }
    }

    /**
     * 玩家手动右键使用物品时，废弃旧的异步搜索结果和方案缓存。
     * mio 宿主层协同：避免用户操作与 AC 过期结果冲突。
     */
    @EventHandler
    private void onInteractItem(InteractItemEvent event) {
        planner.consumeResult(); // 废弃旧结果
    }

    /**
     * 智能 support 仲裁 —— 决定是否优先选择 support 方案而非 direct 方案。
     * support 伤害先打折（用户可配置，默认 30%），折后伤害 > direct 才选 support。
     * 致命 support（能击杀目标）始终优先（不打折）。
     */
    private boolean shouldPreferSupport(double supportDmg, double directDmg) {
        if (directDmg <= 0) return supportDmg > 0;

        // 致命 support 始终优先（能一击杀则不计代价补块）
        LivingEntity nearest = getNearestTarget();
        if (nearest != null && supportDmg >= EntityUtils.getTotalHealth(nearest)) return true;

        // 折扣后与 direct 比较
        double discounted = supportDmg * (1.0 - supportDiscount.get());
        return discounted > directDmg;
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (mc.player == null) return;
        BlockPos pos = event.pos;

        // Incremental blast-resistance snapshot update for async planner
        planner.updateBlock(pos.getX(), pos.getY(), pos.getZ(),
            event.newState.getBlock().getBlastResistance());

        if (baseCacheDirty) return;
        int range = baseCacheScanRange;

        // Skip positions outside cache coverage
        if (Math.abs(pos.getX() - baseCacheCenterX) > range + 1
            || Math.abs(pos.getY() - baseCacheCenterY) > range + 1
            || Math.abs(pos.getZ() - baseCacheCenterZ) > range + 1) return;

        // Incremental update: changed pos as potential air-above, and bases below
        boolean se = support.get() != SupportMode.Disabled;
        updateCacheAt(pos.getX(), pos.getY(), pos.getZ(), se);
        updateCacheAt(pos.getX(), pos.getY() - 1, pos.getZ(), se);
        if (placement112.get()) updateCacheAt(pos.getX(), pos.getY() - 2, pos.getZ(), se);
    }

    private void updateCacheAt(int x, int y, int z, boolean supportEnabled) {
        long packed = BlockPos.asLong(x, y, z);
        if (isValidBase(x, y, z, supportEnabled)) validBasePosCache.add(packed);
        else validBasePosCache.remove(packed);
    }

    // 水晶状态查询（供渲染器 mixin 调用，无需实体 mixin）

    public boolean shouldHideCrystal(int entityId) {
        return handledCrystals.contains(entityId)
            && System.currentTimeMillis() - crystalSpawnTimes.getOrDefault(entityId, 0L) <= 150;
    }

    public boolean isNewbornCrystal(int entityId) {
        return System.currentTimeMillis() - crystalSpawnTimes.getOrDefault(entityId, 0L) <= 150;
    }

    // 有效基座缓存 —— 移动/范围变化时全量重建，方块变化时增量更新

    private boolean isValidBase(int x, int y, int z, boolean supportEnabled) {
        blockPos.set(x, y, z);
        net.minecraft.block.BlockState state = mc.world.getBlockState(blockPos);
        boolean hasBlock = state.isOf(Blocks.BEDROCK) || state.isOf(Blocks.OBSIDIAN);
        if (!hasBlock && !(supportEnabled && state.isReplaceable())) return false;

        // Support 候选必须有至少一个非空气邻居，否则无法 attach 方块（空中放置保护）
        if (!hasBlock && supportEnabled) {
            if (BlockUtils.getPlaceSide(blockPos) == null) return false;
        }

        blockPos.set(x, y + 1, z);
        if (!mc.world.getBlockState(blockPos).isAir()) return false;
        if (placement112.get()) {
            blockPos.set(x, y + 2, z);
            if (!mc.world.getBlockState(blockPos).isAir()) return false;
        }
        return true;
    }

    private void refreshBaseCacheIfNeeded() {
        int range = (int) Math.ceil(placeRange.get());
        BlockPos pp = mc.player.getBlockPos();

        // Full rebuild when: dirty, range changed, or player moved >2 blocks from scan center
        if (!baseCacheDirty && range == baseCacheScanRange
            && Math.abs(pp.getX() - baseCacheCenterX) <= 2
            && Math.abs(pp.getY() - baseCacheCenterY) <= 2
            && Math.abs(pp.getZ() - baseCacheCenterZ) <= 2) return;

        validBasePosCache.clear();
        boolean se = support.get() != SupportMode.Disabled;

        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -range; dy <= range; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    int x = pp.getX() + dx, y = pp.getY() + dy, z = pp.getZ() + dz;
                    if (isValidBase(x, y, z, se)) validBasePosCache.add(BlockPos.asLong(x, y, z));
                }
            }
        }
        baseCacheDirty = false;
        baseCacheScanRange = range;
        baseCacheCenterX = pp.getX();
        baseCacheCenterY = pp.getY();
        baseCacheCenterZ = pp.getZ();
    }

    // === Async Planner — delegates to CrystalPlanner ===

    private void captureAndSubmitAsyncScan() {
        // Always keep snapshot current (main thread only — no contention with background thread's copy)
        BlockPos pp = mc.player.getBlockPos();
        if (planner.needsRebuild(pp.getX(), pp.getY(), pp.getZ())) {
            planner.rebuildSnapshot(mc.world, pp.getX(), pp.getY(), pp.getZ());
        }

        // 2. Snapshot targets
        boolean predict = predictMovement.get();
        CrystalPlanner.TargetSnap[] tSnaps = new CrystalPlanner.TargetSnap[targets.size()];
        for (int i = 0; i < targets.size(); i++) tSnaps[i] = CrystalPlanner.snapshotTarget(targets.get(i), predict);
        CrystalPlanner.TargetSnap selfSnap = CrystalPlanner.snapshotTarget(mc.player, false);

        // 3. Collect valid bases + pre-filter (range, entity overlap — all on main thread; LOS deferred to consumption)
        List<CrystalPlanner.Candidate> candidates = new ArrayList<>();
        refreshBaseCacheIfNeeded();
        for (long packed : validBasePosCache) {
            int bx = BlockPos.unpackLongX(packed), by = BlockPos.unpackLongY(packed), bz = BlockPos.unpackLongZ(packed);
            net.minecraft.block.BlockState state = mc.world.getBlockState(blockPos.set(bx, by, bz));
            boolean hasBlock = state.isOf(Blocks.BEDROCK) || state.isOf(Blocks.OBSIDIAN);

            ((IVec3d) vec3d).meteor$set(bx + 0.5, by + 1, bz + 0.5);
            blockPos.set(bx, by + 1, bz);
            if (isOutOfRange(vec3d, blockPos, true)) continue;

            double cx = bx, cy = by + 1.0, cz = bz;
            ((IBox) box).meteor$set(cx, cy, cz, cx + 1, cy + (placement112.get() ? 1 : 2), cz + 1);
            if (intersectsWithEntities(box)) continue;

            candidates.add(new CrystalPlanner.Candidate(bx, by, bz, hasBlock));
        }

        if (candidates.isEmpty()) return;

        // 4. Submit to planner background thread
        planner.submitScan(
            candidates.toArray(new CrystalPlanner.Candidate[0]),
            tSnaps, selfSnap,
            new CrystalPlanner.ScanSettings(
                maxDamage.get(), antiSuicide.get(), safetyMargin.get(), minDamage.get(), smartDelay.get(),
                shouldFacePlace(), support.get() == SupportMode.Fast, TickRate.INSTANCE.getTickRate(),
                mc.world.getDifficulty(), minDamageRatio.get())
        );
    }

    // 放置流程

    /**
     * 响应式放置主流程（Post 阶段执行）。
     *
     * Why:
     * 1. 主线程不再做全量同步 fallback 扫描，避免重复算法和维护分叉。
     * 2. 仅消费 planner 的最新结果，并用“年龄 + 世界版本漂移”做门控，阻断过期 proposal。
     * 3. 旋转回调加入 worldVersion 捕获，防止 rotate 排队期间环境剧变导致旧包落地。
     *
     * Timeline:
     * Pre 提交扫描 -> 后台 10ms 窗口合并计算 -> Post 消费结果并执行发包。
     */
    private void doPlace() {
        if (!doPlace.get() || placeTimer > 0) return;
        if (attackedThisTick) return;
        if (shouldPause(PauseMode.Place)) return;
        if (placing && placingTimer > 0) return;
        if (!InvUtils.testInHotbar(Items.END_CRYSTAL)) return;
        boolean supportAvailable = support.get() != SupportMode.Disabled && InvUtils.testInHotbar(Items.OBSIDIAN);
        if (autoSwitch.get() != AutoSwitchMode.None) {
            if (noGapSwitch.get() && autoSwitch.get() == AutoSwitchMode.Normal && offItem != Items.END_CRYSTAL) {
                if (mainItem == Items.ENCHANTED_GOLDEN_APPLE
                || offItem == Items.ENCHANTED_GOLDEN_APPLE
                || mainItem == Items.GOLDEN_APPLE
                || offItem == Items.GOLDEN_APPLE) return;
            }
            if (noBowSwitch.get() && (mainItem == Items.BOW || offItem == Items.BOW)) return;
        } else if (mainItem != Items.END_CRYSTAL && offItem != Items.END_CRYSTAL) return;

        for (Entity entity : mc.world.getEntities()) {
            if (getBreakDamage(entity, false) > 0) return;
        }

        CrystalPlanner.ResultSet asyncRes = planner.consumeResult();
        if (asyncRes == null || !planner.isResultFresh(asyncRes, 35, 6)) return;

        CrystalPlanner.PlaceResult directRes = asyncRes.direct();
        CrystalPlanner.PlaceResult supportRes = asyncRes.support();

        CrystalPlanner.PlaceResult chosen = null;
        boolean isSupportChoice = false;
        if (directRes != null && supportRes != null && supportAvailable) {
            isSupportChoice = shouldPreferSupport(supportRes.damage(), directRes.damage());
            chosen = isSupportChoice ? supportRes : directRes;
        } else if (directRes != null) {
            chosen = directRes;
        } else if (supportAvailable) {
            chosen = supportRes;
            isSupportChoice = true;
        }

        if (chosen == null) return;

        BlockPos pos = new BlockPos(chosen.x(), chosen.y(), chosen.z());
        final double finalDamage = chosen.damage();
        BlockHitResult result = resolveCrystalHit(pos);
        if (result == null) return;

        SupportPlan supportPlan = isSupportChoice ? resolveSupportHit(pos) : null;
        if (isSupportChoice && supportPlan == null) return;

        updateRenderCandidate(pos, finalDamage, isSupportChoice);

        BlockPos supportBlock = isSupportChoice ? pos : null;
        if (supportPlan != null) {
            ((IVec3d) vec3d).meteor$set(supportPlan.hitVec().x, supportPlan.hitVec().y, supportPlan.hitVec().z);
        } else {
            ((IVec3d) vec3d).meteor$set(
                result.getBlockPos().getX() + 0.5 + result.getSide().getVector().getX() * 0.5,
                result.getBlockPos().getY() + 0.5 + result.getSide().getVector().getY() * 0.5,
                result.getBlockPos().getZ() + 0.5 + result.getSide().getVector().getZ() * 0.5
            );
        }

        if (rotate.get()) {
            double yaw = Rotations.getYaw(vec3d);
            double pitch = Rotations.getPitch(vec3d);
            if (yawStepMode.get() != YawStepMode.Break && !doYawSteps(yaw, pitch)) return;

            setRotation(true, vec3d, 0, 0);
            Vec3d hitTarget = new Vec3d(vec3d.x, vec3d.y, vec3d.z);
            long capturedWorldVersion = planner.getWorldVersion();

            Rotations.rotateToward(hitTarget, 70, () -> {
                // Why: rotateToward 可能跨 tick 执行。若期间世界版本漂移过大，说明 proposal 已过时，直接丢弃。
                if (planner.getWorldVersion() - capturedWorldVersion > 5) return;
                if (placeCrystal(result, finalDamage, supportBlock) && supportBlock == null) {
                    placeTimer += getEffectivePlaceDelay();
                }
            });
            return;
        }

        if (placeCrystal(result, finalDamage, supportBlock) && supportBlock == null) {
            placeTimer += getEffectivePlaceDelay();
        }
    }

    /**
     * 安全解析水晶放置的 HitResult —— NCP 方向检查 + LOS 视线检查 + 距离检查。
     * 优先尝试 UP（最常用的基座顶面），然后其余方向。
     *
     * hitVec 使用眼到面的最近点（clamped 到面边界内 [0.01, 0.99]），
     * 而非面死中心，以最小化旋转角度和到面距离。
     *
     * @return 验证通过的 BlockHitResult，不可放置时返回 null
     */
    private BlockHitResult resolveCrystalHit(BlockPos blockPos) {
        Vec3d eyePos = new Vec3d(mc.player.getX(), mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()), mc.player.getZ());
        double reach = placeRange.get();

        // 优先序：UP（最常见的水晶放置面）→ 其余
        Direction[] tryOrder = { Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN };

        double bx = blockPos.getX(), by = blockPos.getY(), bz = blockPos.getZ();

        for (Direction face : tryOrder) {
            // 眼投影到面上的最近点，clamped 到面边界内 (避免 FabricatedPlace [0,1] 边界判定)
            Vec3d hitVec = switch (face) {
                case UP    -> new Vec3d(MathHelper.clamp(eyePos.x, bx + 0.01, bx + 0.99), by + 1.0,  MathHelper.clamp(eyePos.z, bz + 0.01, bz + 0.99));
                case DOWN  -> new Vec3d(MathHelper.clamp(eyePos.x, bx + 0.01, bx + 0.99), by,        MathHelper.clamp(eyePos.z, bz + 0.01, bz + 0.99));
                case NORTH -> new Vec3d(MathHelper.clamp(eyePos.x, bx + 0.01, bx + 0.99), MathHelper.clamp(eyePos.y, by + 0.01, by + 0.99), bz);
                case SOUTH -> new Vec3d(MathHelper.clamp(eyePos.x, bx + 0.01, bx + 0.99), MathHelper.clamp(eyePos.y, by + 0.01, by + 0.99), bz + 1.0);
                case EAST  -> new Vec3d(bx + 1.0,  MathHelper.clamp(eyePos.y, by + 0.01, by + 0.99), MathHelper.clamp(eyePos.z, bz + 0.01, bz + 0.99));
                case WEST  -> new Vec3d(bx,        MathHelper.clamp(eyePos.y, by + 0.01, by + 0.99), MathHelper.clamp(eyePos.z, bz + 0.01, bz + 0.99));
            };

            if (BlockUtilHelper.isPointValid(hitVec, face, blockPos, eyePos, mc.world, mc.player,
                true, strictPlaceLOS.get(), reach, null)) {
                return new BlockHitResult(hitVec, face, blockPos instanceof BlockPos.Mutable ? blockPos.toImmutable() : blockPos, false);
            }
        }

        return null;
    }

    /**
     * Support 放置方案 —— 由 resolveSupportHit() 预验证，供 placeCrystal() 直接使用。
     * 消除 "渲染用 crystal 规则, 执行用 printer 规则" 的不对齐。
     */
    private record SupportPlan(BlockPos interactPos, Direction clickedFace, Vec3d hitVec) {}

    /**
     * 预验证 support 方块放置可行性 —— 与 placeSupportSafe 共享同一套 Printer 规则。
     * <p>
     * 返回 SupportPlan 表示当前帧确实可以把黑曜石放在此位置；返回 null 表示不可行。
     * 调用方可把返回的 plan 直接传给 placeCrystal，避免二次求解（修正 5）。
     */
    private SupportPlan resolveSupportHit(BlockPos pos) {
        if (mc.player == null || mc.world == null) return null;
        if (BlockUtils.getPlaceSide(pos) == null) return null;

        if (supportSafePlacement.get()) {
            // 修正 4: checkLos 参数与上游统一
            net.minecraft.block.BlockState obsidianState = Blocks.OBSIDIAN.getDefaultState();
            PlacementContext ctx = PlacementContext.of(
                mc.world, pos, obsidianState, mc.player, true, strictPlaceLOS.get(), placeRange.get()
            );
            PlacementOption option = ResolverRegistry.resolve(ctx);
            if (option == null || option.hitVec() == null) return null;
            return new SupportPlan(option.getInteractPos(pos), option.getClickedFace(), option.hitVec());
        } else {
            // 传统路径: 只需确认 getPlaceSide 有效（已在上面检查）
            return new SupportPlan(null, null, null);
        }
    }

    private boolean placeCrystal(BlockHitResult result, double damage, BlockPos supportBlock) {
        return placeCrystal(result, damage, supportBlock, -1);
    }

    /**
     * 放置水晶或 support 方块。
     * @param restoreSlot 0-tick 递归时由外层传入的原始槽位 (避免 prevSlot 污染), -1=不指定
     * @return true = 成功发包, false = 某环节失败（物品/手/support 放置等）
     */
    private boolean placeCrystal(BlockHitResult result, double damage, BlockPos supportBlock, int restoreSlot) {
        // Switch
        Item targetItem = supportBlock == null ? Items.END_CRYSTAL : Items.OBSIDIAN;

        FindItemResult item = InvUtils.findInHotbar(targetItem);
        if (!item.found()) return false;

        int prevSlot = restoreSlot >= 0 ? restoreSlot : mc.player.getInventory().getSelectedSlot();

        if (autoSwitch.get() != AutoSwitchMode.None && !item.isOffhand()) InvUtils.swap(item.slot(), false);

        Hand hand = item.getHand();
        if (hand == null) return false;

        // Place
        if (supportBlock == null) {
            // Place crystal
            int seq = mc.world.getPendingUpdateManager().incrementSequence().getSequence();
            mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(hand, result, seq));

            if (swingMode.get().client()) mc.player.swingHand(hand);
            if (swingMode.get().packet()) mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(hand));

            placing = true;
            placingTimer = 4;
            kaTimer = 8;
            placingCrystalBlockPos.set(result.getBlockPos()).move(0, 1, 0);

            placeRenderPos.set(result.getBlockPos());
            renderDamage = damage;
            renderIsSupport = false; // 实际水晶放置 —— 不是 support

            if (renderMode.get() == RenderMode.Normal) {
                placeRenderTimer = placeRenderTime.get();
            } else {
                placeRenderTimer = renderTime.get();
                if (renderMode.get() == RenderMode.Fading) {
                    RenderUtils.renderTickingBlock(
                        placeRenderPos, sideColor.get(),
                        lineColor.get(), shapeMode.get(),
                        0, renderTime.get(), true,
                        false
                    );
                }
            }
        }
        else {
            // 空中放置保护：无有效邻居面时放弃 support（服务端会拒绝）
            if (BlockUtils.getPlaceSide(supportBlock) == null) return false;

            boolean placed;
            if (supportSafePlacement.get()) {
                // 安全放置：复用 Printer 的 NCP/LOS 系统
                // 修正 4: checkLos 使用 strictPlaceLOS 设置而非硬编码 true
                placed = placeSupportSafe(supportBlock, item, hand);
            } else {
                // 传统放置：直接发包（无 NCP/LOS 验证）
                placed = BlockUtils.place(supportBlock, item, false, 0, swingMode.get().client(), true, false);
            }

            if (!placed) return false;

            placeTimer += supportDelay.get();

            // 乐观更新：立即将快照中该位置标记为黑曜石（不等服务端回包）
            // 这样后台线程下一轮扫描会把此位置视为有效基座
            planner.updateBlock(supportBlock.getX(), supportBlock.getY(), supportBlock.getZ(), 1200.0f);

            if (supportDelay.get() == 0) return placeCrystal(result, damage, null, prevSlot);
            // support 放置成功但 delay > 0: 等待下 tick 放水晶
        }

        // Switch back
        if (autoSwitch.get() == AutoSwitchMode.Silent) InvUtils.swap(prevSlot, false);
        return true;
    }

    /**
     * 安全放置支撑方块 —— 复用 Printer 的 NCP 方向检查 + LOS 视线检查 + hitVec 计算。
     * 确保放置方向和点击坐标通过反作弊验证。
     *
     * @return 是否成功发送了放置包
     */
    private boolean placeSupportSafe(BlockPos pos, FindItemResult item, Hand hand) {
        if (mc.player == null || mc.world == null) return false;

        // 构建放置上下文（黑曜石 defaultState，NCP strict + LOS 跟随上游设置）
        net.minecraft.block.BlockState obsidianState = Blocks.OBSIDIAN.getDefaultState();
        PlacementContext ctx = PlacementContext.of(
            mc.world, pos, obsidianState, mc.player, true, strictPlaceLOS.get(), placeRange.get()
        );

        // 通过 Printer 的规则引擎解析最佳放置方案
        PlacementOption option = ResolverRegistry.resolve(ctx);
        if (option == null || option.hitVec() == null) return false;

        // 构建 BlockHitResult
        BlockPos interactPos = option.getInteractPos(pos);
        Direction clickedFace = option.getClickedFace();
        BlockHitResult hitResult = new BlockHitResult(option.hitVec(), clickedFace, interactPos, false);

        if (hand == null) return false;

        // 直接发包 —— 此方法在 Rotations callback 内调用（Post 阶段），
        // 嵌套 Rotations.rotate() 会被 defer 到下一 tick，导致方块包先于旋转包。
        // NCP/LOS 验证已由 ResolverRegistry.resolve 完成，hitVec 合法即可。
        int seq = mc.world.getPendingUpdateManager().incrementSequence().getSequence();
        mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(hand, hitResult, seq));
        if (swingMode.get().client()) mc.player.swingHand(hand);
        if (swingMode.get().packet()) mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(hand));

        return true;
    }

    // 偏航步进

    @EventHandler
    private void onPacketSent(PacketEvent.Sent event) {
        if (event.packet instanceof PlayerMoveC2SPacket) {
            serverYaw = ((PlayerMoveC2SPacket) event.packet).getYaw((float) serverYaw);
        }
    }

    public boolean doYawSteps(double targetYaw, double targetPitch) {
        targetYaw = MathHelper.wrapDegrees(targetYaw) + 180;
        double serverYaw = MathHelper.wrapDegrees(this.serverYaw) + 180;

        if (distanceBetweenAngles(serverYaw, targetYaw) <= yawSteps.get()) return true;

        double delta = Math.abs(targetYaw - serverYaw);
        double yaw = this.serverYaw;

        if (serverYaw < targetYaw) {
            if (delta < 180) yaw += yawSteps.get();
            else yaw -= yawSteps.get();
        }
        else {
            if (delta < 180) yaw -= yawSteps.get();
            else yaw += yawSteps.get();
        }

        setRotation(false, null, yaw, targetPitch);
        Rotations.rotate(yaw, targetPitch, -100, null); // Low priority: just maintaining facing direction
        return false;
    }

    private static double distanceBetweenAngles(double alpha, double beta) {
        double phi = Math.abs(beta - alpha) % 360;
        return phi > 180 ? 360 - phi : phi;
    }

    // 贴脸放置判定

    private boolean shouldFacePlace() {
        if (!facePlace.get()) return false;

        if (forceFacePlace.get().isPressed()) return true;

        // 检查当前位置是否应对任一目标启用贴脸放置
        for (LivingEntity target : targets) {
            if (EntityUtils.getTotalHealth(target) <= facePlaceHealth.get()) return true;

            for (EquipmentSlot slot : AttributeModifierSlot.ARMOR) {
                ItemStack itemStack = target.getEquippedStack(slot);
                if (itemStack == null || itemStack.isEmpty()) {
                    if (facePlaceArmor.get()) return true;
                }
                else {
                    if ((double) (itemStack.getMaxDamage() - itemStack.getDamage()) / itemStack.getMaxDamage() * 100 <= facePlaceDurability.get()) return true;
                }
            }
        }

        return false;
    }

    // 其他工具方法

    /**
     * TPS 自适应放置延迟 —— 低 TPS 时增加间隔避免重复放置包。
     * 正常 TPS (>=16): 原始延迟
     * 低 TPS (12-16): +1 tick
     * 极低 TPS (<12): +2 tick
     */
    private int getEffectivePlaceDelay() {
        int base = placeDelay.get();
        float tps = TickRate.INSTANCE.getTickRate();
        if (tps < 12) return base + 2;
        if (tps < 16) return base + 1;
        return base;
    }

    private boolean shouldPause(PauseMode process) {
        if (mc.player.isUsingItem() || mc.options.useKey.isPressed()) {
            if (pauseOnUse.get().equals(process)) return true;
        }

        if (pauseOnLag.get() && TickRate.INSTANCE.getTimeSinceLastTick() >= 1.0f) return true;
        for (Module module : pauseModules.get()) if (module.isActive()) return true;
        if (pauseOnMine.get().equals(process) && mc.interactionManager.isBreakingBlock()) return true;
        return (EntityUtils.getTotalHealth(mc.player) <= pauseHealth.get());
    }

    /**
     * F1 重写: 范围 + 穿墙检测 —— 对齐 Grim FarPlace/Reach 的检测基准。
     * <p>
     * 改动要点:
     * 1. 起点 = 眼睛 (不再是脚底)
     * 2. 距离 = 到 AABB 最近表面 (不再是到中心点)
     * 3. 壁检测: 放置时检查射线是否命中基座方块; 破坏时检查是否命中实体方块
     *
     * @param targetPos  放置时=水晶中心(bp.X+0.5, bp.Y+1, bp.Z+0.5); 破坏时=entity.getPos()
     * @param blockPos   放置时=基座上方空气方块(bp+1); 破坏时=entity.getBlockPos()
     * @param place      true=放置检测, false=破坏检测
     */
    private boolean isOutOfRange(Vec3d targetPos, BlockPos blockPos, boolean place) {
        double range = (place ? placeRange : breakRange).get();
        double wallsRange = (place ? placeWallsRange : breakWallsRange).get();

        double eyeX = playerEyePos.x, eyeY = playerEyePos.y, eyeZ = playerEyePos.z;

        // ① 到 AABB 最近表面的距离²（匹配 Grim getMinReachToBox）
        double distSq;
        if (place) {
            // 放置: Grim FarPlace 检测 眼→基座方块 AABB 表面
            // blockPos = 基座上方 (bp+1), 基座 = blockPos.Y - 1
            int bx = blockPos.getX(), by = blockPos.getY() - 1, bz = blockPos.getZ();
            double dx = Math.max(bx - eyeX, Math.max(0, eyeX - (bx + 1)));
            double dy = Math.max(by - eyeY, Math.max(0, eyeY - (by + 1)));
            double dz = Math.max(bz - eyeZ, Math.max(0, eyeZ - (bz + 1)));
            distSq = dx * dx + dy * dy + dz * dz;
        } else {
            // 破坏: Grim Reach 检测 眼→水晶 AABB(2×2×2) 表面
            double px = targetPos.x, py = targetPos.y, pz = targetPos.z;
            double dx = Math.max(px - 1 - eyeX, Math.max(0, eyeX - (px + 1)));
            double dy = Math.max(py - eyeY, Math.max(0, eyeY - (py + 2)));
            double dz = Math.max(pz - 1 - eyeZ, Math.max(0, eyeZ - (pz + 1)));
            distSq = dx * dx + dy * dy + dz * dz;
        }

        // ② 壁检测: 从眼到目标点的射线
        ((IRaycastContext) raycastContext).meteor$set(playerEyePos, targetPos,
            RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player);
        BlockHitResult result = mc.world.raycast(raycastContext);

        boolean behindWall;
        if (result.getType() == net.minecraft.util.hit.HitResult.Type.MISS) {
            behindWall = false; // 无遮挡 (对 support 空气位尤其重要)
        } else if (place) {
            // 射线命中基座方块或其下方同列方块 = 直视
            // support 位基座是空气, 射线穿透后打到地板仍算直视 (同 X/Z 列, Y <= baseY)
            int baseY = blockPos.getY() - 1;
            BlockPos hitPos = result.getBlockPos();
            behindWall = hitPos.getX() != blockPos.getX()
                || hitPos.getZ() != blockPos.getZ()
                || hitPos.getY() > baseY;
        } else {
            // 破坏: 射线命中水晶方块或脚下基座 = 直视
            behindWall = !result.getBlockPos().equals(blockPos)
                && !result.getBlockPos().equals(blockPos.down());
        }

        double effectiveRange = behindWall ? wallsRange : range;
        return distSq > effectiveRange * effectiveRange;
    }

    private LivingEntity getNearestTarget() {
        LivingEntity nearestTarget = null;
        double nearestDistance = Double.MAX_VALUE;

        for (LivingEntity target : targets) {
            double distance = PlayerUtils.squaredDistanceTo(target);

            if (distance < nearestDistance) {
                nearestTarget = target;
                nearestDistance = distance;
            }
        }

        return nearestTarget;
    }

    private float getDamageToTargets(Vec3d vec3d, BlockPos obsidianPos, boolean breaking, boolean fast) {
        float damage = 0;

        if (fast) {
            LivingEntity target = getNearestTarget();
            if (target != null) {
                float dmg = DamageUtils.crystalDamage(target, vec3d, predictMovement.get(), obsidianPos);
                // smartDelay: hurtTime > 0 时严格跳过 (客户端不知 lastDamageTaken, 不做致死赌博)
                if (breaking && smartDelay.get() && target.hurtTime > 0) { /* skip */ }
                // 无敌帧冷却：达到连发上限后冷却期间跳过对同一目标的 break
                else if (breaking && targetBreakCooldown.getOrDefault(target.getId(), 0) > 0) { /* skip */ }
                else damage = dmg;
            }
        }
        else {
            for (LivingEntity target : targets) {
                float dmg = DamageUtils.crystalDamage(target, vec3d, predictMovement.get(), obsidianPos);

                // smartDelay: hurtTime > 0 时严格跳过 (客户端不知 lastDamageTaken, 不做致死赌博)
                if (breaking && smartDelay.get() && target.hurtTime > 0) continue;
                // 无敌帧冷却：达到连发上限后冷却期间跳过对同一目标的 break
                if (breaking && targetBreakCooldown.getOrDefault(target.getId(), 0) > 0) continue;

                // Update best target
                if (dmg > bestTargetDamage) {
                    bestTarget = target;
                    bestTargetDamage = dmg;
                    bestTargetTimer = 10;
                }

                damage = Math.max(damage, dmg);
            }
        }

        return damage;
    }

    @Override
    public String getInfoString() {
        return bestTarget != null && bestTargetTimer > 0 ? EntityUtils.getName(bestTarget) : null;
    }

    private void findTargets() {
        targets.clear();

        // Living Entities
        for (Entity entity : mc.world.getEntities()) {
            // Ignore non-living
            if (!(entity instanceof LivingEntity livingEntity)) continue;

            // Player
            if (livingEntity instanceof PlayerEntity player) {
                if (player.getAbilities().creativeMode || livingEntity == mc.player) continue;
                if (!player.isAlive() || !Friends.get().shouldAttack(player)) continue;

                if (ignoreNakeds.get()) {
                    if (player.getOffHandStack().isEmpty()
                        && player.getMainHandStack().isEmpty()
                        && player.getEquippedStack(EquipmentSlot.FEET).isEmpty()
                        && player.getEquippedStack(EquipmentSlot.LEGS).isEmpty()
                        && player.getEquippedStack(EquipmentSlot.CHEST).isEmpty()
                        && player.getEquippedStack(EquipmentSlot.HEAD).isEmpty()
                    ) continue;
                }
            }

            // Animals, water animals, monsters, bats, misc
            if (!(entities.get().contains(livingEntity.getType()))) continue;

            // Close enough to damage
            if (livingEntity.squaredDistanceTo(mc.player) > targetRange.get() * targetRange.get()) continue;

            targets.add(livingEntity);
        }
    }

    private boolean intersectsWithEntities(Box box) {
        return EntityUtils.intersectsWithEntity(box, entity -> !entity.isSpectator() && !removed.contains(entity.getId()));
    }

    /**
     * 用当前主线程世界态刷新渲染伤害值。
     * Why: 显示层不应直接复用 planner 的历史快照结果，否则会出现“方块已变但数字仍旧”的迟滞感。
     */
    private void refreshRenderDamageLive() {
        if (!renderDamageText.get()) return;
        if (renderMode.get() == RenderMode.None || placeRenderTimer <= 0) return;
        if (targets.isEmpty()) {
            renderDamage = 0;
            return;
        }

        ((IVec3d) vec3d).meteor$set(placeRenderPos.getX() + 0.5, placeRenderPos.getY() + 1, placeRenderPos.getZ() + 0.5);
        renderDamage = getDamageToTargets(vec3d, placeRenderPos, false, renderIsSupport && support.get() == SupportMode.Fast);
    }

    // 渲染系统

    /**
     * 实时更新候选渲染位置 —— 在 LOS 验证通过或同步扫描找到最优位置时调用。
     * 不需要等放置成功，让用户始终看到当前瞄准的位置和伤害。
     */
    private void updateRenderCandidate(BlockPos pos, double damage, boolean isSupport) {
        if (renderMode.get() == RenderMode.None) return;
        placeRenderPos.set(pos);
        renderDamage = damage;
        renderIsSupport = isSupport;
        // 持续刷新 timer 保证渲染不中断 —— 下一 tick 无候选时自然倒计时消失
        placeRenderTimer = Math.max(placeRenderTimer, 2);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (renderMode.get() == RenderMode.None) return;

        // 根据当前渲染位置是否为 support 候选选择颜色
        SettingColor sc = renderIsSupport ? supportSideColor.get() : sideColor.get();
        SettingColor lc = renderIsSupport ? supportLineColor.get() : lineColor.get();

        switch (renderMode.get()) {
            case Normal -> {
                if (renderPlace.get() && placeRenderTimer > 0) {
                    event.renderer.box(placeRenderPos, sc, lc, shapeMode.get(), 0);
                }
                if (renderBreak.get() && breakRenderTimer > 0) {
                    event.renderer.box(breakRenderPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
                }
            }

            case Smooth -> {
                if (placeRenderTimer <= 0) return;

                if (renderBoxOne == null) renderBoxOne = new Box(placeRenderPos);
                if (renderBoxTwo == null) renderBoxTwo = new Box(placeRenderPos);
                else ((IBox) renderBoxTwo).meteor$set(placeRenderPos);

                double offsetX = (renderBoxTwo.minX - renderBoxOne.minX) / smoothness.get();
                double offsetY = (renderBoxTwo.minY - renderBoxOne.minY) / smoothness.get();
                double offsetZ = (renderBoxTwo.minZ - renderBoxOne.minZ) / smoothness.get();

                ((IBox) renderBoxOne).meteor$set(
                    renderBoxOne.minX + offsetX,
                    renderBoxOne.minY + offsetY,
                    renderBoxOne.minZ + offsetZ,
                    renderBoxOne.maxX + offsetX,
                    renderBoxOne.maxY + offsetY,
                    renderBoxOne.maxZ + offsetZ
                );

                event.renderer.box(renderBoxOne, sc, lc, shapeMode.get(), 0);
            }

            case Gradient -> {
                if (placeRenderTimer <= 0) return;

                Color bottom = new Color(0, 0, 0, 0);

                int x = placeRenderPos.getX();
                int y = placeRenderPos.getY() + 1;
                int z = placeRenderPos.getZ();

                if (shapeMode.get().sides()) {
                    event.renderer.quadHorizontal(x, y, z, x + 1, z + 1, sc);
                    event.renderer.gradientQuadVertical(x, y, z, x + 1, y - height.get(), z, bottom, sc);
                    event.renderer.gradientQuadVertical(x, y, z, x, y - height.get(), z + 1, bottom, sc);
                    event.renderer.gradientQuadVertical(x + 1, y, z, x + 1, y - height.get(), z + 1, bottom, sc);
                    event.renderer.gradientQuadVertical(x, y, z + 1, x + 1, y - height.get(), z + 1, bottom, sc);
                }

                if (shapeMode.get().lines()) {
                    event.renderer.line(x, y, z, x + 1, y, z, lc);
                    event.renderer.line(x, y, z, x, y, z + 1, lc);
                    event.renderer.line(x + 1, y, z, x + 1, y, z + 1, lc);
                    event.renderer.line(x, y, z + 1, x + 1, y, z + 1, lc);

                    event.renderer.line(x, y, z, x, y - height.get(), z, lc, bottom);
                    event.renderer.line(x + 1, y, z, x + 1, y - height.get(), z, lc, bottom);
                    event.renderer.line(x, y, z + 1, x, y - height.get(), z + 1, lc, bottom);
                    event.renderer.line(x + 1, y, z + 1, x + 1, y - height.get(), z + 1, lc, bottom);
                }
            }

            case Fading -> {
                // Fading 模式：按剩余 timer 线性淡出 alpha，目前退化为 Normal 行为
                if (renderPlace.get() && placeRenderTimer > 0) {
                    event.renderer.box(placeRenderPos, sc, lc, shapeMode.get(), 0);
                }
                if (renderBreak.get() && breakRenderTimer > 0) {
                    event.renderer.box(breakRenderPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
                }
            }

            case None -> {} // onRender 入口的 early-return 已处理，此处不可达
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (renderMode.get() == RenderMode.None || !renderDamageText.get()) return;
        if (placeRenderTimer <= 0 && breakRenderTimer <= 0) return;

        if (renderMode.get() == RenderMode.Smooth) {
            if (renderBoxOne == null) return;
            vec3.set(renderBoxOne.minX + 0.5, renderBoxOne.minY + 0.5, renderBoxOne.minZ + 0.5);
        } else vec3.set(placeRenderPos.getX() + 0.5, placeRenderPos.getY() + 0.5, placeRenderPos.getZ() + 0.5);

        if (NametagUtils.to2D(vec3, damageTextScale.get())) {
            NametagUtils.begin(vec3);
            TextRenderer.get().begin(1, false, true);

            String text = String.format("%.1f", renderDamage);
            double w = TextRenderer.get().getWidth(text) / 2;
            TextRenderer.get().render(text, -w, 0, damageColor.get(), true);

            TextRenderer.get().end();
            NametagUtils.end();
        }
    }

    public enum YawStepMode {
        Break,
        All,
    }

    public enum AutoSwitchMode {
        Normal,
        Silent,
        None
    }

    public enum SupportMode {
        Disabled,
        Accurate,
        Fast
    }

    public enum PauseMode {
        Both,
        Place,
        Break,
        None;

        public boolean equals(PauseMode process) {
            return this == process || this == PauseMode.Both;
        }
    }

    public enum SwingMode {
        Both,
        Packet,
        Client,
        None;

        public boolean packet() {
            return this == Packet || this == Both;
        }

        public boolean client() {
            return this == Client || this == Both;
        }
    }

    public enum RenderMode {
        Normal,
        Smooth,
        Fading,
        Gradient,
        None
    }
}
