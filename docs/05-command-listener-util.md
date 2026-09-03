# 05 · command / listener / util

## command（指令系统，本会话新增）

采用"主指令 + 子指令接口"模式，便于扩展：

- `SubCommand` 接口：`getName()/getUsage()/execute(sender, args)` + 可选 `onTabComplete`。
- `ShdfGameCommand`：主指令 `shdfgame`（别名 `sg`，plugin.yml 注册），
  按第一参数路由到子指令，实现 `CommandExecutor` + `TabCompleter`。
- `ConfigCommand`：子指令 `config`，下属 `reload` → `ConfigManager.reload()`（成功/失败有反馈）。

用法：
```
/sg                查看可用子指令
/sg config reload  重载 config.yml 与 maps.yml
```

注意：整条命令权限为 `shadowhunter.game.player`（config reload 属敏感操作，后续可加权限细分）。
`RoleRegistry` import（com.shadowHunterRolesPlugin.registry）在 RoleSelectingPhase 中存在但当前未使用。

## listener（原代码）

- `PlayerJoinListener` / `PlayerQuitListener`：把 Bukkit 加入/退出事件转成内部事件发到 `GameEventBus`。
- 薄适配层，逻辑都在各阶段订阅者里。

## util

| 类 | 说明 |
|---|---|
| `MessageUtil` | 统一前缀消息：`sendMessageWithPrefix(player, comp)`；`sendPrefixedMessageToAllPlayers(comp)` 广播全体 |
| `SoundUtil` | 组合音效：`playNoticeSuccessCombinedSound`（上行）、`playNoticeFailCombinedSound`（下行），用 GlobalRegionScheduler 定时播放 |
| `ParticleUtil` | 用粒子描 `CubeRegion` 边框（调试/表现用），入参为 CubeRegion |
| `GameCountdown` | **共享倒计时**（tick 制）：`setOnTick(Consumer<Integer>)/setOnCancel(Consumer<String>)/setOnFinish(Runnable)`、`start/cancel/isRunning/getRemainingTicks/...`。由 WaitingPhase 与 RoleSelectingPhase 共用；**onTick 可选**（未设置不触发） |

注意：
- 倒计时每 tick 先减 1 再回调，所以阶段在使用配置 tick 值时会 `+1` 补偿（约定见 06）。
- 所有调度走 `getGlobalRegionScheduler()`（Folia 风格调度，兼容 Paper）。
