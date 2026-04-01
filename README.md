<p align="center">
<img src="https://meteorclient.com/icon.png" alt="meteor-client-logo" width="15%"/>
</p>

<h1 align="center">Meteor Client — SMARK 修改版</h1>
<p align="center">
基于 <a href="https://github.com/MeteorDevelopment/meteor-client">MeteorDevelopment/meteor-client</a> 的 <code>1.21.4</code> 标签构建的修改分支<br/>
适用于 Minecraft 1.21.4 的 Fabric 实用工具模组
</p>

<div align="center">
    <a href="https://github.com/MeteorDevelopment/meteor-client"><img src="https://img.shields.io/badge/上游仓库-MeteorDevelopment%2Fmeteor--client-blue" alt="上游仓库"/></a>
    <img src="https://img.shields.io/badge/基线标签-1.21.4-green" alt="基线标签"/>
    <img src="https://img.shields.io/badge/许可证-GPL--3.0-orange" alt="License"/>
</div>

---

## 📖 项目简介

本仓库是 [MeteorDevelopment/meteor-client](https://github.com/MeteorDevelopment/meteor-client) 的个人修改分支，基于上游仓库的 **`1.21.4` 标签**（提交 `a96efdc`）进行修改。主要改动包括：

1. **🏷️ Nametag（名牌）显示修复** — 修复了现代化 UI 缩放下名牌显示错位的问题
2. **🖨️ Printer（打印机）模块** — 新增基于 Litematica 蓝图的自动放置方块模块
3. **🍖 AutoEat（自动进食）修复** — 修复食物检测逻辑 Bug，优化进食时的暂停/恢复顺序
4. **⛏️ InfinityMiner（无限矿工）优化** — 新增进食让行机制，避免挖矿与进食冲突
5. **🔧 其他小修复** — 旋转优先级逻辑修正、Mixin 兼容性补丁等

> **本 README 提供了完整的差异分析和安全性审计，以便使用者了解本分支对原仓库进行了哪些修改。**

---

## 📊 差异总览

与上游 `1.21.4` 标签相比，本分支共计 **55 次提交**，涉及 **24 个文件**，新增 **4566 行**，删除 **179 行**。

### 修改文件一览

| 文件路径 | 类型 | 改动说明 |
|---------|------|---------|
| `src/.../utils/render/NametagUtils.java` | ✏️ 修改 | 修复现代化 UI 缩放下的名牌位置偏移 |
| `src/.../mixin/GameRendererMixin.java` | ✏️ 修改 | 传递投影矩阵给 NametagUtils（1 行） |
| `src/.../systems/modules/Modules.java` | ✏️ 修改 | 注册新的 Printer 模块（1 行） |
| `src/.../systems/modules/player/Printer.java` | 🆕 新增 | Printer 打印机模块主体（921 行） |
| `src/.../systems/modules/player/AutoEat.java` | ✏️ 修改 | 修复食物检测逻辑，优化进食暂停顺序 |
| `src/.../systems/modules/world/InfinityMiner.java` | ✏️ 修改 | 新增进食让行机制，优化与 AutoEat 联动 |
| `src/.../utils/player/ItemSwitchHelper.java` | 🆕 新增 | 物品切换辅助工具类（196 行） |
| `src/.../utils/printer/BlockUtilHelper.java` | 🆕 新增 | 方块工具辅助类（292 行） |
| `src/.../utils/printer/CandidateFilter.java` | 🆕 新增 | 候选方块过滤器（77 行） |
| `src/.../utils/printer/CandidateSource.java` | 🆕 新增 | 候选方块来源（63 行） |
| `src/.../utils/printer/HitVecCalculator.java` | 🆕 新增 | 点击位置计算器（156 行） |
| `src/.../utils/printer/PlacementContext.java` | 🆕 新增 | 放置上下文（117 行） |
| `src/.../utils/printer/PlacementOption.java` | 🆕 新增 | 放置选项（75 行） |
| `src/.../utils/printer/PlacementResolver.java` | 🆕 新增 | 放置解析器（270 行） |
| `src/.../utils/printer/ResolverRegistry.java` | 🆕 新增 | 解析器注册表（724 行） |
| `src/.../utils/printer/Rules.java` | 🆕 新增 | 方块放置规则（1040 行） |
| `src/.../utils/player/Rotations.java` | ✏️ 修改 | 旋转优先级比较逻辑修正（1 行） |
| `src/.../asm/Asm.java` | ✏️ 修改 | Mixin 兼容性补丁（4 行） |
| `build.gradle.kts` | ✏️ 修改 | 添加 Litematica/MaLiLib 依赖，升级 fabric-loom |
| `gradle.properties` | ✏️ 修改 | 构建参数调整，添加依赖版本号 |
| `gradle/wrapper/gradle-wrapper.properties` | ✏️ 修改 | Gradle 版本升级至 9.2.0 |
| `gradlew.bat` | ✏️ 修改 | 添加 UTF-8 编码设置 |
| `prompt` | 🆕 新增 | 开发备忘（非代码文件，不影响运行） |

---

## 🔍 详细差异分析

### 1. 🏷️ Nametag（名牌）显示修复

**问题**：在高分辨率屏幕或非 100% GUI 缩放下，Meteor 的自定义名牌（Nametag）在屏幕上的渲染位置出现偏移/错位。

**修改文件**：
- `src/.../utils/render/NametagUtils.java`
- `src/.../mixin/GameRendererMixin.java`

**具体改动**：

#### `NametagUtils.java` — 坐标计算修正

| 改动项 | 说明 |
|-------|------|
| `onRender()` 方法签名变更 | 新增 `Matrix4f projectionMatrix` 参数，直接接收渲染管线的投影矩阵，而非通过 `RenderSystem.getProjectionMatrix()` 获取（避免时序错位） |
| `toScreen()` 坐标计算 | 将 `x / windowScale` 替换为直接使用 `x`，将 `y` 从 `framebufferHeight - y / windowScale` 改为 `framebufferHeight - y`，修正了 GUI 缩放带来的二次缩放问题 |
| `begin()` 矩阵缩放 | 在 `matrices.translate()` 之前新增 `matrices.scale(1/scaleFactor, 1/scaleFactor, 1)`，确保名牌在不同 GUI 缩放下正确渲染 |
| 调试日志 | 添加了条件调试日志（默认 **已禁用**，`DEBUG_ENABLED = false`），不会在正常运行时产生任何输出 |

#### `GameRendererMixin.java` — 投影矩阵传递

```java
// 原始代码
NametagUtils.onRender(view);
// 修改后
NametagUtils.onRender(view, projection);
```

仅修改了一行调用，将投影矩阵 `projection` 一并传递。

---

### 2. 🖨️ Printer（打印机）模块

**功能**：读取 [Litematica](https://github.com/maruohon/litematica) 模组的蓝图数据，自动在游戏中放置对应方块，实现半自动化建筑。

**新增文件结构**：

```
src/main/java/meteordevelopment/meteorclient/
├── systems/modules/player/
│   └── Printer.java                    # 主模块（921行）— 包含 tick 逻辑、放置状态机、UI 设置
├── utils/player/
│   └── ItemSwitchHelper.java           # 物品切换辅助（196行）— 管理快捷栏物品切换
└── utils/printer/
    ├── BlockUtilHelper.java            # 方块工具（292行）— 方块状态检查、邻居方块查找
    ├── CandidateFilter.java            # 候选过滤器（77行）— 过滤不可放置的候选方块
    ├── CandidateSource.java            # 候选来源（63行）— 从 Litematica 蓝图提取待放置方块
    ├── HitVecCalculator.java           # 点击计算器（156行）— 精确计算放置的交互射线
    ├── PlacementContext.java           # 放置上下文（117行）— 封装单次放置的全部参数
    ├── PlacementOption.java            # 放置选项（75行）— 描述一个可行的放置方向
    ├── PlacementResolver.java          # 放置解析器（270行）— 查找可行的放置方案
    ├── ResolverRegistry.java           # 解析器注册表（724行）— 各类方块的专用解析器
    └── Rules.java                      # 放置规则（1040行）— 半砖/楼梯/活板门等的放置规则
```

**关键技术点**：
- 依赖 Litematica 的 `SchematicWorldHandler` 获取蓝图方块状态
- 使用状态机管理每 tick 的放置流程（搜索→选择→切换物品→放置→冷却）
- 支持 Legit / Strict 两种放置模式
- 对半砖（Slab）、楼梯（Stairs）、活板门（Trapdoor）等特殊方块有专用放置逻辑
- 仅发送标准 Minecraft 交互数据包（`interactBlock` + `HandSwingC2SPacket`）

**构建依赖添加**（`build.gradle.kts`）：
```kotlin
// Litematica
modImplementation("maven.modrinth:litematica:0.21.6")
// MaLiLib（Litematica 的前置库）
modImplementation("fi.dy.masa.malilib:malilib-fabric-1.21.4:0.23.5")
```

---

### 3. 🍖 AutoEat（自动进食）修复

**修改文件**：`src/.../systems/modules/player/AutoEat.java`

**原始问题**：原版 AutoEat 模块存在食物检测逻辑 Bug，且进食时暂停/恢复光环（Aura）和 Baritone 的顺序不合理，可能导致冲突。

**具体改动**：

| 改动项 | 说明 |
|-------|------|
| 食物检测逻辑修复 | 将 `if (foodComponent != null)` 修正为 `if (foodComponent == null)`，修复了当前手持物品不是食物时未能正确切换的 Bug |
| 新增 `isEating()` 公开方法 | 暴露进食状态给其他模块查询（如 InfinityMiner），便于模块间联动 |
| 进食启动顺序优化 | 将 `eat()` 调用从 `startEating()` 开头移至末尾，确保先暂停光环和 Baritone **再** 开始进食，避免操作冲突 |
| 停止进食顺序优化 | 在 `stopEating()` 中将 `setPressed(false)` 移到 `changeSlot(prevSlot)` 之前，确保先停止进食动作再切换回原物品 |

**关键修复代码**：

```java
// 原始代码（Bug：!= null 条件反了，应该检查食物为空的情况）
if (mc.player.getInventory().getStack(slot).get(DataComponentTypes.FOOD) != null) {
// 修复后
if (mc.player.getInventory().getStack(slot).get(DataComponentTypes.FOOD) == null) {
```

```java
// 原始顺序：先吃再暂停
private void startEating() {
    prevSlot = mc.player.getInventory().selectedSlot;
    eat();           // ← 先吃
    // Pause auras   // ← 后暂停
}

// 修复后顺序：先暂停再吃
private void startEating() {
    prevSlot = mc.player.getInventory().selectedSlot;
    // Pause auras first  // ← 先暂停
    // Pause baritone first
    eat();                // ← 后吃
}
```

---

### 4. ⛏️ InfinityMiner（无限矿工）优化

**修改文件**：`src/.../systems/modules/world/InfinityMiner.java`

**原始问题**：InfinityMiner 在玩家需要进食时不会让步，导致挖矿操作与 AutoEat 的进食操作产生冲突（例如快捷栏物品切换冲突）。

**具体改动**：

| 改动项 | 说明 |
|-------|------|
| 新增 `yieldingToEating` 状态 | 布尔标志，跟踪当前是否正在为进食让行 |
| 新增 `shouldYieldToEating()` 方法 | 检查 AutoEat 模块是否处于活跃状态且正在进食 |
| 进食让行逻辑 | 当检测到 AutoEat 正在进食时，暂停 Baritone 寻路，跳过所有镐子查找和挖矿操作，避免快捷栏冲突 |
| Tick 逻辑重排 | 将 `checkThresholds()` 检查移至 `findPickaxe()` 之前，进食让行检查插入二者之间 |

**关键新增代码**：

```java
// 新增：进食让行检查
private boolean shouldYieldToEating() {
    AutoEat autoEat = Modules.get().get(AutoEat.class);
    return autoEat != null && autoEat.isActive() && autoEat.isEating();
}
```

```java
// tick 中的让行逻辑
if (shouldYieldToEating()) {
    if (!yieldingToEating) {
        yieldingToEating = true;
        baritone.getPathingBehavior().cancelEverything(); // 暂停挖矿
    }
    return; // 跳过本 tick
}
```

---

### 5. 🔧 其他修改

#### `Rotations.java` — 旋转优先级修正

```java
// 原始代码（高优先级后插入）
if (priority > rotations.get(i).priority) break;
// 修改后（高优先级前插入）
if (priority <= rotations.get(i).priority) break;
```

将旋转请求的插入排序方向从「大于时中断」改为「小于等于时中断」，影响多个模块同时请求旋转时的优先级排序。

#### `Asm.java` — Mixin 兼容性补丁

```java
// 新增方法
public boolean couldTransformClass(MixinEnvironment environment, String name) {
    return true;
}
```

添加了 `couldTransformClass` 方法的重写，返回 `true`，解决某些 Mixin 环境下的兼容性问题。

#### 构建配置变更

| 配置项 | 原始值 | 修改后 |
|-------|-------|-------|
| `fabric-loom` 版本 | `1.9-SNAPSHOT` | `1.14.10` |
| Gradle 版本 | `8.12` | `9.2.0` |
| JVM 内存 | `-Xmx2G` | `-Xmx4G` |
| `sodium_version` | `mc1.21.4-0.6.6-fabric` | `mc1.21.4-0.6.13-fabric` |
| `lithium_version` | `mc1.21.4-0.14.3-fabric` | `mc1.21.4-0.15.3-fabric` |
| `iris_version` | `1.8.5+1.21.4-fabric` | `1.8.8+1.21.4-fabric` |
| `modmenu_version` | `13.0.2` | `13.0.3` |

> ⚠️ **注意**：`gradle.properties` 中包含了开发者本地配置（如 `org.gradle.java.home=F:\\include\\jdk-21.0.2` 和代理设置 `proxyHost=127.0.0.1:15236`），这些仅在开发者本地生效，不影响其他用户构建。

---

## 🔒 安全性分析

> 本节是对本分支所有修改代码的完整安全审计。针对「客户端可能泄露玩家坐标」的疑虑，逐一排查了以下威胁向量。

### 威胁模型

常见的恶意客户端窃取坐标的方式有两种：
1. **私聊泄露**：客户端自动向某个账户发送含坐标的聊天消息，且不在本地聊天框显示
2. **外部回传**：客户端通过 HTTP/WebSocket 等网络协议将坐标发送到外部服务器

### 审计结果

#### ✅ 1. 无任何聊天消息发送

对所有修改/新增文件进行全文搜索，**未找到**以下任何内容：
- `ChatMessageC2SPacket`（聊天消息数据包）
- `sendChatMessage()`（发送聊天）
- `sendCommand()`（发送命令）
- `CommandExecutionC2SPacket`（命令执行数据包）
- 任何 `/msg`、`/tell`、`/whisper` 等私聊指令

#### ✅ 2. 无任何外部网络连接

对所有修改/新增文件进行全文搜索，**未找到**以下任何内容：
- `HttpClient` / `HttpURLConnection`（HTTP 连接）
- `Socket` / `ServerSocket`（套接字通信）
- `URL` 对象创建（除了注释中的链接）
- `webhook` / `discord` / `telegram` 等回调地址
- 任何 `http://` 或 `https://` 连接（除了代码许可证注释头）

#### ✅ 3. 无文件写入操作

对所有修改/新增文件进行全文搜索，**未找到**以下任何内容：
- `FileOutputStream` / `FileWriter` / `BufferedWriter`
- 任何文件输出相关的 API 调用

#### ✅ 4. 无反射或动态代码加载

对所有修改/新增文件进行全文搜索，**未找到**以下任何内容：
- `Class.forName()`（动态类加载）
- `URLClassLoader`（远程类加载）
- `Runtime.getRuntime().exec()`（进程执行）
- `ProcessBuilder`（进程构建）
- `Base64` 编码/解码（数据混淆）

#### ✅ 5. 网络数据包分析

**Printer.java** 中唯一的网络数据包发送位于第 608 行：

```java
mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
```

这是标准的 **手臂挥动动画数据包**，是 Minecraft 客户端放置方块时的正常行为，仅包含 `Hand.MAIN_HAND` 枚举值，**不包含任何坐标信息**。

方块放置本身通过 `mc.interactionManager.interactBlock()` 执行，这是 Minecraft 原版客户端 API，与正常手动放置方块的行为完全一致。

**InfinityMiner.java** 中存在 `sendPacket(new DisconnectS2CPacket(...))` 调用，但此代码 **与上游原版完全一致**，用于在背包满时断开连接，属于该模块原有的断线功能，**并非本分支新增**。

**AutoEat.java** 中 **无任何** `sendPacket`、`getNetworkHandler` 或网络相关调用。所有操作均为本地客户端状态管理（切换物品栏、模拟右键点击等）。

#### ✅ 6. `gradle.properties` 中的代理配置

文件中存在本地代理配置：
```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=15236
```

这是开发者本地的 **构建时** 代理设置（用于 Gradle 下载依赖），仅在 `./gradlew build` 时生效。**不会影响 Minecraft 游戏运行时**，也不会将任何游戏数据通过代理转发。

### 🟢 结论

**本分支的所有修改代码均不包含任何形式的坐标泄露、隐私回传或恶意后门。** 所有新增代码的功能范围严格限于：
- 本地 UI 渲染修正（名牌显示）
- 本地游戏交互逻辑（方块放置、自动进食、无限矿工联动）
- 标准 Minecraft 客户端-服务器通信协议

---

## 🛠️ 构建与使用

### 前置要求

- **Java 21** 或更高版本
- **Minecraft 1.21.4**
- **Fabric Loader**
- [Litematica](https://www.curseforge.com/minecraft/mc-mods/litematica) 模组（Printer 功能需要）
- [MaLiLib](https://www.curseforge.com/minecraft/mc-mods/malilib)（Litematica 前置库）

### 构建步骤

```bash
# 1. 克隆本仓库
git clone https://github.com/SMARK2022/meteor-client.git
cd meteor-client

# 2. 构建（如果你在中国大陆，可能需要配置 Gradle 代理）
./gradlew build

# 3. 构建产物位于
#    build/libs/meteor-client-*.jar
```

> **注意**：首次构建可能需要较长时间（下载 Minecraft 反混淆映射等）。如果遇到内存不足，请调整 `gradle.properties` 中的 `org.gradle.jvmargs`。

### 安装步骤

1. 安装 [Fabric Loader](https://fabricmc.net/use/installer/)（选择 Minecraft 1.21.4）
2. 将构建产物 `meteor-client-*.jar` 放入 `.minecraft/mods/` 目录
3. 同时安装 Litematica 和 MaLiLib 模组（如需使用 Printer 功能）
4. 启动游戏

### Printer 使用方法

1. 在 Litematica 中加载蓝图并放置到世界中
2. 在 Meteor 客户端中启用 `Printer` 模块（Player 分类下）
3. 确保快捷栏中有蓝图所需的方块
4. 站在蓝图附近，模块会自动读取蓝图并放置方块

---

## 📁 仓库文件说明

| 文件/目录 | 说明 |
|----------|------|
| `src/` | Java 源代码 |
| `build.gradle.kts` | Gradle 构建脚本 |
| `gradle.properties` | 构建属性和依赖版本 |
| `prompt` | 开发过程备忘文件（非代码，不影响构建和运行） |
| `launch/` | 启动配置 |
| `.github/` | GitHub Actions 配置 |

---

## 📜 致谢

- [MeteorDevelopment/meteor-client](https://github.com/MeteorDevelopment/meteor-client) — 原始项目
- [Cabaletta](https://github.com/cabaletta) & [WagYourTail](https://github.com/wagyourtail) — [Baritone](https://github.com/cabaletta/baritone)
- [Fabric Team](https://github.com/FabricMC) — [Fabric](https://github.com/FabricMC/fabric-loader) & [Yarn](https://github.com/FabricMC/yarn)
- [maruohon](https://github.com/maruohon) — [Litematica](https://github.com/maruohon/litematica)

## 📄 许可证

本项目遵循 [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.en.html) 许可证。

如果你使用了本项目的 **任何** 代码：
- 你必须公开你修改后的源代码以及你从本项目中获取的代码。这意味着你不得在闭源和/或混淆的应用程序中使用本项目的代码（即使是部分使用）。
- 你必须向所有最终用户清晰明确地声明你使用了本项目的代码。
- 你的应用程序也必须使用相同的许可证进行授权。
