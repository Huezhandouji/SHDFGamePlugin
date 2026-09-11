# 08 · PLAYING 阶段模块地图

本文件是 t19（PlayingPhase 行为等价拆分）的产物，也是后续 t20/t21/t22/t23 的**边界约定**：
这些任务的 `inScope` 直接指向下表的路径，改动请落在对应模块，不要跨模块顺手重构。

拆分前后**行为完全等价**（纯结构搬移，不修 bug、不加功能）；拆分前 `phase/PlayingPhase.java` 约 1059 行，
拆分后门面 297 行（t19 交付修订版，满足 ≤ 300 的目标；t20 完善后为 313 行，见 §一 注）。等价性核对方法与结论见 §6。

---

## 一、模块总览

| 文件 | 职责 | t19 交付时行数 |
|---|---|---|
| `phase/PlayingPhase.java` | **门面**：`onEnter/onExit` 编排、事件订阅/退订、join/quit 路由、右键交互路由、控制器装配与生命周期、本局系统初始化/清理 | 297 |
| `phase/playing/MatchSessionState.java` | 本局共享运行时状态：进度表、标志位、任务/监听器句柄 | 184 |
| `phase/playing/DeploymentController.java` | 等待态表现、重生计时驱动与自动部署、重连恢复、观战转换、等待期行为守卫 | 374 |
| `phase/playing/DeathHandler.java` | 死亡接管：取消原版死亡、击杀广播、扣票、清角色占用、转入等待重生 | 130 |
| `phase/playing/BombInteractionController.java` | 装弹/拆弹读条（`BombProgress` 内部类）、范围内定位、进度显示、打断校验、完成回调、移动冻结、已安放炸弹粒子 | 321 |
| `phase/playing/PlayingItemFactory.java` | 战斗物品构建与发放：slot 7 装弹/拆弹、slot 8 战斗菜单（含不可丢弃/不可移动） | 123 |

> **行数口径**：上表为 t19 交付修订版（`PlayingPhase.java` mtime 20:13:34）的实测值，用
> `[System.IO.File]::ReadAllLines` / 换行符计数得到，与编辑器、grep 的行号一致。
> 注意 harness 里 `(Get-Content <file>).Count` 会少算若干行（本工程实测每个文件少 5~24 行），**不要用它核对行数**。
> **现状提醒**：t20 完成后门面为 **313 行**（新增 `SectorProgressController` 订阅接线与空服判定助手方法），
> 已超出本文档的 300 行原始目标区间；t21/t23 继续改门面时，请把"编排之外"的逻辑继续下沉到对应模块。

### 依赖方向（保持单向）

```
PlayingPhase（门面）
  ├─> DeploymentController ──┐
  ├─> DeathHandler ──────────┼─> MatchSessionState（纯状态容器，不回调任何控制器）
  ├─> BombInteractionController ─┘
  └─> PlayingItemFactory（被以上三者按需调用）
DeathHandler ─> DeploymentController.setAwaitingLook(...)   // 死亡转入等待重生
DeploymentController / DeathHandler / BombInteractionController ─> PlayingItemFactory（发物品）
```

所有控制器/门面为**单例**（`getInstance()`），与工程既有相位类/管理器风格一致。

---

## 二、逐类职责与对外接口

### 1. `phase/PlayingPhase.java`（门面）

职责（也是本类仅有的内容，新增玩法不要在门面里写）：

- `onEnter()`：日志 → `MatchSessionState.setMatchEnded(false)` → `initMatchSystems()` →
  入场/广播/守卫/死亡监听/冻结守卫/重生驱动 → 事件订阅 → 物品注册 + 右键订阅 → 读条驱动 + 粒子任务；
- `onExit()`：**按拆分前的原顺序**逐一退订/停任务/清状态（顺序本身是行为的一部分）；
- `initMatchSystems()`：防御性清理（`SectorManager.cleanup` / `SpawnManager.clearAll` / `TicketManager.reset`）
  后按 `MapConfig` 载入据点、票数、重生配置；未选地图只告警并返回；
