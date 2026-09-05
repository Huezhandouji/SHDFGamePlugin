# 07 · PLAYING 现状与后续（交接参考）

> 交接文档：只描述事实与待办，**代码永远是最新事实**，改动前先读代码。
> 编写节点：对局"入场等待重生 + 真实死亡"流程收尾后，准备切换会话继续开发 PLAYING。

## 0. 一句话现状

能"进对局、等重生、自动部署、战死→等待→自动复活"，但**炸弹玩法与胜负结算未接**：
没有战斗菜单/安放拆弹/据点推进/结束判定。进攻方扣票已生效，但票尽**不会**结束对局。

## 1. 已实现行为链（代码为准）

### 1.1 进入 PLAYING（`PlayingPhase.onEnter`）
1. `initMatchSystems()`：防御性清理（`SectorManager.cleanup` / `SpawnManager.clearAll` /
   `TicketManager.reset`）→ `SectorManager.loadMap(sectors)`（激活第 1 据点，**据点时限立即开始走**）
   → `TicketManager.init(initial, max)` → `SpawnManager.setCurrentMapConfig(mapConfig)`。
2. `enterAwaitingRespawn()`：参战玩家与观战者**一并传送至旁观者出生点**（地图世界）；
   参战者：清背包 → **创造模式 + 无粒子永久隐身效果**（`PotionEffect(INVISIBILITY, MAX_VALUE, 0,
   false,false,false)`，非实体标志位）+ 不可碰撞 → `DEPLOYING` + 进重生队列（按地图
   `attacker_respawn_time` / `defender_respawn_time` 倒计时）；观战者置 SPECTATOR。
3. `broadcastMatchStart()`：MessageUtil 广播 **"对局将在 X 秒后开始!"**（X = 双方重生时间较长者的秒数）。
4. 注册阶段内 Bukkit 监听器：等待守卫（禁破坏/放置/攻击，仅对 DEPLOYING）、死亡监听。
5. 启动每 tick 重生驱动：`SpawnManager.update()` 递减 → 死亡者每秒播报 → 就绪自动部署。
6. 订阅内部事件 join/quit。

### 1.2 自动部署（`autoDeploy` → `SpawnManager.deployPlayer`）
重生倒计时结束**自动**执行（无点击物品、无 GameItem）：传送**当前据点本方出生区**随机点 →
ADVENTURE → `IN_BATTLE` → `RoleBridge.setPlayerRole(uuid, selectedRoleId)`（占用表此时填充）→
移除无粒子隐身效果 → 复位血量/饥饿 → 可见可碰撞。失败每 tick 静默重试（`deployFailureLogged` 去重日志）。

### 1.3 真实死亡（`CombatDeathListener` / `handlePlayerDeath`）
仅处理 **IN_BATTLE 参战玩家**：**取消原版死亡事件**（不掉落、无死亡界面、**原地不传送**）→ 复位血量/火焰 →
**死亡瞬间广播击杀信息**（`RoleBridge.getLastDamagerUuid` 定位击杀者；无有效击杀者报"阵亡"）→
受害者 **"你死了！"** 标题 → **进攻方死亡扣 1 票** → 原地转等待重生（同上隐身表现）→
每秒播报 **"将在 X 秒后重新部署"**（仅死亡玩家，`deathCountdownLastSecond` 去重）→ 倒计时结束自动部署。

### 1.4 加入 / 退出 / 重连
- 重连：取消断线保护；`IN_BATTLE` → `restoreCombatant`（本方出生区 + 移除隐身效果 + 重应用角色）；
  `DEPLOYING` → `restoreAwaitingRespawn`（回旁观者点；倒计时若已结束立即自动部署）。
- 新加入者 / 状态过期 → `makeSpectator`（清重生队列、移除隐身效果、清角色占用、SPECTATOR 观战点）。
- 退出：空服 → `cleanupMatchState()` + 回 IDLE（含 `DisconnectProtection.cancelAll`）；否则挂断线保护
  （`playing.reconnect_time_limit`），超时清 PlayerStatus / 重生队列 / 角色占用。
- `onExit`：注销订阅与两个 Bukkit 监听器、停 tick 任务、清各集合、关 GUI、清快捷栏 slot 0/8。

## 2. 已修复 / 已澄清的关键点（勿回退）

- **调度初始延迟必须 > 0**：`GlobalRegionScheduler.runAtFixedRate(..., 0L, 1L)` 抛
  `IllegalArgumentException`，曾使 `onEnter` 中断（玩家不变创造）。`SectorTimeLimit` /
  `SectorManager.startBombFuse` / 重生驱动已统一为 `1L`。
