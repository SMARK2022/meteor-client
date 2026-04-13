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
        .description("将目标的速度向量叠加到当前位置上，预估下一 tick 的实际位置。对高速移动的目标可显著提高伤害计算精度。")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> minDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("最低伤害")
        .description("水晶对目标造成的最低伤害阈值。低于此值的放置位置将被跳过。贴脸放置时此阈值会自动降至 1.5。")
        .defaultValue(6)
        .min(0)
        .build()
    );

    private final Setting<Double> maxDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("自伤上限")
        .description("水晶爆炸对自己造成的最大允许伤害。超过此值的位置不会被选中。低 TPS（<18）时自动放宽 15%。")
        .defaultValue(6)
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

    private final Setting<Boolean> ignoreNakeds = sgGeneral.add(new BoolSetting.Builder()
        .name("忽略裸装")
        .description("忽略没有穿戴任何护甲且双手为空的玩家，避免浪费水晶。")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("服务端旋转")
        .description("发送视角旋转包使服务端认为玩家朝向水晶方向。关闭后放置/破坏不发送旋转包，可能被反作弊检测。")
        .defaultValue(true)
        .build()
    );

    private final Setting<YawStepMode> yawStepMode = sgGeneral.add(new EnumSetting.Builder<YawStepMode>()
        .name("偏航步进模式")
        .description("控制偏航步进（每 tick 限制旋转角度）的生效时机。Break=仅破坏时检查，Both=放置和破坏都检查。")
        .defaultValue(YawStepMode.Break)
        .visible(rotate::get)
        .build()
    );

    private final Setting<Double> yawSteps = sgGeneral.add(new DoubleSetting.Builder()
        .name("偏航步长")
        .description("每 tick 允许的最大偏航旋转角度（度）。180°=无限制；较低值可绕过部分反作弊的转头速度检测。")
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
        .description("发现目标时自动切换到快捷栏上的末地水晶。Normal=普通切换，Silent=服务器端静默切换，None=不自动切换。")
        .defaultValue(AutoSwitchMode.Normal)
        .build()
    );

    private final Setting<Integer> switchDelay = sgSwitch.add(new IntSetting.Builder()
        .name("切换延迟")
        .description("切换快捷栏槽位后等待多少 tick 再破坏水晶。用于避免部分反作弊的切槽后立即攻击检测。")
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
        .description("上一次放置后等待多少 tick 再放置下一个水晶。值越大放置节奏越慢，低 TPS 时会自动额外加 1-2 tick。")
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
        .description("透过墙壁放置水晶的最远距离（格）。视线被阻挡时使用此范围而非普通放置范围。")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Boolean> strictPlaceLOS = sgPlace.add(new BoolSetting.Builder()
        .name("严格视线检查")
        .description("对放置面执行 OUTLINE 射线遮挡检测。关闭后仅检查距离和 NCP 方向（与 GrimAC 检测等级对齐），大幅增加可放置位置。")
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
        .description("找不到直接基座时，在空中先放置黑曜石作为支撑方块。Disabled=关闭，Fast=仅对第一目标计算伤害，Normal=对所有目标计算。")
        .defaultValue(SupportMode.Disabled)
        .build()
    );

    private final Setting<Integer> supportDelay = sgPlace.add(new IntSetting.Builder()
        .name("支撑延迟")
        .description("放置支撑黑曜石后等待多少 tick 再放置水晶。用于等待服务器确认方块放置。")
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
        .description("目标缺少任一护甲部件（头盔/胸甲/护腿/靴子）时自动启用贴脸放置。")
        .defaultValue(false)
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
        .description("水晶放置后等待多少 tick 再尝试破坏。可用于规避部分反作弊的交互节奏检测。")
        .defaultValue(0)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> smartDelay = sgBreak.add(new BoolSetting.Builder()
        .name("智能延迟")
        .description("仅在目标无受伤 CD（hurtTime=0）时才破坏水晶，除非爆炸能击杀目标。避免浪费水晶在目标无敌帧上。")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> breakRange = sgBreak.add(new DoubleSetting.Builder()
        .name("破坏范围")
        .description("破坏水晶的最远距离（格）。")
        .defaultValue(4.5)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> breakWallsRange = sgBreak.add(new DoubleSetting.Builder()
        .name("穿墙破坏范围")
        .description("透过墙壁破坏水晶的最远距离（格）。视线被阵挡时使用此范围。")
        .defaultValue(4.5)
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
        .description("对同一颗水晶的最大攻击尝试次数。超过此数后放弃该水晶，避免对已经爆炸但未消失的水晶重复攻击。")
        .defaultValue(2)
        .sliderMin(1)
        .sliderMax(5)
        .build()
    );

    private final Setting<Integer> ticksExisted = sgBreak.add(new IntSetting.Builder()
        .name("最小存在时间")
        .description("水晶需要存在至少多少 tick 后才能被攻击。用于避免攻击刚生成但未被服务器确认的幽灵水晶。")
        .defaultValue(0)
        .min(0)
        .build()
    );

    private final Setting<Integer> attackFrequency = sgBreak.add(new IntSetting.Builder()
        .name("每秒攻击上限")
        .description("每秒允许的最大水晶攻击次数。防止因过快攻击被反作弊检测。默认 25 次/秒。")
        .defaultValue(25)
        .min(1)
        .sliderRange(1, 30)
        .build()
    );

    private final Setting<Boolean> fastBreak = sgBreak.add(new BoolSetting.Builder()
        .name("新生即破")
        .description("水晶实体生成后立即尝试破坏，无视破坏延迟设置。在击杀型伤害时还会无视智能延迟。")
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
        CrystalPlanner.TargetSnap selfSnap = CrystalPlanner.snapshotTarget(mc.player, predict);

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
        BlockHitResult result = resolveCrystalHit(proposalPos);
        if (result == null) {
            hasProposal = false;
            return;
        }

        // LOS 验证通过后才更新渲染位置 —— 保证白框只出现在可达位置
        updateRenderCandidate(proposalPos, proposalDamage, proposalIsSupport);

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
                Vec3d hitTarget = new Vec3d(vec3d.x, vec3d.y, vec3d.z);
                Rotations.rotateToward(hitTarget, 50, () -> placeCrystal(result, proposalDamage, supportBlock));
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

        // === Async 结果消费 → 仅写入 proposal（不直接放置，伤害排序基于过时快照） ===
        CrystalPlanner.PlaceResult directRes = planner.getDirectResult();
        CrystalPlanner.PlaceResult supportRes = planner.getSupportResult();
        if (directRes != null || supportRes != null) {
            planner.clearResults();
            boolean supportEnabled = support.get() != SupportMode.Disabled;

            CrystalPlanner.PlaceResult chosen = null;
            boolean isSup = false;
            if (directRes != null && supportRes != null && supportEnabled) {
                isSup = shouldPreferSupport(supportRes.damage(), directRes.damage());
                chosen = isSup ? supportRes : directRes;
            } else if (directRes != null) {
                chosen = directRes;
            } else if (supportEnabled) {
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
        if (hasProposal && proposalAge < 3) {
            if (quickValidateProposal()) {
                proposalAge++;
                // 渲染移至 executeProposal 内 resolveCrystalHit 通过后，避免渲染不可达位置
                executeProposal();
                captureAndSubmitAsyncScan();
                return;
            }
            hasProposal = false;
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
        boolean supportEnabled = support.get() != SupportMode.Disabled;

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
            float selfDamage = DamageUtils.crystalDamage(mc.player, vec3d, predictMovement.get(), bp);
            float effectiveMaxDmg = maxDamage.get().floatValue();
            if (TickRate.INSTANCE.getTickRate() < 18) effectiveMaxDmg *= 0.85f;
            if (selfDamage > effectiveMaxDmg || (antiSuicide.get() && selfDamage >= EntityUtils.getTotalHealth(mc.player))) return;

            // 目标伤害
            float damage = getDamageToTargets(vec3d, bp, false, !hasBlock && support.get() == SupportMode.Fast);
            boolean shouldFacePlace = shouldFacePlace();
            double minimumDamage = Math.min(minDamage.get(), shouldFacePlace ? 1.5 : minDamage.get());
            if (damage < minimumDamage) return;

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
            } else {
                if (damage > bestSupportDamage.get()) {
                    bestSupportDamage.set(damage);
                    bestSupportHit.set(hit);
                    bestSupportPos.set(bp.toImmutable());
                }
            }
        });

        // 放置 —— 同步扫描的全局最优结果，已 LOS 验证
        BlockIterator.after(() -> {
            if (attackedThisTick) return;

            boolean isSup = false;
            BlockHitResult result;
            BlockPos pos;
            double dmg;

            if (bestDirectPos.get() != null && bestSupportPos.get() != null && supportEnabled) {
                isSup = shouldPreferSupport(bestSupportDamage.get(), bestDirectDamage.get());
            } else if (bestDirectPos.get() == null && bestSupportPos.get() != null && supportEnabled) {
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

            // 实时候选渲染 —— 同步扫描找到最优位置时立即渲染（不等放置成功）
            updateRenderCandidate(pos, dmg, isSup);

            proposalPos.set(pos);
            proposalIsSupport = isSup;
            proposalDamage = dmg;
            hasProposal = true;
            proposalAge = 0;

            BlockPos supportBlock = isSup ? pos : null;

            ((IVec3d) vec3d).meteor$set(
                result.getBlockPos().getX() + 0.5 + result.getSide().getVector().getX() * 0.5,
                result.getBlockPos().getY() + 0.5 + result.getSide().getVector().getY() * 0.5,
                result.getBlockPos().getZ() + 0.5 + result.getSide().getVector().getZ() * 0.5);

            if (rotate.get()) {
                double yaw = Rotations.getYaw(vec3d);
                double pitch = Rotations.getPitch(vec3d);
                if (yawStepMode.get() == YawStepMode.Break || doYawSteps(yaw, pitch)) {
                    setRotation(true, vec3d, 0, 0);
                    Vec3d hitTarget = new Vec3d(vec3d.x, vec3d.y, vec3d.z);
                    Rotations.rotateToward(hitTarget, 50, () -> placeCrystal(result, dmg, supportBlock));
                    placeTimer += getEffectivePlaceDelay();
                }
            } else {
                placeCrystal(result, dmg, supportBlock);
                placeTimer += getEffectivePlaceDelay();
            }
        });
    }

    /**
     * 安全解析水晶放置的 HitResult —— NCP 方向检查 + LOS 视线检查 + 距离检查。
     * 优先尝试 UP（最常用的基座顶面），然后其余方向。
     *
     * @return 验证通过的 BlockHitResult，不可放置时返回 null
     */
    private BlockHitResult resolveCrystalHit(BlockPos blockPos) {
        Vec3d eyePos = new Vec3d(mc.player.getX(), mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()), mc.player.getZ());
        double reach = placeRange.get();

        // 优先序：UP（最常见的水晶放置面）→ 其余
        Direction[] tryOrder = { Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN };

        for (Direction face : tryOrder) {
            Vec3d hitVec = new Vec3d(
                blockPos.getX() + 0.5 + face.getVector().getX() * 0.5,
                blockPos.getY() + 0.5 + face.getVector().getY() * 0.5,
                blockPos.getZ() + 0.5 + face.getVector().getZ() * 0.5
            );

            if (BlockUtilHelper.isPointValid(hitVec, face, blockPos, eyePos, mc.world, mc.player,
                true, strictPlaceLOS.get(), reach, null)) {
                return new BlockHitResult(hitVec, face, blockPos instanceof BlockPos.Mutable ? blockPos.toImmutable() : blockPos, false);
            }
        }

        return null;
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
            if (BlockUtils.getPlaceSide(supportBlock) == null) return;

            boolean placed;
            if (supportSafePlacement.get()) {
                // 安全放置：复用 Printer 的 NCP/LOS 系统
                placed = placeSupportSafe(supportBlock, item, hand);
            } else {
                // 传统放置：直接发包（无 NCP/LOS 验证）
                placed = BlockUtils.place(supportBlock, item, false, 0, swingMode.get().client(), true, false);
            }

            if (!placed) return;

            placeTimer += supportDelay.get();

            // 乐观更新：立即将快照中该位置标记为黑曜石（不等服务端回包）
            // 这样后台线程下一轮扫描会把此位置视为有效基座
            planner.updateBlock(supportBlock.getX(), supportBlock.getY(), supportBlock.getZ(), 1200.0f);

            if (supportDelay.get() == 0) placeCrystal(result, damage, null);
        }

        // Switch back
        if (autoSwitch.get() == AutoSwitchMode.Silent) InvUtils.swap(prevSlot, false);
    }

    /**
     * 安全放置支撑方块 —— 复用 Printer 的 NCP 方向检查 + LOS 视线检查 + hitVec 计算。
     * 确保放置方向和点击坐标通过反作弊验证。
     *
     * @return 是否成功发送了放置包
     */
    private boolean placeSupportSafe(BlockPos pos, FindItemResult item, Hand hand) {
        if (mc.player == null || mc.world == null) return false;

        // 构建放置上下文（黑曜石 defaultState，NCP strict + LOS check）
        net.minecraft.block.BlockState obsidianState = Blocks.OBSIDIAN.getDefaultState();
        PlacementContext ctx = PlacementContext.of(
            mc.world, pos, obsidianState, mc.player, true, true, placeRange.get()
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
        mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(hand, hitResult, 0));
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
        if (renderMode.get() == RenderMode.Normal) {
            placeRenderTimer = Math.max(placeRenderTimer, 2);
        } else {
            placeRenderTimer = Math.max(placeRenderTimer, 2);
        }
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
