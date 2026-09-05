# 06 · 开发约定与 TODO

## 一、工作流约定

1. **先审查再写入**：每个文件改动/新增前先把代码给作者审查，通过后再落盘。
2. 每次改动后 `git add -A src`（以及资源文件），保持暂存区即"当前工作集"。
3. 大量决策以对话评审为准；本仓库文档是对照物，**代码永远是最新事实**。
4. 涉及配置文件后，**服务器端** `paper…/plugins/SHDFGamePlugin/config.yml`、`maps.yml`
   不会自动同步（`saveResource` 只在文件不存在时写入）——需手动覆盖或删除让其重建，
   否则会出现"代码新、运行旧配置"的问题（曾导致地图解析失败、倒计时不启动）。

## 二、代码风格 / 结构约定

- 分层：phase 驱动，domain 纯规则，infrastructure 实现；事件解耦用 `GameEventBus`。
- 阶段类为单例，`onEnter` 注册、`onExit` 清理（订阅/倒计时/GUI/快捷栏）。
- GameItem id 采用**阶段前缀**风格：`gameItem_<phase>_…`（如 `gameItem_roleSelectingPhase_…`）。
- **三元表达式已改为 if/else**（可读性）；新增代码建议保持 if 风格。
- 文字消息统一走 `MessageUtil`（`SHDF>>` 前缀）。
- 调度统一用 `getGlobalRegionScheduler()`。

## 三、关键语义约定（易错）

1. **tick 配置自动 +1**：`GameCountdown` 先减后回调，使用配置 tick 时在代码里 `+1`，
   配置里写真实值（如 600 = 30 秒），不要再手动加 1。
2. `role_selection.duration ≤ 0` → 回退默认 600；**只跑倒计时**，无"手动选完"模式。
3. `role_selection.reconnect_time_limit` 若大于 duration，取配置时被**钳制为 duration**。
4. **选角阶段不应用角色**：只写 `PlayerStatus.selectedRoleId`；`RoleBridge.setPlayerRole`
   只应在战斗阶段（部署/复活）调用。占用表在战斗阶段才会填充。
5. WAITING 退出**立即删除** PlayerStatus；ROLE_SELECTING / PLAYING 走断线保护
   （`DisconnectProtection`，保留一段时间，超时/对局结束才清）。
6. ROLE_SELECTING 玩家退出会**立即清空已选角色**（槽位释放，重连需重选）。
7. ChestGui 行数 **1..6**（MC 上限），需要"底部操作区"用"角色行 + 1 行"方案，勿设 7。
8. RoleAPI 空值语义：不存在角色 → `getRoleIcon` 返回 `Material.AIR`（不是 null）、
   名称/描述返回空 Component；判定"缺失"必须同时判 AIR / `Component.empty()`。
9. 依赖 `ShadowHunterRolesPlugin`（plugin.yml `depend`），RoleAPI 经
   `ServicesManager` 加载；`RoleBridge.setCurrentMapConfig` 要在应用角色前调用。
10. 阶段切换不取消断线保护（保护需跨阶段）；**对局结束**（FinishedPhase）才 `cancelAll()`。
11. **全局调度初始延迟必须 > 0**：`runAtFixedRate(plugin, task, delay, period)` 的 delay 写 0L
    会抛 `IllegalArgumentException`（曾使 PLAYING `onEnter` 中断、玩家不变创造模式）——统一写 `1L`。
12. **"死亡/等待"隐身用无粒子永久隐身效果**：`addPotionEffect(new PotionEffect(INVISIBILITY,
    Integer.MAX_VALUE, 0, false, false, false))`，部署/转观战时 `removePotionEffect(INVISIBILITY)`
    恢复可见；**不要用实体 `setInvisible` 标志位**（会被效果/原版逻辑覆盖，全库已清零）。
13. **击杀归属必须 `RoleBridge.getLastDamagerUuid(entity)`**：角色插件伤害做过特殊处理，
    勿用原版 `getKiller` 等原版途径。