- **部署点语义**：`role_selection.*_spawnpoint` = 选角大厅坐标；战斗部署一律用 maps.yml
  各 objective 的出生区（`Sector.getAttacker/DefenderSpawnRegion`）。
- **隐身方案**：无粒子永久隐身**效果**，部署/转观战/回场时 `removePotionEffect(INVISIBILITY)`；
  全库已无 `setInvisible`（标志位会被效果/原版逻辑覆盖，勿用）。
- **击杀归属**：必须 `RoleBridge.getLastDamagerUuid(entity)`（角色插件伤害特殊处理），勿用原版 `getKiller`。
- **入场模型**：开局=全员"死亡"等待重生（观战点等待）；自动部署；击杀广播时机=死亡瞬间（已拍板）；
  扣票=仅进攻方玩家死亡扣 1。
- `RoleSelectingPhase.deployCombatants` 已删除（入场部署移交 PlayingPhase）；选角侧边栏与
  `Region.randomPoint()` 上接口为并行会话改动，已并入暂存区。

## 3. 本阶段还需要做（建议顺序）

1. **战斗菜单**：slot 8 物品（id 前缀 `gameItem_playingPhase_`）+ 按阵营/炸弹状态动态按钮，
   用 `ChestGui.setSlot` 刷新（参考 RoleSelectingPhase 的按钮注册表 + `HashBiMap<bombId,itemId>` 模式）。
2. **安放/拆弹进度任务与打断**（移动/受击/死亡）→ 接 `SectorManager.onBombPlantSuccess` /
   `onBombDefuseSuccess`（引信调度 bug 已修，安放后可正常引爆）。
3. **据点推进**：订阅炸弹三事件 → 全部爆炸后 `TicketManager.increaseTicket(reward)` + 广播 →
   `advanceToNextSector()` → 重新部署/刷新菜单；`isAllCaptured()` → 进攻方胜。
4. **对局结束判定**：`TicketDepletedEvent` / `SectorTimeLimitExpiredEvent` / 全据点攻占 →
   `endMatch(胜方)` → `transitionTo(FINISHED)`（`matchEnded` 字段已预留防竞态）。
5. **战绩与结算展示**（此前已拍板未实现）：`PlayerStatus` + kills/deaths/bombsPlanted/bombsDefused；
   击杀归属用 `RoleBridge.getLastDamagerUuid`；`FinishedPhase` 重构为"广播战绩 → 停留数秒 →
   清理+踢人 → IDLE"（胜方经静态 `setMatchResult` 注入；读战绩须在 `TeamManager.reset()` 前；
   `onExit` 取消延时任务）。
6. **死亡流程遗留**：仅对敌方隐身（当前全局隐身效果）、部署点选择与 lethal/tactical 预留、
   死亡时打断进行中的安放/拆弹。
7. **`/sg` 调试子指令**（bomb state / sector / tickets / nextround 等）。
8. **版本基线**：暂存区已积压多轮改动，建议 `git commit` 打基线后再继续。

## 4. 已知边界 / 潜在问题

- 据点时限开局即走且无人监听（`SectorTimeLimitExpiredEvent` 无订阅者）。
- `TicketDepletedEvent` 已能触发（进攻方扣票）但无人结束对局。
- 等待期玩家在旁观者点可飞行观察全图（隐身）；死亡者留在死亡位置等待。
- 死亡被取消 → 不掉落、不掉经验；击杀者无击杀/助攻计数（待 3.5）。
- 非 IN_BATTLE 玩家（WAITING/ROLE_SELECTING 意外死亡、观战者）不被接管，走原版流程。
- `FinishedPhase` 目前仍是"立即清理 + 踢人"，无战绩停留（待 3.5 重构）。

## 5. 状态与数据参考

- `PlayerState`：WAITING / ROLE_SELECTING / **DEPLOYING**（=等待部署，开局全员与死后共用）/ IN_BATTLE。
- 地图 `attacker_respawn_time` / `defender_respawn_time` 同时充当"开局部署倒计时"与"死亡重生时间"。
- 阶段内维护集合：`deployFailureLogged`（部署失败日志去重）、`deathCountdownLastSecond`（死亡播报去重）。
- 事件订阅（bus）：仅 join/quit；Bukkit 监听器（阶段注册）：等待守卫 + 死亡。
