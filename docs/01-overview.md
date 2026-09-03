# 01 · 总体架构与数据流

## 1. 分层

```
        Bukkit/Paper 事件
              │
   listener（薄适配） ──→ GameEventBus（内部事件总线）
              │                    │ 订阅
        command（/sg）       phase（当前阶段，按 GameState 路由）
                                   │
                     ┌─────────────┴──────────────┐
                 domain（纯规则）        infrastructure（技术实现）
```

- **core**：进程级容器与状态机，是"游戏在哪个阶段"的唯一权威。
- **phase**：每个 `GameState` 对应一个 `GamePhase` 单例。阶段进入/退出时
  自行注册/注销事件订阅、启停倒计时、发/收物品。**所有对局逻辑都在这里驱动。**
- **domain**：纯游戏数据与规则（队伍、炸弹、重生、票数），不直接处理玩家交互细节。
- **infrastructure**：配置解析、事件总线、物品/GUI、区域运算、对外插件桥接等。
- 依赖方向：phase → domain / infrastructure；domain 尽量不反向依赖。

## 2. 状态机

```
IDLE ──(有人加入)──▶ WAITING ──(倒计时结束)──▶ ROLE_SELECTING ──(倒计时结束)──▶ PLAYING ──(对局结束)──▶ FINISHED
  ▲                                                                    │                    │
  └──────────────────────(空服 / 无玩家收尾)───────────────────────────┘                    └──(清理+踢人)──▶ IDLE
```

- `GameStateMachine.transitionTo`：先调旧状态 `onExit`，再调新状态 `onEnter`。
- 空服回 IDLE 出现在各阶段的 quit 处理与 `RoleSelectingPhase.finishRoleSelection` 兜底中。
- `FinishedPhase.onEnter` 执行对局清理后**主动回 IDLE**。

## 3. 两条事件通道

1. **Bukkit 原生事件**（`listener` 包）→ 转成插件内部事件发到 `GameEventBus`：
   `PlayerJoinEvent → ShdfPlayerJoinEvent`、`PlayerQuitEvent → ShdfPlayerQuitEvent`。
2. **物品/GUI 交互**：`InteractionManager`（Bukkit Listener）根据物品 PDC 找到
   `GameItem`，组件回调里 `GameEventBus.publish(RightClickGameItemEvent / InventoryClickGameItemEvent)`，
   由当前阶段订阅处理。

> 只有**当前阶段**订阅了对应事件，因此"谁在听"随阶段切换而变化；
> 阶段退出时必须注销订阅（`unsubscribeEvents`），否则会串阶段。

## 4. 配置体系

- `config.yml`：全局（waiting / role_selection / playing / maps 列表）。
- `maps.yml`：地图定义（每地图：票数、重生时间、角色池、据点 objectives；
  每个 objective：`bombs` 列表 + 双方出生区/活动区 + ticket_reward + time_limit）。
- `ConfigManager` 启动与 `/sg config reload` 时加载；**必要字段缺失会抛异常使插件启动失败**；
  单张地图解析出错则跳过该图并记日志（不影响其他地图）。

## 5. 关键约定速览（详见 06 文档）

- tick 类配置在代码使用处**自动 +1**，配置里写真实值即可。
- 角色选择阶段**只记录** `PlayerStatus.selectedRoleId`，角色在战斗阶段才经 `RoleBridge` 应用。
- WAITING 阶段退出即删 PlayerStatus；ROLE_SELECTING / PLAYING 走**断线保护**（保留一段时间，
  超时未重连才清除），由 `infrastructure/DisconnectProtection` 统一管理。
