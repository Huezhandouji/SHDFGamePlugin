# 04 · infrastructure 基础设施

## 事件与通信

- `GameEventBus`（原代码）：静态总线，`subscribe(Class, handler)` 返回可取消的 `Subscription`；
  `publish` 遍历**快照**，允许回调中订阅/退订。`unsubscribeAll()` 慎用。
- `SHDFGameEvent`：内部事件基类（标记）。
- `infrastructure/event` 事件清单：
  - 玩家：`ShdfPlayerJoinEvent` / `ShdfPlayerQuitEvent`
  - 队伍：`TeamChangedEvent`
  - 炸弹：`BombPlantedEvent` / `BombDefusedEvent` / `BombExplodedEvent`（均携带 Sector + BombConfig）
  - 据点：`SectorTimeLimitExpiredEvent`
  - 票数：`TicketDepletedEvent`
  - 物品交互（**不继承** SHDFGameEvent）：`RightClickGameItemEvent` / `InventoryClickGameItemEvent`

## config（配置）

- `ConfigManager`（单例）：加载 `config.yml`（全局）与 `maps.yml`（地图）。
  - 单张地图出错 → 跳过该图并 `warning`；必要全局字段缺失 → 抛异常（插件启动失败 / reload 报错）。
  - 提供各字段查询：等待/选角配置、出生点（`getLobbySpawnpoint`、`getRoleSelection*Spawnpoint`、
    地图级 `getSpectatorSpawnpoint`）、世界（`getWaitingWorld`/`getRoleSelectionWorld`）、
    倒计时/重连时限等。
  - **`getRoleSelectionReconnectTimeLimit()` 会把重连时限钳制到不超过本阶段 duration**。
- `MapConfig`：单图静态配置（票数/重生时间/世界/观战点/双方角色池/据点列表）。
- `BombConfig`：炸弹静态配置（id/name/region 角点/plant/fuse/defuse），含 Builder 校验。

## regionNotation（区域抽象）

- `Region`：接口（getCenter / contains / copy）。
- `CubeRegion`：立方体（origin+size 或角点 `createFromCorners`）；出生区/活动区/炸弹范围都用它。
- `SphereRegion`：球体（center+radius），当前**未使用**（备用）。
- 注意：包名是 `regionNotation`（此前从 regionExpression 迁移）；其他包引用此包。

## item（物品交互系统，原代码 + 优化）

- `GameItem`：纯逻辑对象（不持有 ItemStack）。组件按 **Class** 键控：
  `RightClickComponent` / `LeftClickComponent` / `InventoryClickComponent`（可区分左/右/Shift）。
  通过 PDC 键 `GAME_ITEM_KEY` 与实体物品关联（`applyIdOnItemMeta/applyIdOnItemStack`）。
- `GameItemRegistry`：注册/注销/`createAndRegister` 门面；`ItemId` 常量。
- `InteractionManager`（Bukkit Listener）：拦截丢弃/交互/物品栏点击；
  - 右键 100ms 去重、丢物品触发左键的屏蔽处理；
  - 同 id 重复注册会 warning 后覆盖（阶段重入正常）。
- 组件历史：左键组件此前 TYPE 误抄为 right_click，已随 Class 键控修复。
- 注意：`InteractionManager` 是单例 Listener，`onEnable` 注册一次。

## gui（ChestGui）

- Builder 模式构建箱子菜单；空槽自动填**占位玻璃**（不可移动，防误点）。
- 打开状态注册表：`getOpenGui(player)` / `getOpenGuis` / `closeAllGuis()`（阶段切换清理用）。
- 动态更新：`setSlot/clearSlot/refresh()`（直接操作同一 Inventory，对已打开玩家实时生效）。
- **行数上限 6**（MC 箱子菜单上限），`rows()` 校验 1..6。
- 注意：玩家自行关闭菜单不会自动从注册表移除（暂无 InventoryClose 监听），
  依赖阶段退出 `closeAllGuis` 兜底。

## 通用工具类（本会话新增）

- `HashBiMap<K,V>`：双向映射（键唯一、值唯一；`put` 键/值冲突返回 false 且不改动；
  `forcePut` 覆盖；`getByKey/getByValue` 查不到返回 null；`removeByKey/removeByValue`）。
  用途示例：RoleSelectingPhase 的角色名 ↔ 按钮 id 注册表。
- `DisconnectProtection`：断线保护管理器。`start(plugin, uuid, delayTicks, action)`
  挂可取消任务（触发时玩家仍离线才执行 action）；`cancel(uuid)` 重连时调用；
  `cancelAll()` 对局结束时调用。句柄集中管理，杜绝跨局残留回调。

## RoleBridge（角色桥接，原代码 + 扩展）

- 防腐层：把 `ShadowHunterRolesPlugin` 的 `RoleAPI` 封装给游戏用。
- `init()` 通过 `ServicesManager` 加载 RoleAPI（缺失抛异常）。
- **角色应用应只在战斗阶段**：`setPlayerRole(uuid, roleId)` 做阵营角色池校验、
  重复检查（`allowDuplicateRoles` + 占用表 `attackerPlayerRoleMap/defenderPlayerRoleMap`）后调用 RoleAPI。
- 选择阶段只用其**查询**：`isValidRoleId`、`isAllowDuplicateRoles`、`getOccupiedRoles`、
  `getRoleDisplayName/Description/Icon`（透传 RoleAPI）。
- 注意（重要，易踩坑）：
  - **RoleAPI 对不存在的角色返回"假空值"而非 null**：`getRoleIcon` 返回 `Material.AIR`、
    `getRoleDisplayName/Description` 返回空 Component——判断时要把 AIR / `Component.empty()` 也算缺失。
  - `setCurrentMapConfig(mapConfig)` 必须在应用角色前调用（角色池以它为准），选角阶段进入时同步。
