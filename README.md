# SHDFGamePlugin

基于 **Paper 1.21.11** 的攻防对局小游戏插件（进攻方 SHADOW vs 防守方 HUNTER），
核心玩法为**多炸弹据点推进**：进攻方在炸弹点安放炸弹、引信引爆后推进据点；
依赖外部角色插件 **ShadowHunterRolesPlugin**（通过其 `RoleAPI` 提供服务）。

> 阶段流程：`IDLE → WAITING → ROLE_SELECTING → PLAYING → FINISHED`
> （对应状态机见 `core/GameStateMachine`）

## 快速开始

- 构建：`gradlew build`（构建后 `copyPluginJar` 任务会自动把 jar 复制到
  `C:/Users/ROG/Desktop/paper1.21.11/plugins`）
- 依赖：先安装 `ShadowHunterRolesPlugin`（`depend` 声明，缺失则插件拒绝启动）
- 主命令：`/shdfgame`（别名 `/sg`），子指令见 `command` 包

## 模块地图

```
src/main/java/com/sHDFGamePlugin/
├── SHDFGamePlugin.java          入口主类：依赖校验/初始化/注册事件与命令/启动状态机
├── SHDFGamePluginLoader.java    Paper 插件类加载器（预留动态库）
├── core/                        核心：GameContext(容器) / GameState / GameStateMachine
├── phase/                       阶段实现（对局流程的实际逻辑）
├── domain/                      领域层（纯游戏规则）
│   ├── team/                    阵营/玩家状态/队伍管理
│   ├── sector/                  据点/炸弹（配置→运行时的状态机）
│   ├── spawn/                   重生队列与部署
│   └── ticket/                  票数
├── infrastructure/              基础设施
│   ├── GameEventBus             内部事件总线
│   ├── event/                   领域事件（炸弹/玩家/队伍/票数…）
│   ├── config/                  配置（ConfigManager/MapConfig/BombConfig）
│   ├── regionNotation/          区域抽象（Region/CubeRegion/SphereRegion）
│   ├── item/                    物品交互系统（GameItem + InteractionManager）
│   ├── gui/                     箱子 GUI（ChestGui）
│   ├── HashBiMap                双向映射表
│   ├── DisconnectProtection     断线保护管理器
│   └── RoleBridge               角色桥接（防腐层，封装 RoleAPI）
├── command/                     指令系统（SubCommand 模式，/sg …）
├── listener/                    Bukkit 事件 → 游戏事件的薄适配层
└── util/                        工具（消息/音效/粒子/共享倒计时）
```

## 文档

| 文档 | 内容 |
|---|---|
| [docs/01-overview.md](docs/01-overview.md) | 总体架构、分层与数据流 |
| [docs/02-core-and-phase.md](docs/02-core-and-phase.md) | core 与各阶段详解与注意点 |
| [docs/03-domain.md](docs/03-domain.md) | 领域层：team / sector / spawn / ticket |
| [docs/04-infrastructure.md](docs/04-infrastructure.md) | 基础设施：事件/配置/区域/物品/GUI/通用工具 |
| [docs/05-command-listener-util.md](docs/05-command-listener-util.md) | 指令 / 监听器 / 工具类 |
| [docs/06-conventions-and-todo.md](docs/06-conventions-and-todo.md) | 开发约定与 TODO |

## 当前进度

- ✅ 状态机骨架、WAITING（大厅/准备/倒计时/侧边栏）
- ✅ ROLE_SELECTING（选角 GUI/去重/自动分配/清除/断线重连）
- ✅ 炸弹数据模型与 SectorManager 多炸弹状态机（领域层已就绪）
- ✅ 玩家加入/退出与断线保护（DisconnectProtection）
- ✅ FINISHED 对局清理（重置 + 踢人 + 回 IDLE）
- ⏳ PLAYING 战斗玩法（炸弹安放/拆弹进度、对局结束触发）尚未接线
