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
  否则未选玩家自动分配 → 部署（传送+IN_BATTLE）→ PLAYING。
- 按钮注册表用 `HashBiMap<角色名, 按钮id>` 按阵营分两张；点击通过 `getByValue` 反查。
- 注意：本阶段不应用角色（详见 06 约定），`RoleBridge` 的占用表在战斗阶段才会被填充。

### PlayingPhase（本会话从空壳补了加入/退出；战斗玩法 TODO）
- 当前只有 join/quit 处理：退出走断线保护（时长取 `playing.reconnect_time_limit`）；
  加入时保留的 `IN_BATTLE` 战斗身份恢复（传回当前据点本方出生区），否则转观战者。
- TODO：炸弹安放/拆弹进度、战斗菜单、对局结束判定（见 06 文档）。

### FinishedPhase（本会话重写）
- 对局清理：停炸弹任务（`SectorManager.cleanup`）→ 清队伍/重生队列/票数/角色占用 →
  `DisconnectProtection.cancelAll()` → 关 GUI → 重置在线玩家运行时状态 → 踢出所有玩家 → 回 IDLE。
- 本阶段**不订阅 quit 事件**，踢人不会触发任何"保留状态"逻辑。

## 阶段间状态衔接的注意点

- 断线保护是**跨阶段**的：ROLE_SELECTING 退出保留的 PlayerStatus 若在时限内阶段结束，
  玩家在 PLAYING 重连会按"状态过期"转观战者——这是设计预期。
- 各阶段 `onExit` 都要做干净（注销订阅、取消倒计时、关 GUI、清快捷栏），防止跨局残留。