## 四、TODO / 已知未接线

### 已解决（本次交接前）
- [x] PLAYING 入场模型：开局初始化链 + "对局将在 X 秒后开始"广播 + 全员等待重生
  （与观战者同处旁观者出生点；创造 + 无粒子永久隐身效果 + 禁破坏/放置/攻击）+
  重生倒计时结束自动部署至当前据点本方出生区（应用角色）。
- [x] 真实死亡流程：取消原版死亡保持原地 → 死亡瞬间击杀广播（`RoleBridge.getLastDamagerUuid`）→
  "你死了！"标题 → 进攻方死亡扣 1 票 → 每秒"将在 X 秒后重新部署" → 自动部署。
- [x] 空服回 IDLE 清理（PLAYING：`cleanupMatchState` 含 `DisconnectProtection.cancelAll`；
  ROLE_SELECTING 空服 quit 未补，任务自检在线实际无害）。
- [x] 调度初始延迟 0→1 修复（`SectorTimeLimit` / `SectorManager.startBombFuse` / 重生驱动）。
- [x] 部署点语义修正：`role_selection.*_spawnpoint` = 选角大厅；战斗部署一律用 maps.yml objective 出生区。
- [x] 隐身改用无粒子永久隐身效果（`setInvisible` 全库清零）。
- [x] config.yml `maps` 与 maps.yml 一致（map_crossfire；map_dust2 悬空问题已不存在）。
- [x] 并行会话改动已并入：RoleSelectingPhase 侧边栏、`Region.randomPoint()` 上接口 + SphereRegion 实现。

### 未完成（按建议顺序，详见 docs/07 §3）
- [x] ~~PLAYING 战斗菜单~~ 已取消：改为 slot 8 物品直连装弹/拆弹。
- [x] 安放/拆弹进度与打断：进攻方 slot 8 TNT 矿车、防守方 slot 8 剪刀，在炸弹范围内右键开始，
  按 `plant_time` / `defuse_time` 读条完成；移动/受伤/死亡/炸弹状态变化会打断；完成时接
  `SectorManager.onBombPlantSuccess` / `onBombDefuseSuccess`；PLANTED 炸弹每秒在中心点生成红色灰尘粒子。
- [ ] 据点推进：收 `BombExplodedEvent` → `isAllBombsExploded()` → 广播 +
  `TicketManager.increaseTicket(reward)` → `advanceToNextSector()` → 重新部署/刷新。
- [ ] 对局结束判定：全据点攻占（`isAllCaptured`）/ `TicketDepletedEvent` /
  `SectorTimeLimitExpiredEvent` → `endMatch(胜方)` → `transitionTo(FINISHED)`（`matchEnded` 已预留）。
- [ ] 战绩与结算展示：`PlayerStatus` + kills/deaths/bombsPlanted/bombsDefused；击杀归属用
  `RoleBridge.getLastDamagerUuid`；`FinishedPhase` 重构为"广播战绩 → 停留数秒 → 清理+踢人 → IDLE"
  （胜方静态注入；战绩读取须在 `TeamManager.reset()` 前；`onExit` 取消延时任务）。
- [ ] 死亡流程遗留：仅对敌方隐身（当前全局隐身效果）、部署点选择与 lethal/tactical 预留、
  死亡时打断进行中的安放/拆弹。
- [ ] `/sg` 调试子指令（bomb state / sector / tickets / nextround 等）。
- [ ] （低优）`refreshOpenRoleGuis` / `openRoleSelectionGui` 内容来源统一。
- [ ] 版本基线：暂存区长期未 commit，交接前建议 `git commit` 打基线（README 已存在）。

## 五、目录索引

- 源码总览与模块地图见 `README.md`
- 各模块文档：`docs/01-overview.md` ~ `docs/06-conventions-and-todo.md`
- PLAYING 现状与后续（交接参考）：`docs/07-playingphase-status.md`
