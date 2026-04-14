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
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
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
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
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
        .name("目标搜索范围")
        .description("扫描敌对实体的最远距离（格）。目标超出此范围时不纳入伤害评估。")
        .defaultValue(10)
        .min(0)
        .sliderMax(16)
        .build()
    );

    private final Setting<Boolean> predictMovement = sgGeneral.add(new BoolSetting.Builder()
        .name("预测移动")
        .description("将目标的速度向量叠加到当前位置上，预估下一 tick 的实际位置。提高对移动目标的伤害计算精度，减少无效水晶。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> minDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("最低伤害")
        .description("水晶对目标造成的最低伤害阈值。低于此值的放置位置将被跳过。4=更多候选位（减少空 tick），6=更精准（可能空扫描）。贴脸放置时自动降至 1.5。")
        .defaultValue(4)
        .min(0)
        .build()
    );

    private final Setting<Double> maxDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("自伤上限")
        .description("水晶爆炸对自己造成的最大允许伤害。超过此值的位置不会被选中。低 TPS（<18）时自动放宽 15%。")
        .defaultValue(8)
        .range(0, 36)
        .sliderMax(36)
        .build()
    );

    private final Setting<Boolean> antiSuicide = sgGeneral.add(new BoolSetting.Builder()
        .name("防自杀")
        .description("当水晶爆炸伤害大于等于自身总血量（生命+吸收）时，跳过该位置的放置和破坏。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> safetyMargin = sgGeneral.add(new DoubleSetting.Builder()
        .name("安全缓冲血量")
        .description("防自杀余量。自伤后若剩余血量低于此值则拒绝放置/破坏。2.0=一颗心，覆盖射线采样与浮点计算误差。高延迟可增至 4.0。")
        .defaultValue(2.0)
        .min(0.0)
        .sliderMax(10.0)
        .visible(antiSuicide::get)
        .build()
    );

    private final Setting<Double> minDamageRatio = sgGeneral.add(new DoubleSetting.Builder()
        .name("最低伤害交换率")
        .description("每次引爆的 (对敌伤害 / 自身伤害) 最低比例。当自伤 ≥ 1.0 时生效。1.0=炸自己 6 血须至少换对面 6 血。防止无效换血、FacePlace 血亏。设为 0 禁用。")
        .defaultValue(0.8)
        .min(0.0)
        .sliderMax(3.0)
        .build()
    );

    private final Setting<Boolean> ignoreNakeds = sgGeneral.add(new BoolSetting.Builder()
        .name("忽略裸装")
        .description("忽略没有穿戴任何护甲且双手为空的玩家，避免浪费水晶。")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("服务端旋转")
        .description("发送视角旋转包使服务端认为玩家朝向水晶方向。GrimAC RotationPlace/RotationBreak 会验证看向方向，必须开启。")
        .defaultValue(true)
        .build()
    );

    private final Setting<YawStepMode> yawStepMode = sgGeneral.add(new EnumSetting.Builder<YawStepMode>()
        .name("偏航步进模式")
        .description("控制偏航步进（每 tick 限制旋转角度）的生效时机。Break=仅破坏时检查，Both=放置和破坏都检查。GrimAC 不检测转头速度，可设为 Break。")
        .defaultValue(YawStepMode.Break)
        .visible(rotate::get)
        .build()
    );

    private final Setting<Double> yawSteps = sgGeneral.add(new DoubleSetting.Builder()
        .name("偏航步长")
        .description("每 tick 允许的最大偏航旋转角度（度）。GrimAC 不检测转头速度，180°=无限制最快。NCP 服可降低到 45-90。")
        .defaultValue(180)
        .range(1, 180)
        .visible(rotate::get)
        .build()
    );

    private final Setting<Set<EntityType<?>>> entities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("目标实体")
        .description("选择要作为水晶攻击目标的实体类型。仅对此列表中的实体计算伤害并尝试击杀。")
        .onlyAttackable()
        .defaultValue(EntityType.PLAYER, EntityType.WARDEN, EntityType.WITHER)
        .build()
    );

    // Switch

    private final Setting<AutoSwitchMode> autoSwitch = sgSwitch.add(new EnumSetting.Builder<AutoSwitchMode>()
        .name("自动切换")
        .description("发现目标时自动切换到快捷栏上的末地水晶。Normal=普通切换，Silent=服务器端静默切换（GrimAC 不检测），None=不自动切换。Silent 避免视觉切槽干扰，最高效。")
        .defaultValue(AutoSwitchMode.Silent)
        .build()
    );

    private final Setting<Integer> switchDelay = sgSwitch.add(new IntSetting.Builder()
        .name("切换延迟")
        .description("切换快捷栏槽位后等待多少 tick 再破坏水晶。GrimAC 不检测切槽后延迟，设为 0 最快。")
        .defaultValue(0)
        .min(0)
        .build()
    );

    private final Setting<Boolean> noGapSwitch = sgSwitch.add(new BoolSetting.Builder()
        .name("持金苹果不切")
        .description("主手或副手持有金苹果/附魔金苹果时不自动切换，避免打断回血节奏。")
        .defaultValue(true)
        .visible(() -> autoSwitch.get() == AutoSwitchMode.Normal)
        .build()
    );

    private final Setting<Boolean> noBowSwitch = sgSwitch.add(new BoolSetting.Builder()
        .name("持弓不切")
        .description("主手或副手持有弓时不自动切换，避免打断拉弓蓄力。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> antiWeakness = sgSwitch.add(new BoolSetting.Builder()
        .name("反虚弱")
        .description("有虚弱状态效果时自动切换到工具以破坏水晶。虚弱会导致空手攻击无法破坏水晶。")
        .defaultValue(true)
        .build()
    );

    // 放置

    private final Setting<Boolean> doPlace = sgPlace.add(new BoolSetting.Builder()
        .name("启用放置")
        .description("是否自动放置末地水晶。关闭后仅保留破坏流程。")
        .defaultValue(true)
        .build()
    );

    public final Setting<Integer> placeDelay = sgPlace.add(new IntSetting.Builder()
        .name("放置延迟")
        .description("上一次放置后等待多少 tick 再放置下一个水晶。GrimAC MultiPlace 限制 1 放置/tick，0=每 tick 尝试放置（已是极限）。低 TPS 自动+1~2。")
        .defaultValue(0)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Double> placeRange = sgPlace.add(new DoubleSetting.Builder()
        .name("放置范围")
        .description("放置水晶的最远距离（格）。超出此范围的基座位置不会被扫描。")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> placeWallsRange = sgPlace.add(new DoubleSetting.Builder()
        .name("穿墙放置范围")
        .description("透过墙壁放置水晶的最远距离（格）。视线被阻挡时使用此范围而非普通放置范围。GrimAC 无壁检测，与普通范围相同即可。")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Boolean> strictPlaceLOS = sgPlace.add(new BoolSetting.Builder()
        .name("严格视线检查")
        .description("对放置面执行 OUTLINE 射线遮挡检测。GrimAC 仅检查 RotationPlace（看向方块方向），不检查中间遮挡，关闭即可。NCP 服务器建议开启。")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> placement112 = sgPlace.add(new BoolSetting.Builder()
        .name("1.12放置规则")
        .description("使用 1.12 版放置规则：基座上方需要 2 格空间。适用于部分服务器的兼容模式。")
        .defaultValue(false)
        .build()
    );

    private final Setting<SupportMode> support = sgPlace.add(new EnumSetting.Builder<SupportMode>()
        .name("支撑放置")
        .description("找不到直接基座时，在空中先放置黑曜石作为支撑方块。Fast=仅第一目标计算伤害（最快），Normal=所有目标。GrimAC 安全（need supportDelay≥1 避免 MultiPlace）。")
        .defaultValue(SupportMode.Fast)
        .build()
    );

    private final Setting<Integer> supportDelay = sgPlace.add(new IntSetting.Builder()
        .name("支撑延迟")
        .description("放置支撑黑曜石后等待多少 tick 再放置水晶。GrimAC MultiPlace 限 1 放置/tick：=0 同 tick 发 2 包→IMMEDIATE FLAG；≥1 安全。1=最优（cost: 1 extra tick per support cycle）。")
        .defaultValue(1)
        .min(0)
        .visible(() -> support.get() != SupportMode.Disabled)
        .build()
    );

    private final Setting<Boolean> supportSafePlacement = sgPlace.add(new BoolSetting.Builder()
        .name("支撑安全放置")
        .description("使用打印机的 NCP/LOS 安全放置系统放置支撑方块。确保点击方向、点击坐标和视线等透过反作弊检测。")
        .defaultValue(true)
        .visible(() -> support.get() != SupportMode.Disabled)
        .build()
    );

    private final Setting<Double> supportDiscount = sgPlace.add(new DoubleSetting.Builder()
        .name("支撑折扣")
        .description("支撑方案的伤害折扣系数。例如 0.3 表示支撑伤害打 7 折后再与直接放置比较。值越高越不倾向放黑曜石，0=无折扣。")
        .defaultValue(0.3)
        .min(0.0)
        .max(0.9)
        .sliderRange(0.0, 0.9)
        .visible(() -> support.get() != SupportMode.Disabled)
        .build()
    );

    // 贴脸放置

    private final Setting<Boolean> facePlace = sgFacePlace.add(new BoolSetting.Builder()
        .name("启用贴脸")
        .description("目标血量或护甲耐久低于阈值时自动启用贴脸放置。贴脸模式下最低伤害阈值降至 1.5，使难以伤害但紧贴的位置也被接受。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> facePlaceHealth = sgFacePlace.add(new DoubleSetting.Builder()
        .name("血量阈值")
        .description("目标总血量（生命+吸收）低于此值时触发贴脸放置。")
        .defaultValue(8)
        .min(1)
        .sliderMin(1)
        .sliderMax(36)
        .visible(facePlace::get)
        .build()
    );

    private final Setting<Double> facePlaceDurability = sgFacePlace.add(new DoubleSetting.Builder()
        .name("耐久阈值")
        .description("目标任一护甲部件耐久度低于此百分比时触发贴脸放置。例如 2表示护甲耐久低于 2% 时触发。")
        .defaultValue(2)
        .min(1)
        .sliderMin(1)
        .sliderMax(100)
        .visible(facePlace::get)
        .build()
    );

    private final Setting<Boolean> facePlaceArmor = sgFacePlace.add(new BoolSetting.Builder()
        .name("缺少护甲触发")
        .description("目标缺少任一护甲部件（头盔/胸甲/护腿/靴子）时自动启用贴脸放置。缺护甲=极脆弱，降低伤害阈值到 1.5 可覆盖更多放置位置。")
        .defaultValue(true)
        .visible(facePlace::get)
        .build()
    );

    private final Setting<Keybind> forceFacePlace = sgFacePlace.add(new KeybindSetting.Builder()
        .name("强制贴脸键")
        .description("按住此键时强制启用贴脸放置，无视血量/耐久阈值。")
        .defaultValue(Keybind.none())
        .build()
    );

    // 破坏

    private final Setting<Boolean> doBreak = sgBreak.add(new BoolSetting.Builder()
        .name("启用破坏")
        .description("是否自动攻击破坏末地水晶。关闭后仅保留放置流程。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> breakDelay = sgBreak.add(new IntSetting.Builder()
        .name("破坏延迟")
        .description("水晶放置后等待多少 tick 再尝试破坏。GrimAC 无此限制，0=最快。但 PacketOrderI 禁止同 tick attack+place，attackedThisTick 已守护。")
        .defaultValue(0)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> smartDelay = sgBreak.add(new BoolSetting.Builder()
        .name("智能延迟")
        .description("仅在目标无受伤 CD（hurtTime=0）时才破坏水晶。严格遵守无敌帧——因为客户端无法获取 lastDamageTaken，在 hurtTime 期间攻击大概率被服务端豁免或只造成极低差值伤害。等 hurtTime 自然过期（~10 tick）后攻击，确保每颗水晶全额命中。")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> breakRange = sgBreak.add(new DoubleSetting.Builder()
        .name("破坏范围")
        .description("破坏水晶的最远距离（格）。Grim Reach 检测纯距离: 眼→AABB 表面 ≤ 3.03。水晶 2×2×2 AABB → 中心距有效极限 ~4.0。")
        .defaultValue(4.0)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> breakWallsRange = sgBreak.add(new DoubleSetting.Builder()
        .name("穿墙破坏范围")
        .description("透过墙壁破坏水晶的最远距离（格）。GrimAC 无壁检测，与普通范围相同即可；NCP 服务器可调低。")
        .defaultValue(4.0)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Boolean> onlyBreakOwn = sgBreak.add(new BoolSetting.Builder()
        .name("仅破自己的")
        .description("仅破坏自己放置的水晶。避免触发别人的陷阱水晶。")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> breakAttempts = sgBreak.add(new IntSetting.Builder()
        .name("最大攻击次数")
        .description("对同一颗水晶的最大攻击尝试次数。GrimAC MultiInteractA 限 1 entity/tick，多次尝试浪费 tick。1=最高效（丢包后下 tick 扫描重试），高延迟服可设 2。")
        .defaultValue(1)
        .sliderMin(1)
        .sliderMax(5)
        .build()
    );

    private final Setting<Integer> ticksExisted = sgBreak.add(new IntSetting.Builder()
        .name("最小存在时间")
        .description("水晶需要存在至少多少 tick 后才能被攻击。GrimAC 无此限制。设 0=生成即攻击，最大化 DPS。")
        .defaultValue(0)
        .min(0)
        .build()
    );

    private final Setting<Integer> attackFrequency = sgBreak.add(new IntSetting.Builder()
        .name("每秒攻击上限")
        .description("每秒允许的最大水晶攻击次数。GrimAC 无此限制（无 CPS 检测），但过高会加重服务器负载。")
        .defaultValue(25)
        .min(1)
        .sliderRange(1, 30)
        .build()
    );

    private final Setting<Boolean> fastBreak = sgBreak.add(new BoolSetting.Builder()
        .name("新生即破")
        .description("水晶实体生成后立即尝试破坏，无视破坏延迟设置。GrimAC 不检测攻击时机，开启可最大化 DPS。")
        .defaultValue(true)
        .build()
    );

    // 暂停

    public final Setting<PauseMode> pauseOnUse = sgPause.add(new EnumSetting.Builder<PauseMode>()
        .name("使用物品时暂停")
        .description("玩家使用物品（如吃金苹果、拉弓）时暂停哪些流程。Place=仅暂停放置，Both=放置+破坏，None=不暂停。")
        .defaultValue(PauseMode.Place)
        .build()
    );

    public final Setting<PauseMode> pauseOnMine = sgPause.add(new EnumSetting.Builder<PauseMode>()
        .name("挖掘时暂停")
        .description("玩家挖掘方块时暂停哪些流程。配合 PacketMine 等模块使用时建议设为 None。")
        .defaultValue(PauseMode.None)
        .build()
    );

    private final Setting<Boolean> pauseOnLag = sgPause.add(new BoolSetting.Builder()
        .name("卡顿时暂停")
        .description("服务器无响应（TPS 极低）时暂停所有流程。避免在丢包环境下发送大量无效包。")
        .defaultValue(true)
        .build()
    );

    public final Setting<List<Module>> pauseModules = sgPause.add(new ModuleListSetting.Builder()
        .name("暂停模块")
        .description("任一已选模块处于激活状态时暂停水晶光环。默认包含 BedAura，避免两个战斗模块冲突。")
        .defaultValue(BedAura.class)
        .build()
    );

    public final Setting<Double> pauseHealth = sgPause.add(new DoubleSetting.Builder()
        .name("低血暂停")
        .description("自身总血量低于此值时暂停所有流程。保命优先，避免低血时放置水晶自爆。")
        .defaultValue(5)
        .range(0,36)
        .sliderRange(0,36)
        .build()
    );

    // 渲染

    public final Setting<SwingMode> swingMode = sgRender.add(new EnumSetting.Builder<SwingMode>()
        .name("挥手方式")
        .description("放置水晶时的挥手动画方式。Both=客户端+服务端，Client=仅客户端，Server=仅服务端，None=无。")
        .defaultValue(SwingMode.Both)
        .build()
    );

    private final Setting<RenderMode> renderMode = sgRender.add(new EnumSetting.Builder<RenderMode>()
        .name("渲染模式")
        .description("水晶放置/破坏位置的视觉反馈模式。Normal=标准方块高亮，Smooth=平滑过渡，Fading=淡入淡出，Gradient=渐变，None=关闭。")
        .defaultValue(RenderMode.Normal)
        .build()
    );

    private final Setting<Boolean> renderPlace = sgRender.add(new BoolSetting.Builder()
        .name("渲染放置")
        .description("在放置水晶的基座方块上渲染半透明覆盖层。")
        .defaultValue(true)
        .visible(() -> renderMode.get() == RenderMode.Normal)
        .build()
    );

    private final Setting<Integer> placeRenderTime = sgRender.add(new IntSetting.Builder()
        .name("放置渲染时长")
        .description("放置覆盖层持续显示的 tick 数。")
        .defaultValue(10)
        .min(0)
        .sliderMax(20)
        .visible(() -> renderMode.get() == RenderMode.Normal && renderPlace.get())
        .build()
    );

    private final Setting<Boolean> renderBreak = sgRender.add(new BoolSetting.Builder()
        .name("渲染破坏")
        .description("在破坏水晶的方块位置渲染半透明覆盖层。")
        .defaultValue(false)
        .visible(() -> renderMode.get() == RenderMode.Normal)
        .build()
    );

    private final Setting<Integer> breakRenderTime = sgRender.add(new IntSetting.Builder()
        .name("破坏渲染时长")
        .description("破坏覆盖层持续显示的 tick 数。")
        .defaultValue(13)
        .min(0)
        .sliderMax(20)
        .visible(() -> renderMode.get() == RenderMode.Normal && renderBreak.get())
        .build()
    );

    private final Setting<Integer> smoothness = sgRender.add(new IntSetting.Builder()
        .name("平滑度")
        .description("平滑渲染模式下的过渡平滑度。值越高过渡越柔和。")
        .defaultValue(10)
        .min(0)
        .sliderMax(20)
        .visible(() -> renderMode.get() == RenderMode.Smooth)
        .build()
    );

    private final Setting<Double> height = sgRender.add(new DoubleSetting.Builder()
        .name("渐变高度")
        .description("渐变渲染模式下覆盖层的高度。值越大覆盖层越高。")
        .defaultValue(0.7)
        .min(0)
        .sliderMax(1)
        .visible(() -> renderMode.get() == RenderMode.Gradient)
        .build()
    );

    private final Setting<Integer> renderTime = sgRender.add(new IntSetting.Builder()
        .name("渲染时长")
        .description("平滑/淡出渲染模式下覆盖层持续显示的 tick 数。")
        .defaultValue(10)
        .min(0)
        .sliderMax(20)
        .visible(() -> renderMode.get() == RenderMode.Smooth || renderMode.get() == RenderMode.Fading)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("形状模式")
        .description("覆盖层的形状渲染方式。Both=侧面+边线，Sides=仅侧面，Lines=仅边线。")
        .defaultValue(ShapeMode.Both)
        .visible(() -> renderMode.get() != RenderMode.None)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("侧面颜色")
        .description("覆盖层侧面填充的 RGBA 颜色。")
        .defaultValue(new SettingColor(255, 255, 255, 45))
        .visible(() -> shapeMode.get().sides() && renderMode.get() != RenderMode.None)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("边线颜色")
        .description("覆盖层边线的 RGBA 颜色。")
        .defaultValue(new SettingColor(255, 255, 255))
        .visible(() -> shapeMode.get().lines() && renderMode.get() != RenderMode.None)
        .build()
    );

    private final Setting<SettingColor> supportSideColor = sgRender.add(new ColorSetting.Builder()
        .name("支撑侧面颜色")
        .description("Support 候选（待放黑曜石位置）侧面填充的 RGBA 颜色。")
        .defaultValue(new SettingColor(255, 170, 0, 45))
        .visible(() -> shapeMode.get().sides() && renderMode.get() != RenderMode.None && support.get() != SupportMode.Disabled)
        .build()
    );

    private final Setting<SettingColor> supportLineColor = sgRender.add(new ColorSetting.Builder()
        .name("支撑边线颜色")
        .description("Support 候选（待放黑曜石位置）边线的 RGBA 颜色。")
        .defaultValue(new SettingColor(255, 170, 0))
        .visible(() -> shapeMode.get().lines() && renderMode.get() != RenderMode.None && support.get() != SupportMode.Disabled)
        .build()
    );

    private final Setting<Boolean> renderDamageText = sgRender.add(new BoolSetting.Builder()
        .name("显示伤害数值")
        .description("在覆盖层上方显示水晶对目标的预估伤害数值。")
        .defaultValue(true)
        .visible(() -> renderMode.get() != RenderMode.None)
        .build()
    );

    private final Setting<SettingColor> damageColor = sgRender.add(new ColorSetting.Builder()
        .name("伤害文字颜色")
        .description("伤害数值文字的 RGBA 颜色。")
        .defaultValue(new SettingColor(255, 255, 255))
        .visible(() -> renderMode.get() != RenderMode.None && renderDamageText.get())
        .build()
    );

    private final Setting<Double> damageTextScale = sgRender.add(new DoubleSetting.Builder()
        .name("伤害文字大小")
        .description("伤害数值文字的缩放比例。默认 1.25，值越大文字越明显。")
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

        // F4: attackedThisTick 在 tick 末尾重置
        // fastBreak (EntityAddedEvent 可能在 tick 前触发) 设置的标志
        // 在 doBreak + doPlace 期间保持有效，防止同 tick 重复攻击/攻击+放置
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
                Rotations.rotateToward(crystal.getPos(), 50, () -> attackCrystal(crystal));

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
        hasProposal = false;
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

        if (planner.isBusy()) return;

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
        float selfDamage = DamageUtils.crystalDamage(mc.player, vec3d, false, proposalPos);
        float effectiveMaxDmg = maxDamage.get().floatValue();
        if (TickRate.INSTANCE.getTickRate() < 18) effectiveMaxDmg *= 0.85f;
        if (selfDamage > effectiveMaxDmg || (antiSuicide.get() && selfDamage >= (EntityUtils.getTotalHealth(mc.player) - safetyMargin.get()))) return false;

        // Target damage still meets threshold?
        float damage = getDamageToTargets(vec3d, proposalPos, false, proposalIsSupport && support.get() == SupportMode.Fast);
        double minimumDamage = shouldFacePlace() ? Math.min(minDamage.get(), 1.5) : minDamage.get();
        if (damage < minimumDamage) return false;

        // Damage ratio check
        if (minDamageRatio.get() > 0 && selfDamage >= 1.0f && damage / selfDamage < minDamageRatio.get()) return false;

        // Entity intersection — support 时额外检查 Y 层（黑曜石放置位置）
        if (proposalIsSupport) {
            double sx = proposalPos.getX(), sy = proposalPos.getY(), sz = proposalPos.getZ();
            ((IBox) box).meteor$set(sx, sy, sz, sx + 1, sy + 1, sz + 1);
            if (intersectsWithEntities(box)) return false;
        }

        double x = proposalPos.getX(), y = proposalPos.getY() + 1, z = proposalPos.getZ();
        ((IBox) box).meteor$set(x, y, z, x + 1, y + (placement112.get() ? 1 : 2), z + 1);
        if (intersectsWithEntities(box)) return false;

        proposalDamage = damage;
        return true;
    }

    /**
     * 尝试执行当前 proposal —— LOS/support 实时重验 + 旋转/放置。
     * @return true = 已安排动作（rotation scheduled 或直接放置/yawStep 推进），false = 硬失败
     */
    private boolean executeProposal() {
        BlockHitResult result = resolveCrystalHit(proposalPos);
        if (result == null) {
            hasProposal = false;
            return false;
        }

        // 修正 1: support 候选必须通过 obsidian resolver 预验证, 保存 plan 供旋转使用
        SupportPlan supportPlan = proposalIsSupport ? resolveSupportHit(proposalPos) : null;
        if (proposalIsSupport && supportPlan == null) {
            hasProposal = false;
            return false;
        }

        // crystal hit + support 预验证均通过后才更新渲染 —— 保证橙框=真的能执行
        updateRenderCandidate(proposalPos, proposalDamage, proposalIsSupport);

        BlockPos supportBlock = proposalIsSupport ? proposalPos.toImmutable() : null;

        // F8: support 时旋转目标必须朝向邻居方块放置面 (与 placeSupportSafe 发包方向一致)
        // 非 support 时朝向水晶放置面
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

            if (yawStepMode.get() == YawStepMode.Break || doYawSteps(yaw, pitch)) {
                setRotation(true, vec3d, 0, 0);
                Vec3d hitTarget = new Vec3d(vec3d.x, vec3d.y, vec3d.z);
                // W2 修正: placeTimer 仅在 placeCrystal 成功时递增
                Rotations.rotateToward(hitTarget, 50, () -> {
                    if (!hasProposal) return; // proposal 已被后续 tick 失效
                    if (placeCrystal(result, proposalDamage, supportBlock) && supportBlock == null)
                        placeTimer += getEffectivePlaceDelay();
                });
            }
            // yawStep 阻塞时 doYawSteps 已发送步进旋转包 → 属于有效动作
        } else {
            if (placeCrystal(result, proposalDamage, supportBlock) && supportBlock == null)
                placeTimer += getEffectivePlaceDelay();
        }
        return true;
    }

    // 放置流程

    private void doPlace() {
        if (!doPlace.get() || placeTimer > 0) return;
        // F3: 本 tick 已攻击则跳过放置 —— 避免同 tick attack+place 触发 Grim PacketOrderI/J
        if (attackedThisTick) return;
        if (shouldPause(PauseMode.Place)) return;

        // 等待水晶生成确认时不发送冗余放置包
        // RusherHack/mio 模式：低 TPS 时零浪费包
        if (placing && placingTimer > 0) return;

        // Return if there are no crystals in hotbar or offhand
        if (!InvUtils.testInHotbar(Items.END_CRYSTAL)) return;

        // 修正 3: Support 早期物品检查 —— 无黑曜石时 support 全链路不触发
        boolean supportAvailable = support.get() != SupportMode.Disabled && InvUtils.testInHotbar(Items.OBSIDIAN);

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

        // === Async 结果消费 → 仅写入 proposal（不直接放置，伤害排序基于过时快照） ===
        CrystalPlanner.ResultSet asyncRes = planner.consumeResult();
        if (asyncRes != null) {
            CrystalPlanner.PlaceResult directRes = asyncRes.direct();
            CrystalPlanner.PlaceResult supportRes = asyncRes.support();

            CrystalPlanner.PlaceResult chosen = null;
            boolean isSup = false;
            if (directRes != null && supportRes != null && supportAvailable) {
                isSup = shouldPreferSupport(supportRes.damage(), directRes.damage());
                chosen = isSup ? supportRes : directRes;
            } else if (directRes != null) {
                chosen = directRes;
            } else if (supportAvailable) {
                chosen = supportRes;
                isSup = true;
            }

            if (chosen != null) {
                proposalPos.set(chosen.x(), chosen.y(), chosen.z());
                proposalIsSupport = isSup;
                proposalDamage = chosen.damage();
                hasProposal = true;
                proposalAge = 0;
            }
        }

        // Proposal 快速路径：实时重算伤害 + 实时 LOS 验证（2 次伤害计算 vs 80+）
        if (hasProposal && proposalAge < 6) {
            if (quickValidateProposal()) {
                proposalAge++;
                // W1 修正: executeProposal 成功才跳过同步扫描；失败则回落全量扫描
                if (executeProposal()) {
                    captureAndSubmitAsyncScan();
                    return;
                }
                // proposal LOS/support 不可达 → 回落同步扫描（本 tick 不浪费）
            } else {
                hasProposal = false;
            }
        }

        // 提交下一轮 async 扫描（结果仅作为 proposal seed）
        captureAndSubmitAsyncScan();

        // 同步扫描：本 tick 实时数据全量扫描，保证全局最优可达位置
        final AtomicDouble bestDirectDamage = new AtomicDouble(0);
        final AtomicDouble bestSupportDamage = new AtomicDouble(0);
        final AtomicReference<BlockHitResult> bestDirectHit = new AtomicReference<>();
        final AtomicReference<BlockHitResult> bestSupportHit = new AtomicReference<>();
        final AtomicReference<BlockPos> bestDirectPos = new AtomicReference<>();
        final AtomicReference<BlockPos> bestSupportPos = new AtomicReference<>();

        refreshBaseCacheIfNeeded();

        BlockIterator.register((int) Math.ceil(placeRange.get()), (int) Math.ceil(placeRange.get()), (bp, blockState) -> {
            if (!validBasePosCache.contains(BlockPos.asLong(bp.getX(), bp.getY(), bp.getZ()))) return;

            boolean hasBlock = blockState.isOf(Blocks.BEDROCK) || blockState.isOf(Blocks.OBSIDIAN);

            ((IVec3d) vec3d).meteor$set(bp.getX() + 0.5, bp.getY() + 1, bp.getZ() + 0.5);
            blockPos.set(bp).move(0, 1, 0);
            if (isOutOfRange(vec3d, blockPos, true)) return;

            // LOS/NCP 实时验证 —— 不可达则跳过
            BlockHitResult hit = resolveCrystalHit(bp);
            if (hit == null) return;

            // 自伤检测
            float selfDamage = DamageUtils.crystalDamage(mc.player, vec3d, false, bp);
            float effectiveMaxDmg = maxDamage.get().floatValue();
            if (TickRate.INSTANCE.getTickRate() < 18) effectiveMaxDmg *= 0.85f;
            if (selfDamage > effectiveMaxDmg || (antiSuicide.get() && selfDamage >= (EntityUtils.getTotalHealth(mc.player) - safetyMargin.get()))) return;

            // 目标伤害
            float damage = getDamageToTargets(vec3d, bp, false, !hasBlock && support.get() == SupportMode.Fast);
            boolean shouldFacePlace = shouldFacePlace();
            double minimumDamage = Math.min(minDamage.get(), shouldFacePlace ? 1.5 : minDamage.get());
            if (damage < minimumDamage) return;

            // Damage ratio check
            if (minDamageRatio.get() > 0 && selfDamage >= 1.0f && damage / selfDamage < minDamageRatio.get()) return;

            // 碰撞检测
            double x = bp.getX();
            double y = bp.getY() + 1;
            double z = bp.getZ();
            ((IBox) box).meteor$set(x, y, z, x + 1, y + (placement112.get() ? 1 : 2), z + 1);
            if (intersectsWithEntities(box)) return;

            if (hasBlock) {
                if (damage > bestDirectDamage.get()) {
                    bestDirectDamage.set(damage);
                    bestDirectHit.set(hit);
                    bestDirectPos.set(bp.toImmutable());
                }
            } else if (supportAvailable) {
                // 修正 1: support 候选必须通过与执行相同的 obsidian resolver 预验证
                if (resolveSupportHit(bp) == null) return;
                if (damage > bestSupportDamage.get()) {
                    bestSupportDamage.set(damage);
                    bestSupportHit.set(hit);
                    bestSupportPos.set(bp.toImmutable());
                }
            }
        });

        // 放置 —— 同步扫描的全局最优结果，已 LOS 验证
        BlockIterator.after(() -> {
            boolean isSup = false;
            BlockHitResult result;
            BlockPos pos;
            double dmg;

            if (bestDirectPos.get() != null && bestSupportPos.get() != null && supportAvailable) {
                isSup = shouldPreferSupport(bestSupportDamage.get(), bestDirectDamage.get());
            } else if (bestDirectPos.get() == null && bestSupportPos.get() != null && supportAvailable) {
                isSup = true;
            }

            if (isSup) {
                result = bestSupportHit.get();
                pos = bestSupportPos.get();
                dmg = bestSupportDamage.get();
            } else {
                result = bestDirectHit.get();
                pos = bestDirectPos.get();
                dmg = bestDirectDamage.get();
            }

            if (result == null || pos == null) return;

            // 同步扫描的 support 候选已在 BlockIterator 回调中通过 resolveSupportHit 验证
            // 故此处渲染 = 真的能执行
            updateRenderCandidate(pos, dmg, isSup);

            proposalPos.set(pos);
            proposalIsSupport = isSup;
            proposalDamage = dmg;
            hasProposal = true;
            proposalAge = 0;

            BlockPos supportBlock = isSup ? pos : null;

            // F8: support 时旋转目标必须朝向邻居方块放置面 (与 placeSupportSafe 发包方向一致)
            SupportPlan syncSupportPlan = isSup ? resolveSupportHit(pos) : null;
            if (isSup && syncSupportPlan == null) return; // support 放置面消失

            if (syncSupportPlan != null) {
                ((IVec3d) vec3d).meteor$set(syncSupportPlan.hitVec().x, syncSupportPlan.hitVec().y, syncSupportPlan.hitVec().z);
            } else {
                ((IVec3d) vec3d).meteor$set(
                    result.getBlockPos().getX() + 0.5 + result.getSide().getVector().getX() * 0.5,
                    result.getBlockPos().getY() + 0.5 + result.getSide().getVector().getY() * 0.5,
                    result.getBlockPos().getZ() + 0.5 + result.getSide().getVector().getZ() * 0.5);
            }

            if (rotate.get()) {
                double yaw = Rotations.getYaw(vec3d);
                double pitch = Rotations.getPitch(vec3d);
                if (yawStepMode.get() == YawStepMode.Break || doYawSteps(yaw, pitch)) {
                    setRotation(true, vec3d, 0, 0);
                    Vec3d hitTarget = new Vec3d(vec3d.x, vec3d.y, vec3d.z);
                    // W2 修正: placeTimer 仅在 placeCrystal 成功时递增
                    Rotations.rotateToward(hitTarget, 50, () -> {
                        if (placeCrystal(result, dmg, supportBlock) && supportBlock == null)
                            placeTimer += getEffectivePlaceDelay();
                    });
                }
            } else {
                if (placeCrystal(result, dmg, supportBlock) && supportBlock == null)
                    placeTimer += getEffectivePlaceDelay();
            }
        });
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

        int prevSlot = restoreSlot >= 0 ? restoreSlot : mc.player.getInventory().selectedSlot;

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
                if (!breaking || !smartDelay.get() || target.hurtTime <= 0) {
                    damage = dmg;
                }
            }
        }
        else {
            for (LivingEntity target : targets) {
                float dmg = DamageUtils.crystalDamage(target, vec3d, predictMovement.get(), obsidianPos);

                // smartDelay: hurtTime > 0 时严格跳过 (客户端不知 lastDamageTaken, 不做致死赌博)
                if (breaking && smartDelay.get() && target.hurtTime > 0) continue;

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
