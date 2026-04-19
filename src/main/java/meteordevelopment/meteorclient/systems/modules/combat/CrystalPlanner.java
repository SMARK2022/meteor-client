package meteordevelopment.meteorclient.systems.modules.combat;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.Difficulty;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * 事件驱动的异步水晶放置扫描器。
 *
 * 架构说明：
 * 1. 主线程维护一个「活体爆炸抗性张量」(AtomicIntegerArray)，方块变化时 O(1) 增量写入。
 * 2. 后台线程常驻，采用事件触发 + 10ms 窗口节流，合并高频 BlockUpdate 风暴。
 * 3. 后台线程每次只消费最新一份候选快照，自动丢弃中间过期请求（latest-wins）。
 * 4. 结果携带世界版本和生成时戳，主线程可按“新鲜度”门控，避免使用过期 proposal。
 *
 * 为什么不用直接异步读 mc.world：
 * - ClientWorld/Chunk/Entity 容器是主线程结构，不具备并发可见性和一致性保证。
 * - 让后台线程读实时 world 会引入竞态读取和偶发崩溃风险（尤其在 chunk 更新/实体增删阶段）。
 * - 因此采用“领域快照张量 + 主线程增量同步”的模型，用极低复制成本换线程安全。
 *
 * 张量半径分析：
 * - 曝光度射线从目标包围盒顶点出发，指向水晶位置（玩家附近 placeRange 以内）。
 * - 射线经过的方块均在 max(targetRange, placeRange) 以内（距快照中心/玩家位置）。
 * - 默认 targetRange=10 → 射线最远到距中心 ~10.5 格 → SNAP_R=10 即可覆盖。
 * - 超出范围的方块返回 blastRes=0（视为非防爆），等同于空气，不影响射线判定。
 *
 * 规模：SNAP_R=10 → SNAP_D=21 → 9261 entries，内存约 37KB。
 */
public class CrystalPlanner {

    // ====== 快照参数 ======
    // 半径 10 覆盖默认 targetRange=10；直径 21，总量 9261 个浮点数
    private static final int SNAP_R = 10;
    private static final int SNAP_D = SNAP_R * 2 + 1;
    private static final int SNAP_SIZE = SNAP_D * SNAP_D * SNAP_D;

    // ====== 主线程写入的活体张量（后台线程无锁读取） ======
    private final AtomicIntegerArray blastResBits = new AtomicIntegerArray(SNAP_SIZE);
    private volatile int centerX, centerY, centerZ;
    private volatile boolean snapshotReady;

    // 世界版本号：任意方块同步都会递增，用于结果新鲜度判定
    private final AtomicLong worldVersion = new AtomicLong();

    // ====== 后台线程（常驻 + 事件驱动） ======
    private static final long MIN_SCAN_INTERVAL_NS = 10_000_000L; // 10ms 合并窗口
    private final Object trigger = new Object();
    private final Thread worker;
    private volatile boolean running = true;

    // ====== 结果（后台线程写入，主线程原子消费）======
    /**
     * 组合结果：
     * - requestSeq: 结果对应的候选请求序号（latest-wins）
     * - worldVersion: 计算结束时的世界版本
     * - computedAtNs: 结果产出时间（用于 age 门控）
     */
    public record ResultSet(PlaceResult direct, PlaceResult support, long requestSeq, long worldVersion, long computedAtNs) {}
    private final AtomicReference<ResultSet> latestResult = new AtomicReference<>();
    private volatile boolean busy;

    // latest-wins 请求缓冲
    private final AtomicLong requestSeq = new AtomicLong();
    private volatile long pendingSeq;
    private volatile boolean pendingScan;
    private volatile Candidate[] pendingCandidates;
    private volatile TargetSnap[] pendingTargets;
    private volatile TargetSnap pendingSelf;
    private volatile ScanSettings pendingSettings;

    // ====== 数据结构 ======

    /**
     * 放置扫描结果：最佳基座坐标 + 预估伤害。
     * 主线程消费结果时必须实时调用 resolveCrystalHit 验证可达性。
     */
    public record PlaceResult(int x, int y, int z, double damage) {}