- `cleanupMatchState()`：空服回 IDLE 前的本局清理（据点/时限、PlayerStatus、重生队列、票数、角色占用、
  断线保护、GUI）；
- `handlePlayerJoin/handlePlayerQuit`：join 路由到 `restoreCombatant` / `restoreAwaitingRespawn` / `makeSpectator`；
  quit 走空服清理或断线保护；断线保护过期回调里清 `MatchSessionState` 的残留项；
- `handleRightClickGameItem` + `openBattleMenu`：右键路由——战斗菜单任意状态可开；装弹/拆弹需
  `IN_BATTLE` 且阵营匹配，通过后交给 `BombInteractionController.tryStartBombProgress`；
- `subscribeEvents/unsubscribeEvents`、`subscribeRightClick/unsubscribeRightClick`：持有三个 `Subscription`。

对外接口：`GamePhase.onEnter/onExit`、`getInstance()`（`core/GameStateMachine` 注册）。
本类**没有**对外暴露内部状态的 getter，后续任务通过 `MatchSessionState` 读状态。

> 已知待办（t20）：`handlePlayerQuit` 的空服判定 `Bukkit.getOnlinePlayers().isEmpty()` 在 quit 事件触发时
> 退出者仍在线，语义需改为"除退出者外无人在线"。改动位置就在本类。

### 2. `phase/playing/MatchSessionState.java`（共享运行时状态）

单例状态容器，**只存数据**。字段与访问器：

| 字段 | 访问器 | 读写方 |
|---|---|---|
| `Map<UUID,BombProgress> activeProgresses` | `getActiveProgresses()` / `clearActiveProgresses()` | `BombInteractionController`（增删改查）、`DeathHandler`（死亡打断）、门面 `onExit`（清空） |
| `Set<UUID> deployFailureLogged` | `addDeployFailureLogged`（返回是否首次）/ `removeDeployFailureLogged` / `clearDeployFailureLogged` | `DeploymentController`、断线保护回调、门面 `onExit` |
| `Map<UUID,Integer> deathCountdownLastSecond` | `hasDeathCountdown` / `getDeathCountdownSeconds` / `putDeathCountdownSeconds` / `removeDeathCountdown` / `clearDeathCountdown` | `DeploymentController`（播报+重置）、`DeathHandler`（`-1` 占位）、断线保护回调、门面 `onExit` |
| `boolean matchEnded` | `isMatchEnded()` / `setMatchEnded(boolean)` | 门面 `onEnter` 置 false；**t20 的 SectorProgressController 置 true**；三个模块读 |
| `ScheduledTask respawnTickTask` | `get/setRespawnTickTask` | `DeploymentController` |
| `ScheduledTask bombProgressTickTask` | `get/setBombProgressTickTask` | `BombInteractionController` |
| `ScheduledTask bombParticleTask` | `get/setBombParticleTask` | `BombInteractionController` |
| `Listener guardListener` | `get/setGuardListener` | `DeploymentController` |
| `Listener deathListener` | `get/setDeathListener` | `DeathHandler` |
| `Listener freezeListener` | `get/setFreezeListener` | `BombInteractionController` |

类型上引用 `BombInteractionController.BombProgress`（同包内部类）；除此之外不依赖任何控制器，不存在状态分叉副本。
新增本局级共享状态（例如 t21 的间歇期标志）请加在这里，**不要**在控制器里另起一份。

### 3. `phase/playing/DeploymentController.java`

对外接口（均 `public`，供门面/DeathHandler 调用）：

