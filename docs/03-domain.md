# 03 · domain 领域层

> 领域层放"游戏规则与数据"，与 Bukkit 交互尽量薄；改动前先读当前代码（多经本会话重构）。

## team（队伍与玩家状态）

| 类 | 说明 |
|---|---|
| `ShdfTeam` | 枚举：ATTACKER（进攻/SHADOW）、DEFENDER（防守/HUNTER）、SPECTATOR、UNKNOWN（随机分配）。`isParticipant()`（前三者参战）、`isCombatant()`（仅进攻/防守） |
| `PlayerState` | 玩家状态：WAITING / ROLE_SELECTING / DEPLOYING / IN_BATTLE（DEPLOYING = 等待部署，开局全员等待与死后等待共用） |
| `PlayerStatus` | 单玩家的可变状态：uuid / team / ready / state / selectedRoleId（整场所选角色，选角阶段记录） |
| `TeamManager` | 单例，`Map<UUID, PlayerStatus>`：阵营查询/切换（发 `TeamChangedEvent`）、准备状态、人数统计、`autoAssignUnknownTeamPlayers()`（随机玩家分到少人一方）等 |

注意：
- `addPlayer(uuid, team, state)` 需要第三个参数；WAITING 阶段创建时传 `WAITING`。
- `removeAllPlayers()` 在 WAITING 进入时清场；FINISHED 清理也调用 `reset()`。

## sector（据点与炸弹）

- `Sector`：静态据点配置 = id/name + timeLimit/ticketReward + `List<BombConfig>` + 4 个 `CubeRegion`
  （出生区×2、活动区×2）。构建用 `Sector.Builder`（字段校验严格）。
- `BombConfig`（在 infrastructure.config）：炸弹静态配置（id/name/region 角点 + plant/fuse/defuse 时间）。
- `BombState`：UNPLANTED / PLANTED / EXPLODED。
- `ActiveBomb`：运行时炸弹（config + state + 引信剩余 + 任务引用），`plant/defuse/explode/tickFuse/stopFuse`。
- `SectorTimeLimit`：据点时限倒计时，归零发布 `SectorTimeLimitExpiredEvent`。
- `SectorManager`（单例）：
  - 当前据点 `Map<bombId, ActiveBomb>`（LinkedHashMap 保序）；
  - `onBombPlantSuccess(bombId)` / `onBombDefuseSuccess(bombId)`：切换单弹状态并发布事件；
  - 引信归零 → 该弹 EXPLODED + 发布 `BombExplodedEvent`（**不推进**，由 PlayingPhase 收齐后推进）；
  - `isAllBombsExploded()`、`advanceToNextSector()`、`getCurrentSectorBombs()` 等查询；
  - `cleanup()` 停掉所有引信/时限任务（FINISHED 清理、PlayingPhase 防御性/空服清理调用）。

注意：
- 炸弹是"配置/运行时分离"的：`BombConfig` 不可变，`ActiveBomb` 才带状态。
- `Sector.getBomb()` 已不存在，用 `getBombs()`；范围判定用 `bomb.getRegion()`（CubeRegion）。
- `loadMap(sectors)` 已由 `PlayingPhase.onEnter` 调用（防御性清理后），激活第 1 据点并启动据点时限。
- PlayingPhase 尚未接入 `advanceToNextSector`/`isAllBombsExploded`——这是 TODO
  （引信调度初始延迟 bug 已修，安放后引信可正常走）。

## spawn（重生与部署）

- `SpawnManager`（单例）：
  - `respawnQueue: Map<UUID, PendingRespawn>`（死亡后按阵营等待不同 tick）；
  - `addPlayer(uuid, team)` 死亡入队、`update()` 每 tick 递减、`canRespawn`/`getRemainingRespawnTime`；
  - `deployPlayer(uuid, roleId)`：传送当前据点本方出生区随机点 → ADVENTURE → `state=IN_BATTLE` → 经 RoleBridge 应用角色 → 出队。
- 现状：`addPlayer` 由 PLAYING 入场（全员等待）与死亡流程调用；`update()` 由 PlayingPhase
  每 tick 驱动；`deployPlayer(uuid, selectedRoleId)` 由 PlayingPhase 自动部署调用
  （部署点 = 当前据点本方出生区）。
- 注意：部署角色应使用 `PlayerStatus.selectedRoleId`；部署点选择与 lethal/tactical 接口未实现（预留）。

## ticket（票数）

- `TicketManager`：进攻方票数（死亡扣票、爆炸加票，设计如此），耗尽发布 `TicketDepletedEvent`。
- `init(initial, max)` / `reset()` / `decrease/increase` / `isDepleted`。
- 现状：`init` 已由 `PlayingPhase.onEnter` 调用；`decreaseTicket` 已由死亡流程调用
  （进攻方玩家死亡扣 1）；`increaseTicket`（据点奖励）待据点推进里程碑接入。
- 尚未有代码监听 `TicketDepletedEvent` 结束对局（TODO：对局结束判定）。