    /**
     * 候选位置 —— 主线程范围/碰撞预过滤后传递给后台线程。
     * 仅包含基座坐标和是否有方块（LOS 在消费时实时验证）。
     */
    public record Candidate(int x, int y, int z, boolean hasBlock) {}

    /**
     * 目标快照 —— 在主线程从活体实体上提取的纯值拷贝，后台线程可安全读取。
     * 包含位置、包围盒、护甲/韧性、保护附魔等级、抗性效果等级、生命值、受伤 CD。
     */
    public record TargetSnap(
        double posX, double posY, double posZ,
        double bMinX, double bMinY, double bMinZ, double bMaxX, double bMaxY, double bMaxZ,
        float armor, float toughness, int protLevel, int resLevel, float health, int hurtTime) {}

    /** 扫描配置快照 —— 在主线程捕获的设置值，确保后台线程读取的是一致性快照。 */
    public record ScanSettings(
        double maxDmg, boolean antiSui, double safetyMargin, double minDmg, boolean smart,
        boolean facePlace, boolean supportFast, float tps, Difficulty difficulty, double damageRatio) {}

    public CrystalPlanner() {
        worker = new Thread(this::runLoop, "CA-Planner");
        worker.setDaemon(true);
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.start();
    }

    // ============================== 快照管理 ==============================

    /** 判断是否需要全量重建（首次运行、或玩家移动超过 2 格）。 */
    public boolean needsRebuild(int playerX, int playerY, int playerZ) {
        if (!snapshotReady) return true;
        return Math.abs(playerX - centerX) > 2
            || Math.abs(playerY - centerY) > 2
            || Math.abs(playerZ - centerZ) > 2;
    }