| 方法 | 说明 |
|---|---|
| `enterAwaitingRespawn()` | 全员进入等待重生：参战者 `setAwaitingLook` + DEPLOYING + 进重生队列；观战者清背包转旁观；统一传送旁观者出生点 |
| `setAwaitingLook(Player)` | 创造模式 + 无粒子永久隐身效果 + `setCollidable(false)` + 清背包（保留 slot 8 战斗菜单） |
| `isAwaitingRespawn(Player)` | 守卫判定谓词（`state == DEPLOYING`） |
| `startRespawnTickTask()` / `stopRespawnTickTask()` | 每 tick 驱动：`matchEnded` 短路 → `SpawnManager.update()` → 死亡倒计时播报 → `autoDeployReadyPlayers()` |
| `autoDeployReadyPlayers()` / `autoDeploy(Player, PlayerStatus)` | 倒计时结束即自动部署；失败保留 DEPLOYING 下一 tick 重试，失败日志经 `deployFailureLogged` 去重；成功则复位隐身/碰撞/生命/饱食 + 发 slot 7/8 物品 + 提示音 |
| `sendDeathCountdownMessages()` | 仅对"死后等待"玩家每秒播报一次"将在 X 秒后重新部署"（开局等待不播报） |
| `broadcastMatchStart()` | 以双方重生时间较长者为"对局将在 X 秒后开始" |
| `registerGuard()` / `unregisterGuard()` | `AwaitingGuardListener`：等待期禁破坏/放置/攻击 |
| `restoreCombatant(Player, PlayerStatus)` | 断线重连回场：传送当前据点本方出生区 + 应用角色 + 发物品 + 恢复可见 |
| `restoreAwaitingRespawn(Player, PlayerStatus)` | 断线重连回等待态；若离线期间倒计时已结束则立即 `autoDeploy` |
| `applyRole(Player, PlayerStatus)` | 经 `RoleBridge.setPlayerRole` 应用本场角色；失败仅告警 |
| `makeSpectator(Player)` | 新来者/状态过期者转观战：传送观战出生点、清队列与角色占用、注册 SPECTATOR |

日志文本沿用原样（`[PlayingPhase] ...` 前缀未改，便于比对新旧日志）。

### 4. `phase/playing/DeathHandler.java`

| 方法 | 说明 |
|---|---|
| `handlePlayerDeath(PlayerDeathEvent)` | 仅处理 `IN_BATTLE` 参战玩家；`matchEnded` 短路；中断读条；取消原版死亡并复位生命/火焰；广播击杀；标题；进攻方扣 1 票；`RoleBridge.clearPlayerRole`；转等待重生 + 标记死后倒计时 |
| `buildKillMessage(Player victim)` | 经 `RoleBridge.getLastDamagerUuid` 定位击杀者（**勿用原版 `getKiller`**），否则报"阵亡了" |
| `register()` / `unregister()` | `CombatDeathListener`（`PlayerDeathEvent`）随阶段注册/注销 |

### 5. `phase/playing/BombInteractionController.java`

| 成员 | 说明 |
|---|---|
| `BombProgress`（`public static` 内部类） | `uuid / bombId / isPlant / totalTicks / remainingTicks / startHealth`；构造时记录 `player.getHealth()` 作为打断基准 |
| `tryStartBombProgress(Player, boolean isPlant)` | 已有读条 → 取消；否则 `findBombInRange` + 状态校验（装弹需 UNPLANTED、拆弹需 PLANTED）后开始读条 |
| `cancelActiveProgress(Player)` | 主动取消（再次右键） |
| `startBombProgressTickTask()` / `stopBombProgressTickTask()` | 每 tick 推进：离线清理 → 失效打断 → 递减 → 归零完成 → ActionBar 进度 |
| `registerFreezeGuard()` / `unregisterFreezeGuard()` | `BombProgressFreezeListener`：读条期间冻结 x/y/z（保留视角） |
| `startBombParticleTask()` / `stopBombParticleTask()` | 每 20 tick 为 PLANTED 炸弹中心生成红色 `DUST` 粒子 |
| （私有）`isBombProgressValid` / `showBombProgress` / `cancelBombProgress` / `completeBombProgress` / `findBombInRange` | 打断校验（在线/状态/阵营/范围/炸弹状态/受伤/`matchEnded`）、进度显示、完成时调 `SectorManager.onBombPlantSuccess` / `onBombDefuseSuccess` |

> t21 的"间歇期炸弹门闩"要接的两条路径就在这里：**开始路径** `tryStartBombProgress` 与
> **失效校验路径** `isBombProgressValid`。

### 6. `phase/playing/PlayingItemFactory.java`

