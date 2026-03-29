# Printer 模块 vs GrimAC 冲突分析报告

> 日期: 2026-03-29
> 分析对象: meteor-client Printer 模块 + GrimAC 2.0 分支
> 问题表现: 打印机在边走边放方块时被 GrimAC 检测并触发卡脚(rubberband)

---

## 1. 问题概述

Printer 模块在 STRICT 模式下使用"双 Tick 旋转架构"：
- **Tick N**: 通过 `Rotations.rotate()` 发送旋转包（同步朝向）
- **Tick N+1**: 发送放置包（利用已同步的朝向）

这个架构在静止时工作正常，但**边走边放**时会被 GrimAC 检测并触发 setback（卡脚）。

---

## 2. 根本原因

### 2.1 额外旋转包污染 Grim 状态

Meteor 的 `Rotations` 系统在 `SendMovementPacketsEvent.Post` 事件中，通过 `sendPacket()` 方法发送独立的 `PlayerMoveC2SPacket.LookAndOnGround` 包（即 rotation-only 包）。

**Grim 将所有 Flying 系列包统一处理**（包括 Position、Look、PositionAndLook、Flying），rotation-only 包在 Grim 看来就是一个"只有旋转没有位置"的 Flying 包，这会导致：

#### 问题 A: `didLastMovementIncludePosition` 被覆盖

```
文件: CheckManagerListener.java#L758
逻辑: player.packetStateData.didLastMovementIncludePosition = hasPosition;
```

同一 Tick 内：
1. 原版 Flying 包 (POSITION_AND_LOOK) → `didLastMovementIncludePosition = true`
2. Meteor 旋转包 (LOOK_ONLY) → `didLastMovementIncludePosition = false` ← 覆盖！

这影响了 `PositionPlace` 的容差计算：
```java
// PositionPlace.java#L35
double movementThreshold = !player.packetStateData.didLastMovementIncludePosition
    || player.canSkipTicks() ? player.getMovementThreshold() : 0;
```

#### 问题 B: `onGround` 不匹配触发 ForceResync

```
文件: CheckManagerListener.java#L698
逻辑:
if (!hasPosition && onGround != player.packetStateData.packetPlayerOnGround && !player.inVehicle()) {
    boolean canFeasiblyPointThree = Collisions.slowCouldPointThreeHitGround(...);
    if (!canFeasiblyPointThree && ...) {
        player.getSetbackTeleportUtil().executeForceResync(); // ← 直接 setback
    }
}
```

当 rotation-only 包携带的 `onGround` 与 Grim 记录的不同时，Grim 认为这是 ghost block/0.03 abuse，直接触发 `executeForceResync()`，设置 `blockOffsets = true`。

#### 问题 C: 放置检查位置/旋转不对齐

```
文件: CheckManagerListener.java#L94-135
逻辑: handleQueuedPlaces() 使用 lastClaimedPosition + 当前旋转做检查
```

放置包在 rotation-only 包的 `handleFlying()` 中被处理时：
- 位置 = 上一个 position 包的位置（已过时）
- 旋转 = rotation-only 包的新旋转
- `RotationPlace` 从旧位置用新旋转做射线 → 打不到目标方块 → flag

### 2.2 冲突链条

```
Tick N（走路 + 旋转同步）:
  1. 原版 Flying 包: POSITION_AND_LOOK (hasPos=true, hasLook=true)
     → Grim: packetPlayerOnGround = onGround
     → didLastMovementIncludePosition = true

  2. Rotations.Post → sendPacket(): LOOK_ONLY (hasPos=false, hasLook=true)
     → Grim:
        a) onGround 不匹配? → executeForceResync() = 直接卡脚！
        b) didLastMovementIncludePosition = false (覆盖!)
        c) handleQueuedPlaces() 用旧位置+新旋转
        d) RotationPlace 射线偏移 → flag

Tick N+1（走路 + 放置）:
  3. 放置包 PLAYER_BLOCK_PLACEMENT 入队
  4. 原版 Flying 包: POSITION
     → violations 累积 > setbackVL → 卡脚
```

---

## 3. Grim 关键检测逻辑

### 3.1 RotationPlace (射线检测)

**文件**: `checks/impl/scaffolding/RotationPlace.java`

