# t28 核心玩法验证 · 执行清单（verifier 准备稿）

> 状态：**暂缓开工**，等队长冻结信号（t23 / t40 收口）。冻结前不 claim、不跑构建、不出结论。
> 本文件只做准备，不是验证结论。冻结后按本清单执行，并把真实结果写进 task output。

## 0. 冻结协议（收到"已冻结"后按序执行）

1. **确认无在途写入**：对 `src/`、`build.gradle.kts`、`docs/` 连续两次采样（间隔 ≥10s）比对 `LastWriteTime` 最大值，两次一致才视为落定。
2. **三件套指纹**（行数口径统一 `[System.IO.File]::ReadAllLines()` 或数 `0x0A` 字节）：
   ```powershell
   $p='<file>'
   "sha256=" + (Get-FileHash $p -Algorithm SHA256).Hash
   "lines="  + [System.IO.File]::ReadAllLines($p).Length   # string[] 元素数 = 行数；字节数需 ReadAllBytes().Length
   "mtime="  + (Get-Item $p).LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')
   ```
   📌 **`ReadAllLines` 返回 `string[]`（数组），`.Length` 就是元素个数＝行数**；**字节数**要用 `[System.IO.File]::ReadAllBytes($p).Length`（例：`SectorProgressController` 244 行 / 15125 字节）。`.Length` 与 `.Count` 在数组上恒等，两种写法都对（本清单统一用 `.Length`）。
   ⚠ **纪律：比行数/gap 之前先钉 mtime+sha256**——gap 会随修订版变化（`SectorProgressController` 227 版 gap=39、244 版 gap=46），不同时刻的两个数不构成矛盾。
   ⚠ **口径纪律（team 实测 21:14 当场重测）**：**本工程多数文件存在折行，`(Get-Content).Count` 会少算，故一律以 `ReadAllLines` / 数 `0x0A` 为准、禁用 `(Get-Content).Count`**。实测（ReadAllLines / `0x0A` / `Get-Content`）：`SectorProgressController` 244/244/**198**（gap **46**，`190D6469…`@20:48:46）、`PlayingPhase` 335/335/**307**（gap **28**，`551E98F2…`@20:44:13）、`IntermissionController` 458/458/**433**（gap **25**，`0B2947F6…`@20:35:31）、`SpawnManager` 191/191/191（gap **0**，无折行例外，`F4818A6E…`@20:27:21）；`-Raw` 后 `split('\n')` 恒 **+1**。所有行数引用必须附 sha256 锚点。
   已核对口径一致性示例（2026/9/11 21:0x 实测，ReadAllLines = LF 计数）：`PlayingPhase.java`=335（sha 前16 `551E98F21496BB1B`，20:44:13）、`SectorProgressController.java`=**244**（`190D6469903CDB10`，20:48:46）、`IntermissionController.java`=458（`0B2947F637B0A845`，20:35:31）、`MatchSessionState.java`=216（`3B1875610F3017D6`，20:43:12）、`FinishedPhase.java`=238（`9AAFCA7F2458E0CD`，20:43:43）。
   ⚠ **行号版本纪律**：`SectorProgressController` 早前测得 227/`F9474CE6F43A240E`（20:43:15）是 **t37/t41 之前**的版本；之后为 **244/`190D6469…`（20:48:46，与 t42 审查锚点一致）**。引用行号时必须同时标注 sha256，切勿沿用 227 版本旧行号（例：`endMatch` 在 244 版为 :192，幂等守卫 :194，`setMatchOutcome` :198）。
3. **跑构建**（绝对路径配方；`$PWD` 字面未展开会回退 `~/.gradle` 并 EXIT 1）：
   ```powershell
   $env:GRADLE_USER_HOME    = 'C:\Users\ROG\Desktop\插件\SHDFGamePlugin\.gradle-work'
   $env:GRADLE_RO_DEP_CACHE = "$env:USERPROFILE\.gradle\caches"
   $env:GRADLE_OPTS         = "-Dorg.gradle.vfs.watch=false"
   .\gradlew jar --console=plain          # 合同命令；记录 EXIT CODE + BUILD SUCCESSFUL
   .\gradlew jar --rerun-tasks --console=plain   # 强制实执行，避免全 up-to-date 的假绿
   ```
   ⚠ **仅拿到 `compileJava/jar UP-TO-DATE` 不能证明当前源码可编译**（jar 可能比源码旧）——必须 `--rerun-tasks`（或先 clean）并确认输出为 `3 actionable tasks: 3 executed`，否则属"假通过"。报告中须注明**是否强制重编**：t28 与 t29 均已附 `--rerun-tasks` 全量构建（皆 EXIT 0 / 3 executed）。
   ⚠ **attempt id 权威性（流程口径）**：平台在**重新派发/重试时会重铸 attempt id**（并非"必然过期"）；提交前一律以 `claim_task` 的返回值为唯一权威 id，收到 stale 拒绝即停止该任务的写入。
4. **产物身份**：`build\libs\SHDFGamePlugin-1.0-SNAPSHOT.jar` 的 size / mtime / sha256，与最后源文件 mtime 比较先后。
5. **任何构建/编译失败结论，必须在落定的树上复跑一次**再写进报告（并行写入会造成 javac 假失败 exit=1）。

## 1. 八条验收 → 可执行步骤

| # | 验收 | 执行步骤 | 判定依据 |
|---|---|---|---|
| 1 | 配方执行合同命令，记录真实退出码与 BUILD SUCCESSFUL；说明不关 VFS 会 EXIT 1 | 第 0 节步骤 3，记录两次运行的 EXIT CODE + 尾部输出 | EXIT 0 且含 `BUILD SUCCESSFUL`；附 VFS 反例说明 |
| 2 | 核对 `build/libs`：含 t19~t22 新类与三个资源文件；给出 mtime 先后关系证明非陈旧构建 | 解压 jar 列条目（ZipFile 只读）；比对 jar mtime 与源文件 mtime 最大值 | 条目含 `phase/playing/*`、`infrastructure/display/*`、`SectorProgressController`、`IntermissionController`、`DebugCommand`、`FinishedPhase`、`config.yml`/`maps.yml`/`plugin.yml`；jar 晚于源码 |
| 3 | t20 逐条 文件:行号 证据 | 按 §2 静态证据表 grep，逐条落 `文件:行号` | 见 §2 目标 |
| 4 | t21 逐条 文件:行号 证据 | 同上，另核对 `src/main/resources/maps.yml` 的**实际键值** | 缺省回退 0 + warning；实际值以文件为准（准备阶段读到 600） |
| 5 | t22 逐条 文件:行号 证据 | 同上，另核对 `finish_display_time` 缺省回退（准备阶段读到 200）与 `onExit` 取消任务 | 读取战绩先于 `TeamManager.reset()` 的**调用顺序**要有行号 |
| 6 | t11：注册的 /sg 子指令、权限校验点、未与 plugin.yml 重复声明、非 PLAYING 有提示不抛异常 | grep `DebugCommand.java` 的路由与权限；比对 `plugin.yml` commands 段；grep `requirePlaying` | 5 个 topic 覆盖；权限两档；`plugin.yml` 仅 `shdfgame`；非 PLAYING 回显 |
| 7 | 产出「用户人工实机验证清单」 | 用 §4 模板，**写在报告内不新建文件** | 含部署步骤/进场步骤/逐现象预期 |
| 8 | 逐条 passed/failed；不可验证项集中列「未验证（已移交用户人工验证）」 | 汇总 §1~§4 | 无无证据通过、无静态推断冒充实机 |

## 2. 静态证据检索表（冻结后按此 grep，替换为真实行号）

**t20**
- 三事件订阅与 onExit 退订配对：`SectorProgressController.java` grep `BombExplodedEvent|TicketDepletedEvent|SectorTimeLimitExpiredEvent|subscribe|unsubscribe`；配对点 `PlayingPhase.java` grep 订阅/退订调用
- 推进后不重部署：`SectorProgressController.java` 中 `advanceToNextSector|increaseTicket|getTicketReward`；确认无 `deployPlayer`/全员部署调用
- 取值先于推进：`getCurrentSector|getTicketReward` 与 `advanceToNextSector()` 的**行号先后**
- 三条件结局 + 幂等：`endMatch|matchEnded|FINISHED`（含 `matchEnded` 守卫的位置）
- 票数钳制未被重复实现：`TicketManager.java` grep `Math.min|maxTickets|Math.max`；确认 `SectorProgressController` 未自行钳制
- 空服判定：`PlayingPhase.java` grep `isServerEmptyExcept|getOnlinePlayers`（确认 **无** 旧 `isEmpty()` 口径残留）
- 部署失败可定位：`SpawnManager.java` `deployPlayer` 的每个 `return false` 前都有**不同原因**日志；`RoleBridge.setPlayerRole` 同；去重表 `deployFailureLogged` 使用点

**t21**
- 地图级键：`ConfigManager.java` 解析 + `MapConfig.java` 字段/getter + `src/main/resources/maps.yml` **实际值**；缺键/负值 → warning + 0（不抛异常）
- 两条门闩：`BombInteractionController.java` 开始路径门闩 + 进行中读条失效路径门闩（各自带玩家反馈）
- 时限起点：`SectorManager` 的「激活」与「开启（启表）」拆分；确认 `openCurrentSector` 才启动 `SectorTimeLimit`
- 切角色：写入 `PlayerStatus.selectedRoleId`；去重口径与选角阶段一致（对照 `RoleSelectingPhase` 的去重实现）；处理「RoleBridge 占用表只在部署时填充」
- 按钮状态：`PlayingItemFactory` 的 BARRIER 禁用态 + 间歇期结束刷新已打开菜单
- 广播节流：秒数变化才刷新，不每 tick 广播

**t22**
- 四字段与记录点：`PlayerStatus` kills/deaths/bombsPlanted/bombsDefused；`DeathHandler.recordKillAndDeath`（击杀者经 `RoleBridge.getLastDamagerUuid`、排除自杀/离线/非战斗/同队误伤）；`BombInteractionController` success 分支
- `finish_display_time`：`ConfigManager` 缺省回退 + `src/main/resources/config.yml` 实际值
- 顺序：`FinishedPhase` 广播 → 停留 → 清理 → 踢人 → IDLE；`onExit` 取消延时任务
- **读取战绩先于 `TeamManager.reset()`**：给出两行的行号顺序

**t11**
- 路由：`DebugCommand` 的 `sector info|next|capture`、`ticket info|set|add`、`bomb list|set`、`intermission info|skip`、`match end`
- 权限：查看/修改两档权限字符串与校验点
- 注册：`ShdfGameCommand` 的注册行；`plugin.yml` commands 段比对（应仅 `shdfgame`，无 Brigadier 第二套）
- 非 PLAYING：`requirePlaying` 提示文案；`match end` 的 matchEnded 预检

## 3. 判读注意（engineer 交付说明 + 队长裁定）

- `/sg` **两条前提必须写进用户清单**：① `sector next` 已代调 `openCurrentSector()`，**手工跳据点不会进入间歇期**（间歇期只由真实炸弹爆炸触发）；② `sector next` 在最后一个据点**直接拒绝**，不会误触发「全据点攻占=进攻方胜」——该路径用 `sector capture` 或 `match end attacker`。
- **胜方展示（t40 落地后已变更，2026-09-11 21:0x 复核）**：胜方/原因**已持久化**——`SectorProgressController.java:198`（244 版 `190D6469…`）在 `endMatch` 幂等守卫（:194）之后 `state.setMatchOutcome(winner, reason)`；`MatchSessionState.java:59-60` 字段、:150-162 读写、:139-140 在 `setMatchEnded(false)` 内跨局复位（由 `PlayingPhase.java:87` 每局开局调用触发）；`FinishedPhase.java:67-68` 在 `TeamManager.reset()`（:195）**之前**读取，:126-133 展示为「对局结束! &lt;胜方&gt; 获胜 (&lt;原因&gt;)」，仅在 `winner == null` 时才退化为 :136-137 的中性文案「胜方未记录(对局未经正常胜负判定结束)」。
  ⇒ 因此 **`/sg debug match end attacker` 正常结束对局时会真的显示「进攻方 获胜 (调试指令强制结束)」**；旧版（t22 交付态、胜方曾被回退）"只显示中性文案"的期望**已过期**。判读时不要把"显示胜方"当成缺陷，也不要把中性文案当作正常路径的预期。
- t20 设计：`endMatch` 在**下一 tick**才 `transitionTo(FINISHED)`（同步切状态会让死亡流程后续写入落在已结束对局上）。副作用该 tick 内死亡不被拦截，需在清单里提示观察是否有异常残留。

## 4. 用户人工实机验证清单（模板，冻结后按真实实现定稿）

**A. 部署（禁止整文件覆盖）**
1. 用新构建覆盖 jar：`build\libs\SHDFGamePlugin-1.0-SNAPSHOT.jar` → `paper1.21.11\plugins\`（覆盖旧 jar 后重启）。
2. **只在服务器原文件增量新增一行**：`plugins\SHDFGamePlugin\maps.yml` 的 `map_crossfire:` 下新增 `sector_advance_interval: <值>`（建议 200 = 10 秒，单位 tick，可按节奏调；实际值以 t21 写入 src 的值为准）。
3. **只在服务器原文件增量新增一行**：`plugins\SHDFGamePlugin\config.yml` 的 `playing:` 段内新增 `finish_display_time: <值>`（缺省回退 200）。
4. ⚠️ **禁止整文件覆盖** maps.yml / config.yml：服务器 maps.yml 是真实出生点坐标（lobby `{3,3,-7}` 等 1~12 范围），src 是占位坐标（±35/61..71/195..205）；覆盖会让部署失败每 tick 重试（历史症状见 9/6 latest.log:79），且既有现场值（countdown_time=200 等）会被改掉。

**B. 进场**：≥2 名玩家（`online-mode=false`，任意用户名）→ 走完 WAITING → ROLE_SELECTING → PLAYING。

**C. 逐现象观察（每条给预期）**
| 现象 | 预期 |
|---|---|
| 据点推进 | `sector capture`（或打满炸弹）→ 广播 + 进攻方加该据点 `ticket_reward` → 进入下一据点；**推进后不全员重部署**，仅之后新死亡者按新据点出生区部署 |
| 票尽 | `ticket set 0`（或打空）→ 防守方胜 → 进 FINISHED |
| 全据点攻占 | `sector capture` 逐段打完全部据点（勿用末段 `sector next`）→ 进攻方胜 |
| 据点超时 | 让据点 `time_limit` 走完（或改小）→ 防守方胜 |
| 间歇期 | 真实炸弹爆炸清空当前据点 → 间歇期内新据点炸弹不可交互（开始安放/读条两条路径都被挡并给反馈）；时限在开启后才走；间歇期可用"切换角色"，开启后变 BARRIER 且已开菜单刷新为禁用 |
| **间歇期倒计时标签落点（t27-F1 验收项，必看）** | **`(Ns)` 倒计时标签必须落在「正在开启的那个据点」上**（而不是它后面那个），且**开启瞬间标签不得跳动**。判读方法：盯住 BossBar 中段/侧边栏里带 `(Ns)` 的那一格——间歇期内它应是"已激活未开启"的当前据点；间歇期结束的那一瞬，只应看到 `名称(Ns)` → `名称 + 炸弹三态`，**不应左移/右移一格**。参考实现：`MatchDisplayBridge.java:328-336 segmentOf`（间歇期 `index == currentIndex → OPENING`），:284 `currentIndex = max(sectors.indexOf(current), 0)`，:270-271 注释说明 `openCurrentSector()` 不改 `currentIndex` |
| 结算 | FINISHED 内聊天栏先展示**胜方与原因**（如「对局结束! 进攻方 获胜 (全部据点已被攻占)」；`/sg debug match end attacker` 走同一条路，原因为「调试指令强制结束」）+ 逐人四维战绩 → 停留 `finish_display_time` 内不被踢 → 清理+踢人 → 回 IDLE；重开一局无残留任务。**注意**：只有胜方确实未被记录（`winner == null`）时才出现中性文案「胜方未记录」，正常结局不应出现 |
| 空服重开 | 最后一名玩家退出 → 回 IDLE；下一名玩家加入能重新开局（连续 2 轮） |
| 部署失败日志 | 人为制造失败（如站在非法出生区/未选角色）→ 日志有**可区分原因**+玩家名，且不刷屏（去重） |

**D. 需要记录回传的证据**：`logs/latest.log` 中状态流转行、四类结局的广播/日志行、部署失败日志行、`/sg debug intermission info` 输出、任何异常堆栈。

## 5. 版本钉定（verifier 实测，2026-09-11 21:0x；口径 = ReadAllLines / sha256）

> 用法：引用任何行号前，先在此表比对 sha256；不一致即为**不同修订版**，行号不可沿用。

| 文件 | 行数 | sha256（前 16） | mtime |
|---|---|---|---|
| `phase/playing/SectorProgressController.java` | 244 | `190D6469903CDB10` | 20:48:46 |
| `phase/FinishedPhase.java` | 238 | `9AAFCA7F2458E0CD` | 20:43:43 |
| `phase/playing/MatchSessionState.java` | 216 | `3B1875610F3017D6` | 20:43:12 |
| `phase/PlayingPhase.java` | 335 | `551E98F21496BB1B` | 20:44:13 |
| `phase/playing/IntermissionController.java` | 458 | `0B2947F637B0A845` | 20:35:31 |
| `phase/playing/PlayingItemFactory.java` | 181 | `73814EDCF6DE1244` | 20:43:49 |
| `command/DebugCommand.java` | 596 | `72649BA77925B158` | 20:43:05 |
| `domain/sector/SectorManager.java` | 291 | `F113385BC56D2969` | 20:34:11 |
| `domain/spawn/SpawnManager.java` | 191 | `F4818A6EB650EEB1` | 20:27:21 |
| `infrastructure/RoleBridge.java` | 242 | `3AC042AF94760622` | 20:27:40 |
| `infrastructure/config/ConfigManager.java` | 492 | `C446E65387CBB746` | 20:39:03 |
| `domain/team/PlayerStatus.java` | 97 | `861F60D088FBAA36` | — |
| `phase/playing/DeathHandler.java` | 159 | `059C9123F42BB6EC` | 20:39:26 |
| `phase/playing/BombInteractionController.java` | 340 | `83643218626BF760` | 20:39:31 |
| **表现层**（t43/t45/t47 落盘后） | | | |
| `phase/playing/MatchDisplayBridge.java` | 426 | `35419442827ADF5A` | 20:56:13 |
| `infrastructure/display/BattleSidebarRenderer.java` | 526 | `302584E94AB8D5BE` | 21:09:45 |
| `infrastructure/display/CompassItemFactory.java` | 286 | `32D3EA6FE40024EE` | 21:06:47 |
| `infrastructure/display/BattleSectorBarRenderer.java` | 396 | `1942CCA1E4F910C8` | 20:42:41 |
| `infrastructure/display/BattleDisplayService.java` | 224 | `49A577C3E8B5038D` | 20:25:35 |
| 资源 `maps.yml` / `config.yml` / `plugin.yml` | 125/49/18 | `B082D303B042B8B4` / `38B73D206221B276` / `23303AF77F304609` | — |

**冻结产物（`--rerun-tasks` 强制重编，两次独立构建同 sha ⇒ 可复现）**
- `build/libs/SHDFGamePlugin-1.0-SNAPSHOT.jar` = **239506 B / sha256 `A654B12D471F9F1D758FD1FC5BD21C164BE7CCCA029EBE8B779F9836CD6B789E`**
- 构建：合同命令原样 + `--rerun-tasks` → **EXIT 0**、`BUILD SUCCESSFUL`、`3 actionable tasks: 3 executed`（21:09:50 与 21:12:11 两次同 sha）
- 时序：jar mtime **晚于**最后源文件（`BattleSidebarRenderer.java` 21:09:45.511）✓
- ⚠ 旧值作废：`C5666A01…`(20:45) / `4F017DB9…`(20:49:06) / `584EE301…`(20:51:49，均早于 t45/t47 的侧边栏修复) / `0BFD2383…`(21:02:31) / `CB721132…`(21:07:40) / 以及**从未在工作区出现过的** `236910B` 与 `MatchDisplayBridge 397/772808AB…`
