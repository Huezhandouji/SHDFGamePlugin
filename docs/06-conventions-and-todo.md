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

## 四、TODO / 已知未接线

- [ ] **PlayingPhase 战斗玩法**：
  - 战斗菜单（slot 8 物品 + 按阵营/炸弹状态动态按钮，用 ChestGui.setSlot 刷新）；
  - 安放/拆弹进度任务与打断（移动/受击/死亡）；
  - 接 `SectorManager.onBombPlantSuccess/onBombDefuseSuccess`；
  - 收 `BombExplodedEvent` → `isAllBombsExploded()` 后 `advanceToNextSector()` + 发 ticket_reward。
- [ ] **对局结束判定**：全据点爆完 / `TicketDepletedEvent` / `SectorTimeLimitExpiredEvent`
  → `transitionTo(FINISHED)`（FinishedPhase 清理已就绪）。
- [ ] **死亡与部署流程**：PlayerDeathListener、`PlayerState.DEPLOYING`、
  扣票、隐藏敌人、用 `selectedRoleId` 部署、部署点选择与预留的 lethal/tactical 接口。
- [ ] `/sg` 调试子指令（bomb state / sector / nextround 等，便于无 GUI 验证）。
- [ ] 空服回 IDLE 时是否补 `DisconnectProtection.cancelAll()`（当前任务自检在线，实际无害）。
- [ ] `refreshOpenRoleGuis` 与 `openRoleSelectionGui` 过滤逻辑已一致；留意后续 GUI 内容来源统一。
- [ ] `map_dust2` 在 config.yml maps 列表里但 maps.yml 未定义——属数据问题，加载会跳过并告警。
- [ ] 版本基线：暂存区长期未 commit，建议定期 `git commit` 打基线（README 已存在）。

## 五、目录索引

- 源码总览与模块地图见 `README.md`
- 各模块文档：`docs/01-overview.md` ~ `docs/05-command-listener-util.md`
