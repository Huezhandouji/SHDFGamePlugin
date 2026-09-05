# 02 · core 与 phase（状态机与各阶段）

## core 包

| 类 | 职责 |
|---|---|
| `GameContext` | 单例容器：持有插件实例与 `ConfigManager`，供全局访问（如取调度器/日志） |
| `GameState` | 枚举：IDLE / WAITING / ROLE_SELECTING / PLAYING / FINISHED |
| `GameStateMachine` | 状态机：`transitionTo` 先调旧态 `onExit` 再调新态 `onEnter`，全程打日志 |

注意：`GameContext.getPlugin()` 在 `onEnable` 里被 `GameContext.init` 初始化前不要调用。

## phase 包（阶段即玩法）

### 通用模式
- 每个阶段是**单例**；`onEnter` 注册订阅/发物品/启倒计时，`onExit` 注销/回收。
- 阶段之间互不直接引用，靠 `GameEventBus` + `GameStateMachine` 通信。

### IdlePhase（原代码）
- 订阅 join：一旦有人加入即 `transitionTo(WAITING)`。

### WaitingPhase（原代码为主，本会话做过重构与功能迭代）
- 大厅职责：玩家注册（默认观战/随机阵营）、选队箱子 GUI、准备开关、开始条件检测、倒计时。
- 侧边栏计分板显示开始条件满足情况；倒计时期间 XP 条显示剩余秒数。
- 倒计时（`GameCountdown`）：
  - 时长取 `waiting.countdown_time` 并**自动 +1**；
  - 仅最后 10 秒显示标题倒计时；剩余 60/30/20/10 秒时聊天播报；
  - 取消分两种情况：最后 10 秒内取消走 title，其余走聊天，音效规则见代码。
- `handleTeamSelect` 有"人数差"校验；`buildTeamButton` 复用一份按钮构建。
- 退出（quit）：**立即删除** PlayerStatus（本阶段无断线保护）。
- 注意：
  - 侧边栏用 `Objective.getScore(String)`（旧 API），文件头部有 `@SuppressWarnings("deprecated")`；
  - 该文件由原作者维护较多，改前先读当前版本。

### RoleSelectingPhase（本会话从骨架重写并多轮迭代）
- 进入：UNKNOWN 自动分配 → 载入角色池 → 同步地图给 RoleBridge → 重置本局重复规则/占用 →
  人数超角色池则开启允许重复 → 参战玩家进 ROLE_SELECTING 状态 + 发放 slot 0 选择物品 →
  注册选择物品/角色按钮/清除按钮 → 启动倒计时 → 订阅事件 → 传送入场。
- 选角 GUI：固定行数 = 角色所需行数 + 底部 1 行（放"清除角色"按钮），**最多 6 行**
  （MC 箱子菜单上限，勿设 7）。
- 按钮：名称 = RoleAPI `getRoleDisplayName`；Lore = 已选该角色的玩家名单；有人选则附魔光效。
- 选择：**只写 `PlayerStatus.selectedRoleId`**，不调用角色桥接设置角色；
  禁重复时按 `selectedRoleId` 自检"是否被同队他人选择"，冲突则拒绝且不关菜单。
- 清除按钮：清空 `selectedRoleId`，播放失败组合音效，保持菜单打开并刷新。
- 倒计时：`role_selection.duration`，≤0 回退 600 并自动 +1；30/20/10 秒播报。
- 退出（quit）：**立即清空 selectedRoleId**（释放角色槽位），保留 PlayerStatus 走断线保护。
- 收尾 `finishRoleSelection`：若**已无在线玩家** → 重置游戏状态并回 IDLE（有日志）；
  否则未选玩家自动分配 → 切换 PLAYING（入场部署移交 PlayingPhase.onEnter：传送当前据点出生区 + 应用角色）。