```java
private boolean didRayTraceHit(BlockPlace place) {
    // 尝试当前旋转和上一次旋转
    List<Vector3f> possibleLookDirs = Arrays.asList(
        new Vector3f(player.yaw, player.pitch, 0),      // 当前
        new Vector3f(player.lastYaw, player.pitch, 0)    // 上一次
    );
    // 1.9+ 额外检查 lastYaw + lastPitch

    for (double eyeHeight : player.getPossibleEyeHeights()) {
        for (Vector3f lookDir : possibleLookDirs) {
            Vector3d eye = new Vector3d(player.x, player.y + eyeHeight, player.z);
            Ray trace = new Ray(player, eye, lookDir);
            // 射线命中目标方块? → 通过
        }
    }
    return false; // 所有组合都没命中 → flag
}
```

### 3.2 PositionPlace (位置合法性)

**文件**: `checks/impl/scaffolding/PositionPlace.java`

检查玩家眼睛位置是否能合法地看到所点击的方块面。根据 `didLastMovementIncludePosition` 决定是否给予 0.03 容差。

### 3.3 DuplicateRotPlace (旋转重复检测)

**文件**: `checks/impl/scaffolding/DuplicateRotPlace.java`

检测连续放置间旋转变化量是否过小（< 0.0001），用于检测自动化放置。

### 3.4 Setback 触发

```java
// SetbackTeleportUtil.java#L383
public boolean shouldBlockMovement() {
    return insideUnloadedChunk()
        || blockOffsets                                    // ForceResync 设置
        || (requiredSetBack != null && !requiredSetBack.isComplete()); // 违规 setback
}
```

---

## 4. 解决方案：单 Tick 放置架构

### 核心思路

不再发送独立的旋转包。改为在 `SendMovementPacketsEvent.Pre` 中修改 `mc.player.yaw/pitch`，让原版的 Flying 包自然携带正确旋转，然后在**同一个 Tick** 的回调中执行放置。

### 数据包时序对比

**修复前**（双 Tick）:
```
Tick N: [POSITION_AND_LOOK] [LOOK_ONLY(额外旋转包)] ← 两个 Flying 包！
Tick N+1: [BLOCK_PLACEMENT] [POSITION]
```

**修复后**（单 Tick）:
```
Tick N: [POSITION_AND_LOOK(含正确旋转)] [BLOCK_PLACEMENT] ← 正常行为！
```

### 修复要点

1. 删除双 Tick 状态机中的"旋转同步"等待
2. 在 `Rotations.rotate()` 的回调中直接执行放置
3. 确保每个 Tick 只有一个 Flying 包
4. 放置操作在 Flying 包发出后立即执行

---

## 5. 附录：Grim 代码关键文件

| 文件 | 路径 | 功能 |
|------|------|------|
| CheckManagerListener | `events/packets/CheckManagerListener.java` | Flying 包处理入口 |
| RotationPlace | `checks/impl/scaffolding/RotationPlace.java` | 放置射线检测 |
| PositionPlace | `checks/impl/scaffolding/PositionPlace.java` | 放置位置检测 |
| DuplicateRotPlace | `checks/impl/scaffolding/DuplicateRotPlace.java` | 旋转重复检测 |
| FarPlace | `checks/impl/scaffolding/FarPlace.java` | 距离检测 |
| FabricatedPlace | `checks/impl/scaffolding/FabricatedPlace.java` | 光标越界检测 |
| SetbackTeleportUtil | `manager/SetbackTeleportUtil.java` | Setback/Rubberband 管理 |
| MovementCheckRunner | `predictionengine/MovementCheckRunner.java` | 移动预测引擎 |
| PacketStateData | `utils/data/PacketStateData.java` | 包状态追踪 |

---

## 6. Meteor Rotations 系统参考

**文件**: `utils/player/Rotations.java`

关键问题代码：
```java
// SendMovementPacketsEvent.Post 中发送额外旋转包:
for (; i < rotations.size(); i++) {
    Rotation rotation = rotations.get(i);
    rotation.sendPacket(); // ← 发送独立的 LookAndOnGround 包
}

// sendPacket() 实现:
public void sendPacket() {
    mc.getNetworkHandler().sendPacket(
        new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, onGround, horizontalCollision));
}
```

这个独立包在 Grim 看来是额外的 Flying 包，破坏了移动预测引擎的状态。