| 成员 | 说明 |
|---|---|
| `PLANT_ITEM_ID` / `DEFUSE_ITEM_ID` / `BATTLE_MENU_ITEM_ID` | `public static final`；`gameItem_playingPhase_*` 阶段前缀风格 |
| `registerPlayingItems()` / `unregisterPlayingItems()` | 向 `GameItemRegistry` 注册/注销三个物品（`canDrop(false).canMove(false)`），右键统一发布 `RightClickGameItemEvent` |
| `giveBombInteractionItem(Player, ShdfTeam)` | slot 7：ATTACKER=TNT 矿车（装弹）、DEFENDER=剪刀（拆弹） |
| `giveBattleMenuItem(Player)` | slot 8：悬挂式橡木告示牌"战斗菜单" |
| `clearInventoryKeepBattleMenu(Player)` | 清背包但**跳过 slot 8**（战斗菜单在等待/死亡状态保留） |
| （私有）`createPlantItem` / `createDefuseItem` / `createBattleMenuItem` | 物品栈与显示名/lore/GameItem id |

---

## 三、注册/注销配对清单（生命周期红线）

| 资源 | 注册点 | 注销点 |
|---|---|---|
| `ShdfPlayerJoinEvent` 订阅 | 门面 `subscribeEvents()` | 门面 `unsubscribeEvents()` |
| `ShdfPlayerQuitEvent` 订阅 | 门面 `subscribeEvents()` | 门面 `unsubscribeEvents()` |
| `RightClickGameItemEvent` 订阅 | 门面 `subscribeRightClick()` | 门面 `unsubscribeRightClick()` |
| `AwaitingGuardListener` | `DeploymentController.registerGuard()` | `DeploymentController.unregisterGuard()`（遍历 `HandlerList` 注销，句柄置 null） |
| `CombatDeathListener` | `DeathHandler.register()` | `DeathHandler.unregister()` |
| `BombProgressFreezeListener` | `BombInteractionController.registerFreezeGuard()` | `BombInteractionController.unregisterFreezeGuard()` |
| 重生驱动任务 | `startRespawnTickTask()` | `stopRespawnTickTask()` |
| 读条驱动任务 | `startBombProgressTickTask()` | `stopBombProgressTickTask()` |
| 粒子任务 | `startBombParticleTask()` | `stopBombParticleTask()` |
| 三个 GameItem | `PlayingItemFactory.registerPlayingItems()` | `PlayingItemFactory.unregisterPlayingItems()` |

**约定**：新增任何监听器/任务/GameItem，必须在同一模块内同时提供 register/unregister，
并由门面 `onEnter/onExit` 成对调用——本工程历史上出现过"跨局残留监听器/任务"事故。

## 四、调度约定（不得回退）

三个周期任务全部使用 `getGlobalRegionScheduler().runAtFixedRate(plugin, task, delay, period)`，
**初始 delay 必须 > 0**（写 0L 会抛 `IllegalArgumentException`，曾导致 PLAYING `onEnter` 中断）：

| 任务 | delay | period |
|---|---|---|
| 重生驱动 | `1L` | `1L` |
| 读条驱动 | `1L` | `1L` |
| 已安放炸弹粒子 | `1L` | `20L` |

> 该约束在拆分后保持不变；t21/t23 若新增刷新任务，同样遵守（并做节流，不要每 tick 重建 BossBar/侧边栏）。

---

## 五、后续功能归属（t20 / t21 / t22 / t23）

以下文件**本任务（t19）未创建**，位置与接口约定如下：