- 按钮注册表用 `HashBiMap<角色名, 按钮id>` 按阵营分两张；点击通过 `getByValue` 反查。
- 注意：本阶段不应用角色（详见 06 约定），`RoleBridge` 的占用表在战斗阶段才会被填充。

### PlayingPhase（本会话从空壳补了开局初始化/等待重生入场/真实死亡/加入退出；战斗玩法 TODO）
- `onEnter` 统一初始化本局系统（防御性清理 → `SectorManager.loadMap` / `TicketManager.init` /
  `SpawnManager.setCurrentMapConfig`）后，**所有参战玩家先视为"死亡"状态等待重生**：
  等待部署的玩家与观战者**一并传送至旁观者出生点**等待（地图世界）→ `DEPLOYING` + 进重生队列
  （按 maps.yml `attacker_respawn_time` / `defender_respawn_time` 倒计时）→ 创造模式 +
  **无粒子永久隐身效果**（非实体标志位）+ 不可碰撞，由阶段内守卫监听器禁止破坏/放置方块与攻击
  （等待期预留给未来的战术道具选择）。
- 重生倒计时结束 → **自动部署进场（无需点击物品）**：由 tick 驱动直接调
  `SpawnManager.deployPlayer(uuid, selectedRoleId)`，此时才传送当前据点本方出生区随机点 →
  ADVENTURE → 应用整场角色（`RoleBridge.setPlayerRole`，占用表此时填充）→ `IN_BATTLE`。
  部署点一律取自 maps.yml 各 objective 的出生区域；`role_selection.*_spawnpoint` 只是选角大厅坐标。
  等待期物品栏保持为空（本阶段未注册任何 GameItem；曾有的"部署进场"物品方案已移除）。
- 进入对局时经 `MessageUtil` 广播"对局将在 X 秒后开始"（X = 双方重生时间较长者的秒数）。
- **真实死亡流程**：`IN_BATTLE` 参战玩家死亡 → **取消原版死亡事件**（不掉落/无死亡界面，
  停留原地不传送）→ 死亡瞬间广播击杀信息（击杀者经 `RoleBridge.getLastDamagerUuid` 定位，
  勿用原版 `getKiller`）→ 受害者"你死了！"标题 → 进攻方死亡扣 1 票 → 原地转等待重生
  （创造 + 无粒子隐身效果）→ 每秒播报"将在 X 秒后重新部署" → 倒计时结束自动部署
  （部署时移除隐身效果恢复可见，复用出生区/角色应用逻辑）。
- join/quit：退出走断线保护（时长取 `playing.reconnect_time_limit`）；加入时保留状态恢复——
  `IN_BATTLE` 直接回场（重新应用角色）、`DEPLOYING` 恢复等待重生（倒计时已结束则立即自动部署），
  否则转观战者；**空服退出会先清理本局系统状态再回 IDLE**，防止引信/据点时限等任务跨局残留。
- TODO：炸弹安放/拆弹进度、战斗菜单、对局结束判定（见 06 文档）。

### FinishedPhase（本会话重写）
- 对局清理：停炸弹任务（`SectorManager.cleanup`）→ 清队伍/重生队列/票数/角色占用 →
  `DisconnectProtection.cancelAll()` → 关 GUI → 重置在线玩家运行时状态 → 踢出所有玩家 → 回 IDLE。
- 本阶段**不订阅 quit 事件**，踢人不会触发任何"保留状态"逻辑。
- 注意：规划中的"战绩广播 + 停留数秒再清场"结算展示**尚未实现**（见 docs/07 §3.5）。

## 阶段间状态衔接的注意点

- 断线保护是**跨阶段**的：ROLE_SELECTING 退出保留的 PlayerStatus 若在时限内阶段结束，
  玩家在 PLAYING 重连会按"状态过期"转观战者——这是设计预期。
- 各阶段 `onExit` 都要做干净（注销订阅、取消倒计时、关 GUI、清快捷栏），防止跨局残留。