    /** 全量重建快照 —— 遍历 SNAP_D³ 个方块，存储爆炸抗性。在主线程调用。 */
    public void rebuildSnapshot(ClientWorld world, int cx, int cy, int cz) {
        centerX = cx; centerY = cy; centerZ = cz;
        int idx = 0;
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int dx = -SNAP_R; dx <= SNAP_R; dx++)
            for (int dy = -SNAP_R; dy <= SNAP_R; dy++)
                for (int dz = -SNAP_R; dz <= SNAP_R; dz++)
                    blastResBits.set(idx++, Float.floatToRawIntBits(world.getBlockState(mutable.set(cx + dx, cy + dy, cz + dz))
                        .getBlock().getBlastResistance()));
        snapshotReady = true;
        worldVersion.incrementAndGet();
        triggerScanIfPending();
    }

    /**
     * 增量更新单个方块的爆炸抗性。由 BlockUpdateEvent 触发，在主线程调用。
     * 如果坐标超出快照范围则静默忽略（远端方块无影响）。
     */
    public void updateBlock(int x, int y, int z, float newBlastRes) {
        if (!snapshotReady) return;
        int dx = x - centerX + SNAP_R, dy = y - centerY + SNAP_R, dz = z - centerZ + SNAP_R;
        if (dx < 0 || dx >= SNAP_D || dy < 0 || dy >= SNAP_D || dz < 0 || dz >= SNAP_D) return;
        blastResBits.set(dx * SNAP_D * SNAP_D + dy * SNAP_D + dz, Float.floatToRawIntBits(newBlastRes));
        worldVersion.incrementAndGet();
        triggerScanIfPending();
    }

    // ============================== 扫描提交 ==============================

    public boolean isBusy() { return busy; }

    /** 当前活体世界版本（仅用于上层诊断/门控）。 */
    public long getWorldVersion() {
        return worldVersion.get();
    }

    /**
     * 原子消费扫描结果 —— 读取并清空。AtomicReference.getAndSet 保证无竞态窗口。
     * @return 最新结果, 无新结果时返回 null
     */
    public ResultSet consumeResult() {
        return latestResult.getAndSet(null);
    }

    /**
     * 结果新鲜度门控：
     * - age 超过阈值直接丢弃（慢线程/卡顿下防陈旧方案）
     * - worldVersion 漂移过大直接丢弃（高频环境变化下防错位方案）
     */
    public boolean isResultFresh(ResultSet result, long maxAgeMs, long maxWorldDrift) {
        if (result == null) return false;
        long ageNs = System.nanoTime() - result.computedAtNs();
        if (ageNs > maxAgeMs * 1_000_000L) return false;
        return worldVersion.get() - result.worldVersion() <= maxWorldDrift;
    }

    /**
     * 提交候选位置到后台线程进行伤害评估。
     * 候选仅需范围/碰撞预过滤（LOS 在主线程消费结果时实时验证）。
     * 在提交前拷贝快照数组（Arrays.copyOf），保证后台线程读取的是主线程提交时刻的一致性快照，
     * 主线程可在此之后继续通过 updateBlock 修改原始数组而不影响正在运行的扫描。
     */
    public void submitScan(Candidate[] candidates, TargetSnap[] targets, TargetSnap self, ScanSettings settings) {
        if (!snapshotReady) return;

        // latest-wins：主线程每次提交都覆盖旧请求，后台只处理“当前最有价值”的一份输入。
        // Why: 战斗态环境变化快，处理历史请求只会放大 stale 结果。
        pendingCandidates = candidates;
        pendingTargets = targets;
        pendingSelf = self;
        pendingSettings = settings;
        pendingSeq = requestSeq.incrementAndGet();
        pendingScan = true;
        signalWorker();
    }

    // ============================== 后台线程（禁止访问 mc.world）==============================

    /**
     * 常驻后台循环：事件触发 + 10ms 合并窗口。
     *
     * Why:
     * - 不使用 while 自旋，空闲时阻塞等待，避免吃满单核。
     * - 高频 block update 时按窗口合并，防止每个包都触发一轮全扫描。
     * - 每轮只吃最新请求序号，主动丢弃中间态，降低过时解概率。
     */
    private void runLoop() {
        long lastRunNs = 0;

        while (running) {
            waitForWork();
            if (!running) break;

            long waitNs = MIN_SCAN_INTERVAL_NS - (System.nanoTime() - lastRunNs);
            if (waitNs > 0) LockSupport.parkNanos(waitNs);

            Candidate[] candidates = pendingCandidates;
            TargetSnap[] targets = pendingTargets;
            TargetSnap self = pendingSelf;
            ScanSettings settings = pendingSettings;
            long seq = pendingSeq;

            if (!snapshotReady || candidates == null || targets == null || self == null || settings == null) {
                pendingScan = false;
                continue;
            }

            busy = true;
            int cx = centerX, cy = centerY, cz = centerZ;
            ResultSet result = runScanLive(cx, cy, cz, candidates, targets, self, settings, seq);
            latestResult.set(result);
            busy = false;

            lastRunNs = System.nanoTime();

            // latest-wins：若期间有新提交或世界版本继续漂移，则继续下一轮
            if (seq == pendingSeq) pendingScan = false;
        }
    }

    private void waitForWork() {
        synchronized (trigger) {
            while (running && !pendingScan) {
                try {
                    trigger.wait();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    running = false;
                    return;
                }
            }
        }
    }

    private void signalWorker() {
        synchronized (trigger) {
            trigger.notify();
        }
    }

    private void triggerScanIfPending() {
        if (!pendingScan) return;
        signalWorker();
    }

    private ResultSet runScanLive(int cx, int cy, int cz,
                                  Candidate[] candidates, TargetSnap[] targets, TargetSnap self,
                                  ScanSettings s, long seq) {
        PlaceResult bestDirect = null, bestSupport = null;

        float effectiveMaxDmg = (float) s.maxDmg;
        if (s.tps < 18) effectiveMaxDmg *= 0.85f;

        for (Candidate cand : candidates) {
            double expX = cand.x + 0.5, expY = cand.y + 1, expZ = cand.z + 0.5;

            // 自伤检测
            float selfDmg = crystalDamage(cx, cy, cz, self, expX, expY, expZ, cand.x, cand.y, cand.z, s.difficulty);
            if (selfDmg > effectiveMaxDmg || (s.antiSui && selfDmg >= (self.health - s.safetyMargin))) continue;

            // 目标伤害 —— 对所有目标进行完整评估（异步只做 place，不受 smartDelay 限制）
            double damage = 0;
            boolean useFast = !cand.hasBlock && s.supportFast;
            if (useFast && targets.length > 0) {
                float dmg = crystalDamage(cx, cy, cz, targets[0], expX, expY, expZ, cand.x, cand.y, cand.z, s.difficulty);
                damage = dmg;
            } else {
                for (TargetSnap t : targets) {
                    float dmg = crystalDamage(cx, cy, cz, t, expX, expY, expZ, cand.x, cand.y, cand.z, s.difficulty);
                    damage = Math.max(damage, dmg);
                }
            }

            double minDamage = s.facePlace ? Math.min(s.minDmg, 1.5) : s.minDmg;
            if (damage < minDamage) continue;

            // Damage ratio check
            if (s.damageRatio > 0 && selfDmg >= 1.0f && damage / selfDmg < s.damageRatio) continue;

            PlaceResult pr = new PlaceResult(cand.x, cand.y, cand.z, damage);

            if (cand.hasBlock) {
                if (bestDirect == null || damage > bestDirect.damage()) bestDirect = pr;
            } else {
                if (bestSupport == null || damage > bestSupport.damage()) bestSupport = pr;
            }
        }

        return new ResultSet(bestDirect, bestSupport, seq, worldVersion.get(), System.nanoTime());
    }

    // ------ 纯数学伤害计算（线程安全，不访问 mc.world）------

    private float crystalDamage(int cx, int cy, int cz,
                                TargetSnap t, double expX, double expY, double expZ,
                                int obsX, int obsY, int obsZ, Difficulty difficulty) {
        double dx = t.posX - expX, dy = t.posY - expY, dz = t.posZ - expZ;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist > 12) return 0;

        double exposure = calcExposure(cx, cy, cz, expX, expY, expZ,
            t.bMinX, t.bMinY, t.bMinZ, t.bMaxX, t.bMaxY, t.bMaxZ, obsX, obsY, obsZ);
        double impact = (1 - dist / 12.0) * exposure;
        float rawDmg = (int) ((impact * impact + impact) / 2.0 * 7.0 * 12.0 + 1);

        return applyReductions(rawDmg, t, difficulty);
    }

    private float applyReductions(float damage, TargetSnap t, Difficulty difficulty) {
        // 难度缩放（位于护甲之前 —— 原版顺序）
        switch (difficulty) {
            case EASY -> damage = Math.min(damage / 2 + 1, damage);  // 简单: 伤害减半+1
            case HARD -> damage *= 1.5f;  // 困难: 1.5倍
            default -> {}
        }
        // 护甲减免（原版公式：f = 2 + toughness/4; g = clamp(armor - damage/f, armor*0.2, 20); damage *= 1 - g/25）
        float f = 2.0f + t.toughness / 4.0f;
        float g = MathHelper.clamp(t.armor - damage / f, t.armor * 0.2f, 20.0f);
        damage *= (1 - g / 25.0f);
        // 抵抗效果（每级减 20%）
        if (t.resLevel >= 0) damage *= 1 - (t.resLevel + 1) * 0.2f;
        // 保护附魔（合计等级 clamp(0,20)，每点 4% 减免）
        damage *= 1 - MathHelper.clamp(t.protLevel, 0, 20) / 25.0f;
        return Math.max(damage, 0);
    }

    private double calcExposure(int cx, int cy, int cz,
                                double srcX, double srcY, double srcZ,
                                double bMinX, double bMinY, double bMinZ,
                                double bMaxX, double bMaxY, double bMaxZ,
                                int obsX, int obsY, int obsZ) {
        double xDiff = bMaxX - bMinX, yDiff = bMaxY - bMinY, zDiff = bMaxZ - bMinZ;
        double xStep = 1 / (xDiff * 2 + 1), yStep = 1 / (yDiff * 2 + 1), zStep = 1 / (zDiff * 2 + 1);
        if (xStep <= 0 || yStep <= 0 || zStep <= 0) return 0;

        double xOff = (1 - Math.floor(1 / xStep) * xStep) * 0.5;
        double zOff = (1 - Math.floor(1 / zStep) * zStep) * 0.5;
        xStep *= xDiff; yStep *= yDiff; zStep *= zDiff;

        int misses = 0, total = 0;
        for (double x = bMinX + xOff; x <= bMaxX + xOff; x += xStep)
            for (double y = bMinY; y <= bMaxY; y += yStep)
                for (double z = bMinZ + zOff; z <= bMaxZ + zOff; z += zStep) {
                    if (!rayBlocked(cx, cy, cz, x, y, z, srcX, srcY, srcZ, obsX, obsY, obsZ))
                        misses++;
                    total++;
                }
        return total == 0 ? 0 : (double) misses / total;
    }

    /**
     * 基于快照的射线检测 —— 复用 MC 的 BlockView.raycast (DDA 行走算法)。
     * 工厂函数从快照数组读取爆炸抗性：
     * - 抗性 >= 600（黑曜石/基岩）：返回 VoxelShapes.fullCube() 射线命中（全方块程序化碰撞体）
     * - 抗性 < 600：返回 null（射线穿透）
     * - obsX/Y/Z 位置强制返回 1200（模拟 support 位置放置的黑曜石）
     */
    private boolean rayBlocked(int cx, int cy, int cz,
                               double startX, double startY, double startZ,
                               double endX, double endY, double endZ,
                               int obsX, int obsY, int obsZ) {
        DamageUtils.ExposureRaycastContext ctx = new DamageUtils.ExposureRaycastContext(
            new Vec3d(startX, startY, startZ), new Vec3d(endX, endY, endZ));

        DamageUtils.RaycastFactory factory = (c, bp) -> {
            float br;
            if (bp.getX() == obsX && bp.getY() == obsY && bp.getZ() == obsZ) {
                br = 1200.0f; // 强制视为黑曜石，模拟 support 块已放置
            } else {
                br = getBlastRes(cx, cy, cz, bp.getX(), bp.getY(), bp.getZ());
            }
            if (br < 600) return null;
            return VoxelShapes.fullCube().raycast(c.start(), c.end(), bp);
        };

        return BlockView.raycast(ctx.start(), ctx.end(), ctx, factory, c -> null) != null;
    }

    private float getBlastRes(int cx, int cy, int cz, int x, int y, int z) {
        int dx = x - cx + SNAP_R, dy = y - cy + SNAP_R, dz = z - cz + SNAP_R;
        if (dx < 0 || dx >= SNAP_D || dy < 0 || dy >= SNAP_D || dz < 0 || dz >= SNAP_D) return 0;
        return Float.intBitsToFloat(blastResBits.get(dx * SNAP_D * SNAP_D + dy * SNAP_D + dz));
    }

    // ============================== 目标快照（主线程）==============================

    /**
     * 从活体实体提取纯值快照，后台线程可安全读取。
     * 护甲值取 floor（原版行为），保护等级 = protection + 2*blast_protection（爆炸保护权重 2x）。
     */
    public static TargetSnap snapshotTarget(LivingEntity entity, boolean predictMovement) {
        Vec3d pos = predictMovement ? entity.getPos().add(entity.getVelocity()) : entity.getPos();
        Box box = entity.getBoundingBox();
        if (predictMovement) box = box.offset(entity.getVelocity());
        float armor = (float) Math.floor(entity.getAttributeValue(EntityAttributes.ARMOR));
        float tough = (float) entity.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS);
        int prot = 0;
        for (EquipmentSlot slot : AttributeModifierSlot.ARMOR) {
            ItemStack stack = entity.getEquippedStack(slot);
            var enchants = new Object2IntOpenHashMap<RegistryEntry<Enchantment>>();
            Utils.getEnchantments(stack, enchants);
            int p = Utils.getEnchantmentLevel(enchants, Enchantments.PROTECTION);
            if (p > 0) prot += p;
            int bp = Utils.getEnchantmentLevel(enchants, Enchantments.BLAST_PROTECTION);
            if (bp > 0) prot += 2 * bp;
        }
        int res = -1;
        StatusEffectInstance resistance = entity.getStatusEffect(StatusEffects.RESISTANCE);
        if (resistance != null) res = resistance.getAmplifier();
        return new TargetSnap(pos.x, pos.y, pos.z, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ,
            armor, tough, prot, res, EntityUtils.getTotalHealth(entity), entity.hurtTime);
    }

    // ============================== 生命周期 ==============================

    /** 模块激活时调用，重置所有状态。 */
    public void reset() {
        latestResult.set(null);
        busy = false;
        snapshotReady = false;
        pendingScan = false;
        pendingCandidates = null;
        pendingTargets = null;
        pendingSelf = null;
        pendingSettings = null;
        worldVersion.incrementAndGet();
    }
}