| 预留文件 | 归属任务 | 应承载的内容 | 与现有模块的接口 |
|---|---|---|---|
| `phase/playing/SectorProgressController.java` | t20 | 订阅 `BombExplodedEvent` / `TicketDepletedEvent` / `SectorTimeLimitExpiredEvent`；据点推进（广播 + `increaseTicket(奖励)` + `advanceToNextSector()`，取值需在推进前）；`endMatch(胜方)` 幂等（票尽/超时=防守方胜，全据点攻占=进攻方胜） | 读/写 `MatchSessionState.isMatchEnded/setMatchEnded`；订阅与退订由门面 `onEnter/onExit` 接线（沿用现有 `Subscription` 风格 + 空判）；推进后**不做全员重部署**（已拍板） |
| `phase/playing/IntermissionController.java` | t21 | 间歇期状态机：当前据点全部炸弹爆炸 → 间歇 ≥ `sector_advance_interval`（地图级配置）→ 正式开启下一个据点；管理剩余倒计时；炸弹门闩（开始 + 进行中两条路径）；战斗菜单"切换角色"按钮状态 | 门闩接入 `BombInteractionController.tryStartBombProgress` 与 `isBombProgressValid`；按钮构建在 `PlayingItemFactory`；状态存 `MatchSessionState`；时限起点由 `domain/sector/SectorManager` 的"激活/启表"拆分配合 |
| `phase/playing/MatchDisplayBridge.java` | t23 | BossBar 据点链与炸弹明细、战斗侧边栏、slot 8 指南针（左键切炸弹 / 右键菜单）的生命周期与刷新节流 | 由门面在 `onEnter/onExit` 启停；物品构建改造在 `PlayingItemFactory`；`onExit` 必须清空 BossBar 与 sidebar objective（防跨阶段残留） |
| `phase/FinishedPhase.java` + `DeathHandler` + `BombInteractionController` | t22 | 内存战绩统计（kills/deaths/bombsPlanted/bombsDefused）、`config.yml` 的 `finish_display_time`、聊天栏结算、`FinishedPhase.onExit` 取消延时任务 | 记分点：`DeathHandler`（击杀者经 `RoleBridge.getLastDamagerUuid`、自身死亡）与 `BombInteractionController.completeBombProgress`（安放/拆除完成）；战绩读取必须在 `TeamManager.reset()` 之前 |

改动禁区（与各任务 `outOfScope` 一致）：`core/`、`WaitingPhase`、`RoleSelectingPhase`（t8 负责其稳定性）、
`GameEventBus`、`infrastructure/display/`（t7/t23）、`command/`（t11）。

---

## 六、行为等价性核对方法（t19 自查记录）

1. **全量编译**：67 个源文件用基线依赖（`paper-api-1.21.11-R0.1-SNAPSHOT` + `ShadowHunterRolesPlugin-1.0.0`
   + Gradle 缓存中的全部传递依赖）整体 `javac -encoding UTF-8` 编译，退出码 0，无编译错误。
   （t19 当时 Gradle 包装器因 `C:\Users\ROG\.gradle` 不可写而无法运行；**后来队长给出了沙箱内可用配方**：
   `$env:GRADLE_USER_HOME="<repo>\.gradle-work"`、`$env:GRADLE_RO_DEP_CACHE="%USERPROFILE%\.gradle\caches"`、
   `$env:GRADLE_OPTS="-Dorg.gradle.vfs.watch=false"`，然后 `.\gradlew jar --console=plain`——
   t20 已用该配方实跑得到 `BUILD SUCCESSFUL` / 退出码 0。源文件数当时 67，t20 之后为 76。）
2. **逐行双向 diff**：取拆分前的 `PlayingPhase.java`（`git show HEAD:...`），与 6 个新文件的代码行
   （去注释、去空白归一化）做双向比对：
   - 原文件 → 新文件：104 行"未匹配"，逐条核对**全部**属于以下三类——字段/常量迁移、方法可见性从
     `private` 改 `public`、调用点由 `foo()` 改为 `Controller.getInstance().foo()` 或
     `activeProgresses`/`matchEnded`/`deployFailureLogged`/`deathCountdownLastSecond` 改为
     `MatchSessionState` 访问器；**没有遗漏分支、条件、消息文本或调度参数**。
   - 新文件 → 原文件：新增行仅为单例、访问器、`getInstance()` 调用点与上述调用点改写，
     **无新增逻辑、无新增玩家可见行为**。
3. 监听器注册/注销、任务启停、物品注册/注销均成对且顺序与拆分前一致（见 §3、§4）。
