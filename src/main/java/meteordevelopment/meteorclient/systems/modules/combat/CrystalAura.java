/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import com.google.common.util.concurrent.AtomicDouble;
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
import meteordevelopment.meteorclient.mixininterface.IMiningToolItem;
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
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class CrystalAura extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSwitch = settings.createGroup("切换");
    private final SettingGroup sgPlace = settings.createGroup("放置");
    private final SettingGroup sgFacePlace = settings.createGroup("贴脸放置");
    private final SettingGroup sgBreak = settings.createGroup("破坏");
    private final SettingGroup sgPause = settings.createGroup("暂停");
    private final SettingGroup sgRender = settings.createGroup("渲染");

    // 通用

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("搜索目标的范围（格）。")
        .defaultValue(10)
        .min(0)
        .sliderMax(16)
        .build()
    );

    private final Setting<Boolean> predictMovement = sgGeneral.add(new BoolSetting.Builder()
        .name("predict-movement")
        .description("预测目标移动位置，提高对移动目标的命中率。")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> minDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-damage")
        .description("水晶对目标造成的最低伤害阈值。")
        .defaultValue(6)
        .min(0)
        .build()
    );

    private final Setting<Double> maxDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-damage")
        .description("水晶对自己造成的最大允许伤害。")
        .defaultValue(6)
        .range(0, 36)
        .sliderMax(36)
        .build()
    );

    private final Setting<Boolean> antiSuicide = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-suicide")
        .description("当水晶会杀死自己时不放置/破坏。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreNakeds = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-nakeds")
        .description("忽略没有穿戴任何物品的玩家。")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("服务端旋转朝向正在放置/破坏的水晶。")
        .defaultValue(true)
        .build()
    );

    private final Setting<YawStepMode> yawStepMode = sgGeneral.add(new EnumSetting.Builder<YawStepMode>()
        .name("yaw-steps-mode")
        .description("何时执行偏航步进检查。")
        .defaultValue(YawStepMode.Break)
        .visible(rotate::get)
        .build()
    );

    private final Setting<Double> yawSteps = sgGeneral.add(new DoubleSetting.Builder()
        .name("yaw-steps")
        .description("每 tick 允许的最大旋转角度。")
        .defaultValue(180)
        .range(1, 180)
        .visible(rotate::get)
        .build()
    );

    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("要攻击的实体类型。")
        .onlyAttackable()
        .defaultValue(EntityType.PLAYER, EntityType.WARDEN, EntityType.WITHER)
        .build()
    );

    // Switch

    private final Setting<AutoSwitchMode> autoSwitch = sgSwitch.add(new EnumSetting.Builder<AutoSwitchMode>()
        .name("auto-switch")
        .description("发现目标时自动切换到快捷栏上的水晶。")
        .defaultValue(AutoSwitchMode.Normal)
        .build()
    );

    private final Setting<Integer> switchDelay = sgSwitch.add(new IntSetting.Builder()
        .name("switch-delay")
        .description("切换快捷栏槽位后等待多少 tick 再破坏水晶。")
        .defaultValue(0)
        .min(0)
        .build()
    );

    private final Setting<Boolean> noGapSwitch = sgSwitch.add(new BoolSetting.Builder()
        .name("no-gap-switch")
        .description("手持金苹果时不自动切换。")
        .defaultValue(true)
        .visible(() -> autoSwitch.get() == AutoSwitchMode.Normal)
        .build()
    );

    private final Setting<Boolean> noBowSwitch = sgSwitch.add(new BoolSetting.Builder()
        .name("no-bow-switch")
        .description("手持弓时不自动切换。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> antiWeakness = sgSwitch.add(new BoolSetting.Builder()
        .name("anti-weakness")
        .description("有虚弱效果时自动切换到工具以破坏水晶。")
        .defaultValue(true)
        .build()
    );

    // 放置

    private final Setting<Boolean> doPlace = sgPlace.add(new BoolSetting.Builder()
        .name("place")
        .description("是否自动放置水晶。")
        .defaultValue(true)
        .build()
    );

    public final Setting<Integer> placeDelay = sgPlace.add(new IntSetting.Builder()
        .name("place-delay")
        .description("水晶爆炸后等待多少 tick 再放置下一个。")
        .defaultValue(0)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Double> placeRange = sgPlace.add(new DoubleSetting.Builder()
        .name("place-range")
        .description("放置水晶的范围（格）。")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> placeWallsRange = sgPlace.add(new DoubleSetting.Builder()
        .name("walls-range")
        .description("穿墙放置水晶的范围（格）。")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Boolean> placement112 = sgPlace.add(new BoolSetting.Builder()
        .name("1.12-placement")
        .description("使用 1.12 版放置规则（基座上方 2 格空间）。")
        .defaultValue(false)
        .build()
    );

    private final Setting<SupportMode> support = sgPlace.add(new EnumSetting.Builder<SupportMode>()
        .name("support")
        .description("找不到其他位置时，在空中放置支撑方块。")
        .defaultValue(SupportMode.Disabled)
        .build()
    );

    private final Setting<Integer> supportDelay = sgPlace.add(new IntSetting.Builder()
        .name("support-delay")
        .description("放置支撑方块后等待的 tick 数。")
        .defaultValue(1)
        .min(0)
        .visible(() -> support.get() != SupportMode.Disabled)
        .build()
    );

    // 贴脸放置

    private final Setting<Boolean> facePlace = sgFacePlace.add(new BoolSetting.Builder()
        .name("face-place")
        .description("目标血量或护甲耐久低于阈值时启用贴脸放置。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> facePlaceHealth = sgFacePlace.add(new DoubleSetting.Builder()
        .name("face-place-health")
        .description("启用贴脸放置的目标血量阈值。")
        .defaultValue(8)
        .min(1)
        .sliderMin(1)
        .sliderMax(36)
        .visible(facePlace::get)
        .build()
    );

    private final Setting<Double> facePlaceDurability = sgFacePlace.add(new DoubleSetting.Builder()
        .name("face-place-durability")
        .description("启用贴脸放置的护甲耐久百分比阈值。")
        .defaultValue(2)
        .min(1)
        .sliderMin(1)
        .sliderMax(100)
        .visible(facePlace::get)
        .build()
    );

    private final Setting<Boolean> facePlaceArmor = sgFacePlace.add(new BoolSetting.Builder()
        .name("face-place-missing-armor")
        .description("目标缺少护甲部件时自动启用贴脸放置。")
        .defaultValue(false)
        .visible(facePlace::get)
        .build()
    );

    private final Setting<Keybind> forceFacePlace = sgFacePlace.add(new KeybindSetting.Builder()
        .name("force-face-place")
        .description("按下此键强制启用贴脸放置。")
        .defaultValue(Keybind.none())
        .build()
    );

    // 破坏

    private final Setting<Boolean> doBreak = sgBreak.add(new BoolSetting.Builder()
        .name("break")
        .description("是否自动破坏水晶。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> breakDelay = sgBreak.add(new IntSetting.Builder()
        .name("break-delay")
        .description("水晶放置后等待多少 tick 再破坏。")
        .defaultValue(0)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> smartDelay = sgBreak.add(new BoolSetting.Builder()
        .name("smart-delay")
        .description("仅在目标能受到伤害时才破坏水晶（无受伤 CD）。")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> breakRange = sgBreak.add(new DoubleSetting.Builder()
        .name("break-range")
        .description("破坏水晶的范围（格）。")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> breakWallsRange = sgBreak.add(new DoubleSetting.Builder()
        .name("walls-range")
        .description("穿墙破坏水晶的范围（格）。")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Boolean> onlyBreakOwn = sgBreak.add(new BoolSetting.Builder()
        .name("only-own")
        .description("仅破坏自己放置的水晶。")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> breakAttempts = sgBreak.add(new IntSetting.Builder()
        .name("break-attempts")
        .description("对同一颗水晶的最大攻击次数。")
        .defaultValue(2)
        .sliderMin(1)
        .sliderMax(5)
        .build()
    );

    private final Setting<Integer> ticksExisted = sgBreak.add(new IntSetting.Builder()
        .name("ticks-existed")
        .description("水晶需要存在多少 tick 后才能被攻击。")
        .defaultValue(0)
        .min(0)
        .build()
    );

    private final Setting<Integer> attackFrequency = sgBreak.add(new IntSetting.Builder()
        .name("attack-frequency")
        .description("每秒最大攻击次数。")
        .defaultValue(25)
        .min(1)
        .sliderRange(1, 30)
        .build()
    );

    private final Setting<Boolean> fastBreak = sgBreak.add(new BoolSetting.Builder()
        .name("fast-break")
        .description("忽略破坏延迟，水晶生成后立即尝试破坏。")
        .defaultValue(true)
        .build()
    );

    // 暂停

    public final Setting<PauseMode> pauseOnUse = sgPause.add(new EnumSetting.Builder<PauseMode>()
        .name("pause-on-use")
        .description("使用物品时暂停哪些流程。")
        .defaultValue(PauseMode.Place)
        .build()
    );

    public final Setting<PauseMode> pauseOnMine = sgPause.add(new EnumSetting.Builder<PauseMode>()
        .name("pause-on-mine")
        .description("挖掘方块时暂停哪些流程。")
        .defaultValue(PauseMode.None)
        .build()
    );

    private final Setting<Boolean> pauseOnLag = sgPause.add(new BoolSetting.Builder()
        .name("pause-on-lag")
        .description("服务器无响应时是否暂停。")
        .defaultValue(true)
        .build()
    );

    public final Setting<List<Module>> pauseModules = sgPause.add(new ModuleListSetting.Builder()
        .name("pause-modules")
        .description("任一已选模块激活时暂停。")
        .defaultValue(BedAura.class)
        .build()
    );

    public final Setting<Double> pauseHealth = sgPause.add(new DoubleSetting.Builder()
        .name("pause-health")
        .description("自身血量低于此值时暂停。")
        .defaultValue(5)
        .range(0,36)
        .sliderRange(0,36)
        .build()
    );

    // 渲染

    public final Setting<SwingMode> swingMode = sgRender.add(new EnumSetting.Builder<SwingMode>()
        .name("swing-mode")
        .description("放置时的挥手方式。")
        .defaultValue(SwingMode.Both)
        .build()
    );

    private final Setting<RenderMode> renderMode = sgRender.add(new EnumSetting.Builder<RenderMode>()
        .name("render-mode")
        .description("渲染模式。")
        .defaultValue(RenderMode.Normal)
        .build()
    );

    private final Setting<Boolean> renderPlace = sgRender.add(new BoolSetting.Builder()
        .name("render-place")
        .description("在放置水晶的方块上渲染覆盖层。")
        .defaultValue(true)
        .visible(() -> renderMode.get() == RenderMode.Normal)
        .build()
    );

    private final Setting<Integer> placeRenderTime = sgRender.add(new IntSetting.Builder()
        .name("place-time")
        .description("放置渲染持续时间（tick）。")
        .defaultValue(10)
        .min(0)
        .sliderMax(20)
        .visible(() -> renderMode.get() == RenderMode.Normal && renderPlace.get())
        .build()
    );

    private final Setting<Boolean> renderBreak = sgRender.add(new BoolSetting.Builder()
        .name("render-break")
        .description("在破坏水晶的方块上渲染覆盖层。")
        .defaultValue(false)
        .visible(() -> renderMode.get() == RenderMode.Normal)
        .build()
    );

    private final Setting<Integer> breakRenderTime = sgRender.add(new IntSetting.Builder()
        .name("break-time")
        .description("破坏渲染持续时间（tick）。")
        .defaultValue(13)
        .min(0)
        .sliderMax(20)
        .visible(() -> renderMode.get() == RenderMode.Normal && renderBreak.get())
        .build()
    );

    private final Setting<Integer> smoothness = sgRender.add(new IntSetting.Builder()
        .name("smoothness")
        .description("平滑渲染的平滑度。")
        .defaultValue(10)
        .min(0)
        .sliderMax(20)
        .visible(() -> renderMode.get() == RenderMode.Smooth)
        .build()
    );

    private final Setting<Double> height = sgRender.add(new DoubleSetting.Builder()
        .name("height")
        .description("渐变渲染的高度。")
        .defaultValue(0.7)
        .min(0)
        .sliderMax(1)
        .visible(() -> renderMode.get() == RenderMode.Gradient)
        .build()
    );

    private final Setting<Integer> renderTime = sgRender.add(new IntSetting.Builder()
        .name("render-time")
        .description("渲染持续时间（tick）。")
        .defaultValue(10)
        .min(0)
        .sliderMax(20)
        .visible(() -> renderMode.get() == RenderMode.Smooth || renderMode.get() == RenderMode.Fading)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("形状渲染方式。")
        .defaultValue(ShapeMode.Both)
        .visible(() -> renderMode.get() != RenderMode.None)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("覆盖层侧面颜色。")
        .defaultValue(new SettingColor(255, 255, 255, 45))
        .visible(() -> shapeMode.get().sides() && renderMode.get() != RenderMode.None)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("覆盖层边线颜色。")
        .defaultValue(new SettingColor(255, 255, 255))
        .visible(() -> shapeMode.get().lines() && renderMode.get() != RenderMode.None)
        .build()
    );

    private final Setting<Boolean> renderDamageText = sgRender.add(new BoolSetting.Builder()
        .name("damage")
        .description("在覆盖层上显示水晶伤害数值。")
        .defaultValue(true)
        .visible(() -> renderMode.get() != RenderMode.None)
        .build()
    );

    private final Setting<SettingColor> damageColor = sgRender.add(new ColorSetting.Builder()
        .name("damage-color")
        .description("伤害数值的文字颜色。")
        .defaultValue(new SettingColor(255, 255, 255))
        .visible(() -> renderMode.get() != RenderMode.None && renderDamageText.get())
        .build()
    );

    private final Setting<Double> damageTextScale = sgRender.add(new DoubleSetting.Builder()
        .name("damage-scale")
        .description("伤害文字的大小。")
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

    private final Vec3d vec3dRayTraceEnd = new Vec3d(0, 0, 0);
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

    // 水晶生命周期追踪（替代实体 mixin，所有状态集中管理）
    private boolean attackedThisTick;                                       // 本 tick 已攻击标志，防止重复攻击
    private final Int2LongMap crystalSpawnTimes = new Int2LongOpenHashMap(); // entityId → 生成时间戚，用于新生判定
    private final IntSet handledCrystals = new IntOpenHashSet();             // 已处理的水晶 id，避免重复处理

    // 有效基座位置缓存 —— BlockUpdateEvent 时增量失效，过滤 ~90% 的无效位置
    private final LongSet validBasePosCache = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private boolean baseCacheDirty = true;
    private int baseCacheScanRange;
    private int baseCacheCenterX, baseCacheCenterY, baseCacheCenterZ;

    // 放置方案缓存 —— 分摊扫描开销到 2–3 tick（mio 方案模式）
    private final BlockPos.Mutable proposalPos = new BlockPos.Mutable();
    private boolean proposalIsSupport;
    private double proposalDamage;
    private boolean hasProposal;
    private int proposalAge;

    // 异步规划器 —— 将完整扫描委派给后台线程 (CrystalPlanner)
    private final CrystalPlanner planner = new CrystalPlanner();

    public CrystalAura() {
        super(Categories.Combat, "crystal-aura", "自动放置与攻击末影水晶。");
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
        baseCacheDirty = true;
        hasProposal = false;
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
        attackedThisTick = false;
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

        // Find targets, break and place
        findTargets();

        if (!targets.isEmpty()) {
            if (!didRotateThisTick) doBreak();
            if (!didRotateThisTick) doPlace();
        }
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
            // 致命覆盖：新生水晶若能击杀目标，无视 smartDelay 限制
            if (damage <= 0 && smartDelay.get()) {
                damage = getBreakDamageLethalOverride(event.entity, !isOwnCrystal);
            }
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
        if (attemptedBreaks.get(entity.getId()) > breakAttempts.get()) return 0;

        // Check crystal age
        if (checkCrystalAge && entity.age < ticksExisted.get()) return 0;

        // Check range
        if (isOutOfRange(entity.getPos(), entity.getBlockPos(), false)) return 0;

        // Check damage to self and anti suicide
        // Low TPS safety margin: 15% reduction on maxDamage threshold
        blockPos.set(entity.getBlockPos()).move(0, -1, 0);
        float selfDamage = DamageUtils.crystalDamage(mc.player, entity.getPos(), predictMovement.get(), blockPos);
        float effectiveMaxDmg = maxDamage.get().floatValue();
        if (TickRate.INSTANCE.getTickRate() < 18) effectiveMaxDmg *= 0.85f;
        if (selfDamage > effectiveMaxDmg || (antiSuicide.get() && selfDamage >= EntityUtils.getTotalHealth(mc.player))) return 0;

        // Check damage to targets and face place
        float damage = getDamageToTargets(entity.getPos(), blockPos, true, false);
        boolean shouldFacePlace = shouldFacePlace();
        double minimumDamage = shouldFacePlace ? Math.min(minDamage.get(), 1.5d) : minDamage.get();

        if (damage < minimumDamage) return 0f;

        return damage;
    }

    /**
     * 致命覆盖版 getBreakDamage —— 仅在新生窗口使用。
     * 忽略 smartDelay（hurtTime）限制，但仅当伤害能击杀任一目标时才返回非零值。
     * 其余检查（age、range、self-damage、anti-suicide）与标准版一致。
     */
    private float getBreakDamageLethalOverride(Entity entity, boolean checkCrystalAge) {
        if (!(entity instanceof EndCrystalEntity)) return 0;
        if (onlyBreakOwn.get() && !placedCrystals.contains(entity.getId())) return 0;
        if (removed.contains(entity.getId())) return 0;
        if (attemptedBreaks.get(entity.getId()) > breakAttempts.get()) return 0;
        if (checkCrystalAge && entity.age < ticksExisted.get()) return 0;
        if (isOutOfRange(entity.getPos(), entity.getBlockPos(), false)) return 0;

        blockPos.set(entity.getBlockPos()).move(0, -1, 0);
        float selfDamage = DamageUtils.crystalDamage(mc.player, entity.getPos(), predictMovement.get(), blockPos);
        float effectiveMaxDmg = maxDamage.get().floatValue();
        if (TickRate.INSTANCE.getTickRate() < 18) effectiveMaxDmg *= 0.85f;
        if (selfDamage > effectiveMaxDmg || (antiSuicide.get() && selfDamage >= EntityUtils.getTotalHealth(mc.player))) return 0;

        // 仅检查致命伤害（无视 hurtTime）
        for (LivingEntity target : targets) {
            float dmg = DamageUtils.crystalDamage(target, entity.getPos(), predictMovement.get(), blockPos);
            if (dmg >= EntityUtils.getTotalHealth(target)) return dmg;
        }
        return 0;
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
                Rotations.rotate(yaw, pitch, 50, () -> attackCrystal(crystal));

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

            // Break render
            breakRenderPos.set(crystal.getBlockPos().down());
            breakRenderTimer = breakRenderTime.get();
        }
    }

    private boolean isValidWeaknessItem(ItemStack itemStack) {
        if (!(itemStack.getItem() instanceof IMiningToolItem) || itemStack.getItem() instanceof HoeItem) return false;

        ToolMaterial material = ((IMiningToolItem) itemStack.getItem()).meteor$getMaterial();
        return material == ToolMaterial.DIAMOND || material == ToolMaterial.NETHERITE;
    }

    private void attackCrystal(Entity entity) {
        // Attack
        mc.player.networkHandler.sendPacket(PlayerInteractEntityC2SPacket.attack(entity, mc.player.isSneaking()));

        Hand hand = InvUtils.findInHotbar(Items.END_CRYSTAL).getHand();
        if (hand == null) hand = Hand.MAIN_HAND;

        if (swingMode.get().client()) mc.player.swingHand(hand);
        if (swingMode.get().packet()) mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(hand));

        attacks++;
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (event.packet instanceof UpdateSelectedSlotC2SPacket) {
            switchTimer = switchDelay.get();
        }
    }

    /**
     * 玩家手动右键使用物品时，废弃旧的异步搜索结果和方案缓存。
     * mio 宿主层协同：避免用户操作与 AC 过期结果冲突。
     */
    @EventHandler
    private void onInteractItem(InteractItemEvent event) {
        planner.clearResults();
        hasProposal = false;
    }

    /**
     * 智能 support 仲裁 —— 决定是否优先选择 support 方案而非 direct 方案。
     * - support 伤害致命（>= 目标血量）→ 始终优先
     * - 目标低血量（贴脸放置区间）→ 阈值降为 1.2x
     * - 默认 → support 伤害需超过 direct 的 1.5x
     */
    private boolean shouldPreferSupport(double supportDmg, double directDmg) {
        if (directDmg <= 0) return supportDmg > 0;
        LivingEntity nearest = getNearestTarget();
        if (nearest != null) {
            float hp = EntityUtils.getTotalHealth(nearest);
            // 致命 support 始终优先（能一击杀则不计代价补块）
            if (supportDmg >= hp) return true;
            // 低血量目标时降低阈值（补块延迟小于对手回血量）
            if (hp <= facePlaceHealth.get()) return supportDmg > directDmg * 1.2;
        }
        return supportDmg > directDmg * 1.5;
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (mc.player == null) return;
        BlockPos pos = event.pos;

        // Invalidate placement proposal if block changed near proposal position
        if (hasProposal && pos.isWithinDistance(proposalPos, 3)) hasProposal = false;

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

        if (planner.isBusy()) return;

        // 2. Snapshot targets
        boolean predict = predictMovement.get();
        CrystalPlanner.TargetSnap[] tSnaps = new CrystalPlanner.TargetSnap[targets.size()];
        for (int i = 0; i < targets.size(); i++) tSnaps[i] = CrystalPlanner.snapshotTarget(targets.get(i), predict);
        CrystalPlanner.TargetSnap selfSnap = CrystalPlanner.snapshotTarget(mc.player, predict);

        // 3. Collect valid bases + pre-filter (range, wall, entity overlap on main thread)
        List<long[]> candidates = new ArrayList<>();
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

            candidates.add(new long[]{packed, hasBlock ? 1 : 0});
        }

        if (candidates.isEmpty()) return;

        // 4. Submit to planner background thread
        planner.submitScan(
            candidates.toArray(new long[0][]),
            tSnaps, selfSnap,
            new CrystalPlanner.ScanSettings(
                maxDamage.get(), antiSuicide.get(), minDamage.get(), smartDelay.get(),
                shouldFacePlace(), support.get() == SupportMode.Fast, TickRate.INSTANCE.getTickRate(),
                mc.world.getDifficulty())
        );
    }

    // 方案验证 —— 仅 2 次伤害计算替代完整扫描（~80 次）

    private boolean quickValidateProposal() {
        // Base block still valid?
        net.minecraft.block.BlockState state = mc.world.getBlockState(proposalPos);
        if (proposalIsSupport) {
            if (!state.isReplaceable()) return false;
        } else {
            if (!state.isOf(Blocks.BEDROCK) && !state.isOf(Blocks.OBSIDIAN)) return false;
        }

        // Air above still clear?
        blockPos.set(proposalPos).move(0, 1, 0);
        if (!mc.world.getBlockState(blockPos).isAir()) return false;

        // Range still OK?
        ((IVec3d) vec3d).meteor$set(proposalPos.getX() + 0.5, proposalPos.getY() + 1, proposalPos.getZ() + 0.5);
        if (isOutOfRange(vec3d, blockPos, true)) return false;

        // Self-damage still safe?
        float selfDamage = DamageUtils.crystalDamage(mc.player, vec3d, predictMovement.get(), proposalPos);
        float effectiveMaxDmg = maxDamage.get().floatValue();
        if (TickRate.INSTANCE.getTickRate() < 18) effectiveMaxDmg *= 0.85f;
        if (selfDamage > effectiveMaxDmg || (antiSuicide.get() && selfDamage >= EntityUtils.getTotalHealth(mc.player))) return false;

        // Target damage still meets threshold?
        float damage = getDamageToTargets(vec3d, proposalPos, false, proposalIsSupport && support.get() == SupportMode.Fast);
        double minimumDamage = shouldFacePlace() ? Math.min(minDamage.get(), 1.5) : minDamage.get();
        if (damage < minimumDamage) return false;

        // Entity intersection?
        double x = proposalPos.getX(), y = proposalPos.getY() + 1, z = proposalPos.getZ();
        ((IBox) box).meteor$set(x, y, z, x + 1, y + (placement112.get() ? 1 : 2), z + 1);
        if (intersectsWithEntities(box)) return false;

        proposalDamage = damage;
        return true;
    }

    private void executeProposal() {
        BlockHitResult result = getPlaceInfo(proposalPos);
        BlockPos supportBlock = proposalIsSupport ? proposalPos.toImmutable() : null;

        ((IVec3d) vec3d).meteor$set(
            result.getBlockPos().getX() + 0.5 + result.getSide().getVector().getX() * 0.5,
            result.getBlockPos().getY() + 0.5 + result.getSide().getVector().getY() * 0.5,
            result.getBlockPos().getZ() + 0.5 + result.getSide().getVector().getZ() * 0.5
        );

        if (rotate.get()) {
            double yaw = Rotations.getYaw(vec3d);
            double pitch = Rotations.getPitch(vec3d);

            if (yawStepMode.get() == YawStepMode.Break || doYawSteps(yaw, pitch)) {
                setRotation(true, vec3d, 0, 0);
                Rotations.rotate(yaw, pitch, 50, () -> placeCrystal(result, proposalDamage, supportBlock));
                placeTimer += getEffectivePlaceDelay();
            }
        } else {
            placeCrystal(result, proposalDamage, supportBlock);
            placeTimer += getEffectivePlaceDelay();
        }
    }

    // 放置流程

    private void doPlace() {
        if (!doPlace.get() || placeTimer > 0) return;
        if (shouldPause(PauseMode.Place)) return;

        // 等待水晶生成确认时不发送冗余放置包
        // RusherHack/mio 模式：低 TPS 时零浪费包
        if (placing && placingTimer > 0) return;

        // Return if there are no crystals in hotbar or offhand
        if (!InvUtils.testInHotbar(Items.END_CRYSTAL)) return;

        // Return if there are no crystals in either hand and auto switch mode is none
        if (autoSwitch.get() != AutoSwitchMode.None) {
            if (noGapSwitch.get() && autoSwitch.get() == AutoSwitchMode.Normal && offItem != Items.END_CRYSTAL) {
                if (mainItem == Items.ENCHANTED_GOLDEN_APPLE
                || offItem == Items.ENCHANTED_GOLDEN_APPLE
                || mainItem == Items.GOLDEN_APPLE
                || offItem == Items.GOLDEN_APPLE) return;
            }
            if (noBowSwitch.get() && (mainItem == Items.BOW || offItem == Items.BOW)) return;
        } else if (mainItem != Items.END_CRYSTAL && offItem != Items.END_CRYSTAL) return;

        // Check for multiplace
        for (Entity entity : mc.world.getEntities()) {
            if (getBreakDamage(entity, false) > 0) return;
        }

        // === Async pipeline: consume result from background thread ===
        CrystalPlanner.PlaceResult ad = planner.getDirectResult(), as = planner.getSupportResult();
        if (ad != null || as != null) {
            planner.clearResults();

            boolean supportEnabled = support.get() != SupportMode.Disabled;
            boolean useDirect = ad != null;
            boolean useSupport = supportEnabled && as != null
                && (!useDirect || as.damage() > ad.damage() * 1.5);

            // 智能仲裁：致命/低血量场景动态调整阈值
            if (supportEnabled && as != null && ad != null) {
                useSupport = shouldPreferSupport(as.damage(), ad.damage());
            }

            CrystalPlanner.PlaceResult chosen = useSupport ? as : useDirect ? ad : null;
            if (chosen != null) {
                BlockPos.Mutable bp = new BlockPos.Mutable(chosen.x(), chosen.y(), chosen.z());

                // Quick validation on main thread: base still valid + range OK
                net.minecraft.block.BlockState state = mc.world.getBlockState(bp);
                boolean hasBlock = state.isOf(Blocks.BEDROCK) || state.isOf(Blocks.OBSIDIAN);
                boolean valid = hasBlock || (supportEnabled && state.isReplaceable());

                if (valid) {
                    ((IVec3d) vec3d).meteor$set(bp.getX() + 0.5, bp.getY() + 1, bp.getZ() + 0.5);
                    blockPos.set(bp).move(0, 1, 0);
                    if (!isOutOfRange(vec3d, blockPos, true)) {
                        // Save as proposal for subsequent ticks
                        proposalPos.set(bp);
                        proposalIsSupport = useSupport;
                        proposalDamage = chosen.damage();
                        hasProposal = true;
                        proposalAge = 0;

                        // Execute placement
                        BlockHitResult result = getPlaceInfo(bp);
                        BlockPos supportBlock = useSupport ? bp.toImmutable() : null;

                        ((IVec3d) vec3d).meteor$set(
                            result.getBlockPos().getX() + 0.5 + result.getSide().getVector().getX() * 0.5,
                            result.getBlockPos().getY() + 0.5 + result.getSide().getVector().getY() * 0.5,
                            result.getBlockPos().getZ() + 0.5 + result.getSide().getVector().getZ() * 0.5);

                        if (rotate.get()) {
                            double yaw = Rotations.getYaw(vec3d);
                            double pitch = Rotations.getPitch(vec3d);
                            if (yawStepMode.get() == YawStepMode.Break || doYawSteps(yaw, pitch)) {
                                setRotation(true, vec3d, 0, 0);
                                Rotations.rotate(yaw, pitch, 50, () -> placeCrystal(result, chosen.damage(), supportBlock));
                                placeTimer += getEffectivePlaceDelay();
                            }
                        } else {
                            placeCrystal(result, chosen.damage(), supportBlock);
                            placeTimer += getEffectivePlaceDelay();
                        }

                        // Submit next async scan for following ticks
                        captureAndSubmitAsyncScan();
                        return;
                    }
                }
            }
        }

        // 方案缓存快速路径：复用上次扫描结果（2 次伤害计算 vs 80+）
        if (hasProposal && proposalAge < 3) {
            if (quickValidateProposal()) {
                proposalAge++;
                executeProposal();
                // Submit async scan in background for when proposal expires
                captureAndSubmitAsyncScan();
                return;
            }
            hasProposal = false;
        }

        // Submit async scan for next tick while falling through to sync scan
        captureAndSubmitAsyncScan();

        // 同步回退：通过 BlockIterator 全量扫描（首 tick 或异步未就绪时使用）

        // Setup variables — track direct and support candidates independently
        AtomicDouble bestDirectDamage = new AtomicDouble(0);
        AtomicReference<BlockPos.Mutable> bestDirectPos = new AtomicReference<>(new BlockPos.Mutable());
        AtomicDouble bestSupportDamage = new AtomicDouble(0);
        AtomicReference<BlockPos.Mutable> bestSupportPos = new AtomicReference<>(new BlockPos.Mutable());
        boolean supportEnabled = support.get() != SupportMode.Disabled;

        // Refresh valid base cache (invalidated on block updates)
        refreshBaseCacheIfNeeded();

        // Find best position — cache filters ~90% of positions before expensive damage calcs
        BlockIterator.register((int) Math.ceil(placeRange.get()), (int) Math.ceil(placeRange.get()), (bp, blockState) -> {
            // Fast-path: skip positions not in valid base cache
            if (!validBasePosCache.contains(net.minecraft.util.math.BlockPos.asLong(bp.getX(), bp.getY(), bp.getZ()))) return;

            boolean hasBlock = blockState.isOf(Blocks.BEDROCK) || blockState.isOf(Blocks.OBSIDIAN);

            // Range check (position-relative, can't cache)
            ((IVec3d) vec3d).meteor$set(bp.getX() + 0.5, bp.getY() + 1, bp.getZ() + 0.5);
            blockPos.set(bp).move(0, 1, 0);
            if (isOutOfRange(vec3d, blockPos, true)) return;

            // Check damage to self and anti suicide
            // On low TPS (< 18), add 15% safety margin — entity positions may be staler
            float selfDamage = DamageUtils.crystalDamage(mc.player, vec3d, predictMovement.get(), bp);
            float effectiveMaxDmg = maxDamage.get().floatValue();
            if (TickRate.INSTANCE.getTickRate() < 18) effectiveMaxDmg *= 0.85f;
            if (selfDamage > effectiveMaxDmg || (antiSuicide.get() && selfDamage >= EntityUtils.getTotalHealth(mc.player))) return;

            // Check damage to targets and face place
            float damage = getDamageToTargets(vec3d, bp, false, !hasBlock && support.get() == SupportMode.Fast);

            boolean shouldFacePlace = shouldFacePlace();
            double minimumDamage = Math.min(minDamage.get(), shouldFacePlace ? 1.5 : minDamage.get());

            if (damage < minimumDamage) return;

            // Check if it can be placed
            double x = bp.getX();
            double y = bp.getY() + 1;
            double z = bp.getZ();
            ((IBox) box).meteor$set(x, y, z, x + 1, y + (placement112.get() ? 1 : 2), z + 1);

            if (intersectsWithEntities(box)) return;

            // Compare damage — direct and support tracked independently
            if (hasBlock) {
                if (damage > bestDirectDamage.get()) {
                    bestDirectDamage.set(damage);
                    bestDirectPos.get().set(bp);

                    // 致命短路：如果该位置可击杀最优目标，停止扫描
                    LivingEntity nearest = getNearestTarget();
                    if (nearest != null && damage >= EntityUtils.getTotalHealth(nearest)) {
                        BlockIterator.disableCurrent();
                        return;
                    }
                }
            } else {
                if (damage > bestSupportDamage.get()) {
                    bestSupportDamage.set(damage);
                    bestSupportPos.get().set(bp);
                }
            }
        });

        // Place the crystal — choose between direct and support candidates
        BlockIterator.after(() -> {
            // GrimAC MultiActionsF：同一 tick 内实体交互 + 方块放置会被标记
            // Skip placement on ticks where we already attacked a crystal
            if (attackedThisTick) return;

            // Direct always preferred; support only when no direct or support damage significantly higher
            boolean useDirect = bestDirectDamage.get() > 0;
            boolean useSupport = supportEnabled && bestSupportDamage.get() > 0
                && (!useDirect || bestSupportDamage.get() > bestDirectDamage.get() * 1.5);

            // 智能仲裁：致命/低血量场景动态调整阈值
            if (supportEnabled && bestSupportDamage.get() > 0 && bestDirectDamage.get() > 0) {
                useSupport = shouldPreferSupport(bestSupportDamage.get(), bestDirectDamage.get());
            }

            double bestDamage;
            BlockPos.Mutable bestBlockPos;
            BlockPos supportBlock;

            if (useSupport) {
                bestDamage = bestSupportDamage.get();
                bestBlockPos = bestSupportPos.get();
                supportBlock = bestBlockPos;
            } else if (useDirect) {
                bestDamage = bestDirectDamage.get();
                bestBlockPos = bestDirectPos.get();
                supportBlock = null;
            } else {
                return;
            }

            // Cache this scan result as a proposal for next 2-3 ticks
            proposalPos.set(bestBlockPos);
            proposalIsSupport = supportBlock != null;
            proposalDamage = bestDamage;
            hasProposal = true;
            proposalAge = 0;

            BlockHitResult result = getPlaceInfo(bestBlockPos);

            ((IVec3d) vec3d).meteor$set(
                    result.getBlockPos().getX() + 0.5 + result.getSide().getVector().getX() * 1.0 / 2.0,
                    result.getBlockPos().getY() + 0.5 + result.getSide().getVector().getY() * 1.0 / 2.0,
                    result.getBlockPos().getZ() + 0.5 + result.getSide().getVector().getZ() * 1.0 / 2.0
            );

            if (rotate.get()) {
                double yaw = Rotations.getYaw(vec3d);
                double pitch = Rotations.getPitch(vec3d);

                if (yawStepMode.get() == YawStepMode.Break || doYawSteps(yaw, pitch)) {
                    setRotation(true, vec3d, 0, 0);
                    Rotations.rotate(yaw, pitch, 50, () -> placeCrystal(result, bestDamage, supportBlock));

                    placeTimer += getEffectivePlaceDelay();
                }
            }
            else {
                placeCrystal(result, bestDamage, supportBlock);
                placeTimer += getEffectivePlaceDelay();
            }
        });
    }

    private BlockHitResult getPlaceInfo(BlockPos blockPos) {
        ((IVec3d) vec3d).meteor$set(mc.player.getX(), mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()), mc.player.getZ());

        for (Direction side : Direction.values()) {
            ((IVec3d) vec3dRayTraceEnd).meteor$set(
                    blockPos.getX() + 0.5 + side.getVector().getX() * 0.5,
                    blockPos.getY() + 0.5 + side.getVector().getY() * 0.5,
                    blockPos.getZ() + 0.5 + side.getVector().getZ() * 0.5
            );

            ((IRaycastContext) raycastContext).meteor$set(vec3d, vec3dRayTraceEnd, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player);
            BlockHitResult result = mc.world.raycast(raycastContext);

            if (result != null && result.getType() == HitResult.Type.BLOCK && result.getBlockPos().equals(blockPos)) {
                return result;
            }
        }

        Direction side = blockPos.getY() > vec3d.y ? Direction.DOWN : Direction.UP;
        return new BlockHitResult(vec3d, side, blockPos, false);
    }

    private void placeCrystal(BlockHitResult result, double damage, BlockPos supportBlock) {
        // Switch
        Item targetItem = supportBlock == null ? Items.END_CRYSTAL : Items.OBSIDIAN;

        FindItemResult item = InvUtils.findInHotbar(targetItem);
        if (!item.found()) return;

        int prevSlot = mc.player.getInventory().selectedSlot;

        if (autoSwitch.get() != AutoSwitchMode.None && !item.isOffhand()) InvUtils.swap(item.slot(), false);

        Hand hand = item.getHand();
        if (hand == null) return;

        // Place
        if (supportBlock == null) {
            // Place crystal
            mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(hand, result, 0));

            if (swingMode.get().client()) mc.player.swingHand(hand);
            if (swingMode.get().packet()) mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(hand));

            placing = true;
            placingTimer = 4;
            kaTimer = 8;
            placingCrystalBlockPos.set(result.getBlockPos()).move(0, 1, 0);

            placeRenderPos.set(result.getBlockPos());
            renderDamage = damage;

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
            // Place support block
            BlockUtils.place(supportBlock, item, false, 0, swingMode.get().client(), true, false);
            placeTimer += supportDelay.get();

            // 乐观更新：立即将快照中该位置标记为黑曜石（不等服务端回包）
            // 这样后台线程下一轮扫描会把此位置视为有效基座
            planner.updateBlock(supportBlock.getX(), supportBlock.getY(), supportBlock.getZ(), 1200.0f);

            if (supportDelay.get() == 0) placeCrystal(result, damage, null);
        }

        // Switch back
        if (autoSwitch.get() == AutoSwitchMode.Silent) InvUtils.swap(prevSlot, false);
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

            for (ItemStack itemStack : target.getArmorItems()) {
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

    private boolean isOutOfRange(Vec3d vec3d, BlockPos blockPos, boolean place) {
        ((IRaycastContext) raycastContext).meteor$set(playerEyePos, vec3d, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player);

        BlockHitResult result = mc.world.raycast(raycastContext);

        if (result == null || !result.getBlockPos().equals(blockPos)) // Is behind wall
            return !PlayerUtils.isWithin(vec3d, (place ? placeWallsRange : breakWallsRange).get());
        return !PlayerUtils.isWithin(vec3d, (place ? placeRange : breakRange).get());
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
                // Smart delay lethal override: break even with hurtTime if damage would kill
                if (!breaking || !smartDelay.get() || target.hurtTime <= 0 || dmg >= EntityUtils.getTotalHealth(target)) {
                    damage = dmg;
                }
            }
        }
        else {
            for (LivingEntity target : targets) {
                float dmg = DamageUtils.crystalDamage(target, vec3d, predictMovement.get(), obsidianPos);

                // Smart delay lethal override: skip hurtTime targets UNLESS this would kill them
                if (breaking && smartDelay.get() && target.hurtTime > 0 && dmg < EntityUtils.getTotalHealth(target)) continue;

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
                        && player.getInventory().armor.get(0).isEmpty()
                        && player.getInventory().armor.get(1).isEmpty()
                        && player.getInventory().armor.get(2).isEmpty()
                        && player.getInventory().armor.get(3).isEmpty()
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

    // 渲染系统

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (renderMode.get() == RenderMode.None) return;

        switch (renderMode.get()) {
            case Normal -> {
                if (renderPlace.get() && placeRenderTimer > 0) {
                    event.renderer.box(placeRenderPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
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

                event.renderer.box(renderBoxOne, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            }

            case Gradient -> {
                if (placeRenderTimer <= 0) return;

                Color bottom = new Color(0, 0, 0, 0);

                int x = placeRenderPos.getX();
                int y = placeRenderPos.getY() + 1;
                int z = placeRenderPos.getZ();

                if (shapeMode.get().sides()) {
                    event.renderer.quadHorizontal(x, y, z, x + 1, z + 1, sideColor.get());
                    event.renderer.gradientQuadVertical(x, y, z, x + 1, y - height.get(), z, bottom, sideColor.get());
                    event.renderer.gradientQuadVertical(x, y, z, x, y - height.get(), z + 1, bottom, sideColor.get());
                    event.renderer.gradientQuadVertical(x + 1, y, z, x + 1, y - height.get(), z + 1, bottom, sideColor.get());
                    event.renderer.gradientQuadVertical(x, y, z + 1, x + 1, y - height.get(), z + 1, bottom, sideColor.get());
                }

                if (shapeMode.get().lines()) {
                    event.renderer.line(x, y, z, x + 1, y, z, lineColor.get());
                    event.renderer.line(x, y, z, x, y, z + 1, lineColor.get());
                    event.renderer.line(x + 1, y, z, x + 1, y, z + 1, lineColor.get());
                    event.renderer.line(x, y, z + 1, x + 1, y, z + 1, lineColor.get());

                    event.renderer.line(x, y, z, x, y - height.get(), z, lineColor.get(), bottom);
                    event.renderer.line(x + 1, y, z, x + 1, y - height.get(), z, lineColor.get(), bottom);
                    event.renderer.line(x, y, z + 1, x, y - height.get(), z + 1, lineColor.get(), bottom);
                    event.renderer.line(x + 1, y, z + 1, x + 1, y - height.get(), z + 1, lineColor.get(), bottom);
                }
            }
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
