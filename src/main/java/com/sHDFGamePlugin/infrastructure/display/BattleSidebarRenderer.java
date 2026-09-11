package com.sHDFGamePlugin.infrastructure.display;

import com.sHDFGamePlugin.SHDFGamePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 战斗侧边栏渲染器。<b>每个玩家一份独立记分板</b>（objective 名相同，但实例按玩家隔离）。
 *
 * <h2>已拍板内容（自上而下）</h2>
 * <pre>
 * DECAYING FRONTLINE          ← 标题
 * 战斗进行中 / 区域推进间隔    ← 状态行
 * 剩余时间    03:12
 * 进攻方剩余票数  12
 * 据点 中心广场 进度    1/3    ← 待开启形态显示 "-"
 * 下一据点开启   00:30        ← 间歇期才有这一行
 * 我方阵营      #进攻方SHADOW# ← 按玩家不同
 * 所持角色      影武者         ← 按玩家不同
 * </pre>
 *
 * <h2>为什么按玩家建 objective（而不是共用 tempScoreboard 的一个 objective）</h2>
 * 侧边栏最后两行（我方阵营 / 所持角色）是<b>玩家维度</b>数据：进攻方与防守方玩家同帧看到的
 * 阵营行必须不同。若共用同一个 objective，所有玩家看到的是同一份条目集合，只能显示其中一名玩家的
 * 阵营/角色（先写入者胜），另一名玩家会看到错误内容。
 * <p>因此本渲染器为每个玩家准备一份<b>独立</b>记分板（{@link ScoreboardManager#getNewScoreboard()}）
 * 与其中同名的 {@code playing_phase_sidebar} objective，并把该记分板设给这名玩家。
 * <b>"各自一块"必须理解为"每人恒定一块"</b>：{@code show(...)} 由
 * {@code BattleDisplayService.update(...)} 以 20 tick 对每名显示中玩家调用，
 * 因此 {@code show} 通过 {@code playerBoards.computeIfAbsent} 复用该玩家那块（见 {@link #ownBoardOf}），
 * <b>只在没有/旧板失效时</b>才新建——绝不允许每次调用都新建（那会每秒泄漏一块记分板）。
 * 有界性：{@code playerBoards} 容量 ≤ 显示中玩家数；每名玩家一局内至多新增 1 块板，
 * 仅在其板被外部替换时才会替换一次（替换前释放旧板上的 objective）。
 * 与工程既有约定的协调：
 * <ul>
 *     <li>准备/选角阶段使用的仍是插件共用的 {@code tempScoreboard}（例如
 *     {@code WaitingPhase.handlePlayerJoin} 的 {@code setScoreboard(tempScoreboard)}）——本渲染器
 *     <b>不修改</b> {@code tempScoreboard} 上的任何东西，因此不影响那两个阶段；</li>
 *     <li>进入 PLAYING 后本渲染器把玩家切到自己的记分板；{@link #hide(Player)} / {@link #unregister()}
 *     会注销<b>本渲染器为该玩家创建过的所有板</b>上的 objective 并清空登记，
 *     再把记分板<b>复位回共享 {@code tempScoreboard}</b>，不跨局残留；</li>
 *     <li>代价：PLAYING 期间不再显示 {@code tempScoreboard} 上的队伍前后缀。当前 PLAYING 并不使用
 *     它们（WaitingPhase 退出时已 {@code clearScoreboardTeam()} 注销自己的队伍），因此无可见损失。</li>
 * </ul>
 *
 * <h2>行更新与清理</h2>
 * {@link #show(Player, BattleDisplayState, BattleDisplayState.PlayerView)} 负责"复用该玩家自己的那块记分板、
 * 建好/复用 objective、写入本帧行"，幂等。
 * 每次写入后会对<b>本渲染器写过的 token 集合</b>做差集 {@code resetScores}，
 * 保证首条目变化（如剩余时间从 05:00 → 04:59、倒计时行消失）时不会残留重复行。
 */
public final class BattleSidebarRenderer {

    /** 本渲染器独占的 objective 名（勿与其他阶段重名；每个玩家各自一份实例） */
    public static final String OBJECTIVE_NAME = "playing_phase_sidebar";

    /** 记分板判据（dummy 型，等价于 {@code Criteria.DUMMY}，但不触发 Criteria 类的服务端静态初始化） */
    private static final String CRITERIA_DUMMY = "dummy";

    /** 玩家 -> 已写入的记分板 token 集合（用于差集清理） */
    private final Map<UUID, Set<String>> appliedTokens = new LinkedHashMap<>();
    /**
     * 玩家 -> 该玩家在本渲染器名下持有的<b>唯一一块</b>记分板（最小修法；取代原先的 {@code takenBoards}）。
     * <p><b>有界性不变式</b>（t48 复审会核）：本表容量 ≤ 当前显示中的玩家数；
     * 每名玩家在一局内至多新增 1 块板（仅在该玩家从未有过板、或其板被外部替换时才会新建）。
     * 因为 {@code show()} 由 {@code BattleDisplayService.update()} 按 20 tick 逐玩家调用，
     * 若每帧新建会每秒泄漏一块板——所以这里必须 {@code computeIfAbsent} 复用，禁止无条件新建。</p>
     */
    private final Map<UUID, Scoreboard> playerBoards = new LinkedHashMap<>();
    /** 玩家 -> 该玩家板上的侧边栏 objective（板被换掉时据此释放旧板上的 objective） */
    private final Map<UUID, Objective> playerObjectives = new LinkedHashMap<>();

    /**
     * 记分板工厂（<b>仅测试内可写</b>）：默认取 {@link ScoreboardManager#getNewScoreboard()}；
     * 探针可替换为返回假记分板，以便在无服务端环境下把 show → applyRows → hide → 复位 的运行期路径打穿。
     * <p><b>生产代码不得写此字段</b>：它是 static 且同包可见，误设会全局改变记分板来源。</p>
     */
    static ScoreboardFactory scoreboardFactory = defaultScoreboardFactory();

    /**
     * 共享记分板覆盖（<b>仅测试内可写</b>）：非 null 时优先于插件的 {@code tempScoreboard}
     * （无服务端环境下探针无法取得插件单例，用它验证"复位回共享板"这条路径）。
     * <p><b>生产代码不得写此字段</b>；探针用后必须在 finally 中还原为 {@code null}。</p>
     */
    static Scoreboard sharedScoreboardOverride;

    /** 记分板工厂接口（见 {@link #scoreboardFactory}） */
    interface ScoreboardFactory {
        Scoreboard create();
    }

    // ==================== 纯渲染（可单测，无副作用） ====================

    /**
     * 生成侧边栏行（自上而下）。
     *
     * @param state 全局帧数据（票数 / 据点进度 / 倒计时）
     * @param view  玩家维度数据（阵营 / 角色），可为 null
     * @return 行列表；行序即显示顺序
     */
    public static List<SidebarLine> renderLines(BattleDisplayState state, BattleDisplayState.PlayerView view) {
        List<SidebarLine> lines = new ArrayList<>();
        boolean intermission = state != null && state.isIntermission();

        lines.add(new SidebarLine(Component.text("DECAYING FRONTLINE", NamedTextColor.GOLD, TextDecoration.BOLD)));
        lines.add(new SidebarLine(Component.text(intermission ? "区域推进间隔" : "战斗进行中",
                intermission ? NamedTextColor.YELLOW : NamedTextColor.GREEN)));
        lines.add(new SidebarLine(Component.text("剩余时间", NamedTextColor.GRAY).append(Component.text("    "))
                .append(Component.text(state == null ? "--:--" : state.remainingTimeText(), NamedTextColor.AQUA))));
        lines.add(new SidebarLine(Component.text("进攻方剩余票数", NamedTextColor.GRAY).append(Component.text("  "))
                .append(Component.text(String.valueOf(state == null ? 0 : state.attackerTickets()),
                        NamedTextColor.WHITE, TextDecoration.BOLD))));

        if(state != null && state.activeSector() != null){
            BattleSectorInfo sector = state.activeSector();
            //中段处于"待开启"形态时该据点尚无炸弹明细：进度显示 "-"，避免 0/0 让玩家误读
            String progress = sector.isOpening() ? "-" : sector.progressText();
            lines.add(new SidebarLine(Component.text("据点 ", NamedTextColor.GRAY)
                    .append(sector.displayName().colorIfAbsent(NamedTextColor.YELLOW))
                    .append(Component.text(" 进度", NamedTextColor.GRAY))
                    .append(Component.text("    "))
                    .append(Component.text(progress, NamedTextColor.WHITE))));
        }
        else{
            lines.add(new SidebarLine(Component.text("据点进度", NamedTextColor.GRAY).append(Component.text("    "))
                    .append(Component.text("--", NamedTextColor.DARK_GRAY))));
        }

        if(intermission){
            lines.add(new SidebarLine(Component.text("下一据点开启", NamedTextColor.GRAY).append(Component.text("  "))
                    .append(Component.text(BattleDisplayState.formatTicks(state.intermissionRemainingTicks()),
                            NamedTextColor.YELLOW, TextDecoration.BOLD))));
        }

        Component teamName = view == null || view.teamName() == null
                ? Component.text("未分配", NamedTextColor.DARK_GRAY)
                : view.teamName();
        Component roleName = view == null || view.roleName() == null
                ? Component.text("未选择角色", NamedTextColor.DARK_GRAY)
                : view.roleName();

        lines.add(new SidebarLine(Component.text("我方阵营", NamedTextColor.GRAY).append(Component.text("    "))
                .append(teamName)));
        lines.add(new SidebarLine(Component.text("所持角色", NamedTextColor.GRAY).append(Component.text("    "))
                .append(roleName)));
        return lines;
    }

    // ==================== 生命周期 ====================

    /**
     * 为玩家注册/刷新侧边栏并挂载到<b>该玩家自己那一块</b>记分板上；幂等。
     * <p><b>最小修法 / 有界性</b>：{@code playerBoards.computeIfAbsent(uuid, k -> newScoreboard())}——
     * 复用该玩家已持有的那块板，<b>绝不每帧新建</b>（{@code show()} 由 20 tick 逐玩家调用，
     * 无条件新建会每秒泄漏一块板）。仅在该玩家从未有过板、或其当前板被外部替换时才新建一块，
     * 且新建前会先释放旧板上的 objective。</p>
     * <p>每帧调用即为"{@code update}"语义：重新渲染本帧内容、差集清理旧 token。</p>
     */
    public void show(Player player, BattleDisplayState state, BattleDisplayState.PlayerView view) {
        if(player == null){
            return;
        }
        UUID uuid = player.getUniqueId();
        Scoreboard board = ownBoardOf(player, uuid);
        if(board == null){
            return;
        }
        registerObjective(board, uuid);
        applyRows(board, uuid, renderLines(state, view));
    }

    /**
     * 取该玩家应使用的记分板（本渲染器名下唯一一块）：
     * <ol>
     *     <li>表里已有该玩家的板，且它仍是该玩家当前绑定的板 → 直接复用（稳态路径，不新建）；</li>
     *     <li>表里已有但玩家当前板已被外部替换（重连/其他阶段接管）→ 先释放旧板上的 objective，再新建一块；</li>
     *     <li>表里没有 → 新建一块并登记（服务端首次 show 走这条）。</li>
     * </ol>
     * 三条路径最多只让"该玩家的板数量"增加 1，因此整局板数量有界（≤ 显示中玩家数 + 偶发替换次数）。
     */
    private Scoreboard ownBoardOf(Player player, UUID uuid) {
        Scoreboard current = player.getScoreboard();
        Scoreboard owned = playerBoards.get(uuid);
        if(owned != null && owned == current){
            return owned;
        }
        if(owned != null){
            //玩家的板被外部换走：先释放旧板上的 objective（否则旧板上会留下无人注销的 objective）
            releaseBoard(uuid, owned);
        }
        Scoreboard created = newScoreboard();
        if(created == null){
            return null;
        }
        playerBoards.put(uuid, created);
        player.setScoreboard(created);
        return created;
    }

    /** 仅刷新内容（已显示的玩家）；未显示时与 {@link #show} 等价 */
    public void update(Player player, BattleDisplayState state, BattleDisplayState.PlayerView view) {
        if(player == null){
            return;
        }
        show(player, state, view);
    }

    /**
     * 隐藏该玩家的侧边栏：注销该玩家这块板上的 objective、清空 token 记录，并把记分板<b>复位</b>回插件共用的
     * {@code tempScoreboard}（玩家随即不再显示旧板，因此无需扫其它板）。
     */
    public void hide(Player player) {
        if(player == null){
            return;
        }
        UUID uuid = player.getUniqueId();
        Scoreboard owned = playerBoards.remove(uuid);
        appliedTokens.remove(uuid);
        if(owned == null){
            return;
        }
        releaseObjective(uuid, owned);
        restoreSharedScoreboard(player);
    }

    /** 隐藏全部玩家的侧边栏（逐玩家注销其板上的 objective 并复位；同一局内可再次 show 重建） */
    public void hideAll() {
        for(UUID uuid : new ArrayList<>(playerBoards.keySet())){
            Player player = onlinePlayer(uuid);
            Scoreboard owned = playerBoards.remove(uuid);
            appliedTokens.remove(uuid);
            if(owned != null){
                releaseObjective(uuid, owned);
            }
            if(player != null){
                restoreSharedScoreboard(player);
            }
        }
        //防御性收尾：清空可能因离线玩家留下的残余映射
        playerBoards.clear();
        playerObjectives.clear();
        appliedTokens.clear();
    }

    /** 取在线玩家；无服务端/玩家离线时返回 null（清理路径不得因它抛异常） */
    private static Player onlinePlayer(UUID uuid) {
        try{
            return Bukkit.getPlayer(uuid);
        }
        catch(RuntimeException | LinkageError noServer){
            return null;
        }
    }

    /**
     * 一次性清理：隐藏全部玩家、逐个注销 objective 并解除映射。
     * <p>集成方在阶段 {@code onExit} 调用（对齐 {@code WaitingPhase.onExit} 的
     * {@code sidebarObjective.unregister()} 语义，但这里是"每个玩家一份"）。</p>
     */
    public void unregister() {
        hideAll();
    }

    /** 当前是否有任一玩家的侧边栏 objective 处于注册状态（自检用） */
    public boolean isRegistered() {
        return !playerObjectives.isEmpty();
    }

    /** 当前已显示侧边栏的玩家数（自检用） */
    public int shownPlayerCount() {
        return playerBoards.size();
    }

    /** 本渲染器当前为该玩家持有的记分板数量（自检/探针用；不变式：恒为 0 或 1） */
    int boardCount(UUID uuid) {
        return playerBoards.containsKey(uuid) ? 1 : 0;
    }

    /** 自检：本渲染器当前登记的板总数（不变式：≤ 显示中玩家数，不随帧数增长） */
    public int boardCount() {
        return playerBoards.size();
    }

    /** 自检：本渲染器当前登记的 objective 总数（应与 {@link #boardCount()} 一致或更少） */
    public int objectiveCount() {
        return playerObjectives.size();
    }

    /** 本渲染器是否正持有该玩家（即该玩家的板由本渲染器创建） */
    boolean ownsBoardOf(UUID uuid) {
        return playerBoards.containsKey(uuid);
    }

    /**
     * 读取某玩家侧边栏当前的"行文本 → 分值"快照（自检/探针用；未注册返回空 Map）。
     * <p>用于证明"每玩家一份 objective、各自看到自己的阵营/角色"与"分值按行序稳定"。</p>
     */
    public Map<String, Integer> scoreSnapshot(Player player) {
        Map<String, Integer> snapshot = new LinkedHashMap<>();
        if(player == null){
            return snapshot;
        }
        Objective objective = playerObjectives.get(player.getUniqueId());
        if(objective == null){
            return snapshot;
        }
        Scoreboard board = objective.getScoreboard();
        if(board == null){
            return snapshot;
        }
        for(String entry : board.getEntries()){
            org.bukkit.scoreboard.Score score = objective.getScore(entry);
            if(score.isScoreSet()){
                snapshot.put(entry, score.getScore());
            }
        }
        return snapshot;
    }

    // ==================== 内部 ====================

    /**
     * 计算"上一帧写过、本帧不再出现"的 token（需要 {@code resetScores} 的差集）。
     * <p>纯函数，便于探针断言"首条目变更后不残留重复行"。</p>
     */
    static List<String> staleTokens(Collection<String> previous, Collection<String> current) {
        List<String> stale = new ArrayList<>();
        if(previous == null){
            return stale;
        }
        for(String token : previous){
            if(current == null || !current.contains(token)){
                stale.add(token);
            }
        }
        return stale;
    }

    /**
     * 在该玩家自己的记分板上注册/复用侧边栏 objective（同名残留先注销，避免 IllegalArgumentException）。
     * <p>同一块板上已有本渲染器的 objective 时直接复用，不重复注册。</p>
     */
    private void registerObjective(Scoreboard board, UUID uuid) {
        Objective existing = playerObjectives.get(uuid);
        if(existing != null && existing.getScoreboard() == board){
            return;
        }
        Objective stale = board.getObjective(OBJECTIVE_NAME);
        if(stale != null){
            stale.unregister();
        }
        //用 Criteria 字符串重载（等价于 Criteria.DUMMY）：Criteria 类的静态初始化需要运行中的服务端，
        //而字符串重载不依赖它——既简化装配，也让本渲染器可在无服务端环境下被探针驱动。
        Objective objective = board.registerNewObjective(OBJECTIVE_NAME, CRITERIA_DUMMY,
                Component.text("DECAYING FRONTLINE", NamedTextColor.GOLD, TextDecoration.BOLD));
        playerObjectives.put(uuid, objective);
    }

    /** 写入本帧行：差集清理未出现的 token，设置显示槽与行分值 */
    private void applyRows(Scoreboard board, UUID uuid, List<SidebarLine> lines) {
        Objective objective = board.getObjective(OBJECTIVE_NAME);
        if(objective == null){
            return;
        }
        playerObjectives.put(uuid, objective);

        List<String> tokens = tokenList(lines);
        //差集：本帧不再出现的 token 必须 resetScores，否则旧行（含旧阵营/角色）会残留
        for(String stale : staleTokens(appliedTokens.get(uuid), tokens)){
            board.resetScores(stale);
        }

        int size = tokens.size();
        for(int index = 0; index < size; index++){
            objective.getScore(tokens.get(index)).setScore(scoreOf(index, size));
        }
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        appliedTokens.put(uuid, new LinkedHashSet<>(tokens));
    }

    /** 注销某块板上本渲染器的 objective 并清其侧边栏显示槽 */
    private void releaseObjective(UUID uuid, Scoreboard board) {
        Objective objective = playerObjectives.get(uuid);
        if(objective != null && objective.getScoreboard() == board){
            playerObjectives.remove(uuid);
            board.clearSlot(DisplaySlot.SIDEBAR);
            objective.unregister();
            return;
        }
        //该板上没有本渲染器的 objective：仅保险清一次显示槽
        board.clearSlot(DisplaySlot.SIDEBAR);
    }

    /** 释放该玩家名下这块板上的 objective（板被外部替换时调用），不动 token 记录 */
    private void releaseBoard(UUID uuid, Scoreboard board) {
        Objective objective = playerObjectives.get(uuid);
        if(objective != null && objective.getScoreboard() == board){
            playerObjectives.remove(uuid);
            board.clearSlot(DisplaySlot.SIDEBAR);
            objective.unregister();
        }
    }

    /**
     * 把玩家记分板复位回插件共用的 {@code tempScoreboard}（Waiting/RoleSelecting 阶段使用的那一份）。
     * <p>调用前应已注销该玩家的 objective 并从 {@code playerBoards} 摘除；玩家离线或插件未就绪时静默跳过——
     * 表现层清理不得因记分板操作抛异常而打断阶段退出。</p>
     */
    private void restoreSharedScoreboard(Player player) {
        Scoreboard shared = sharedScoreboard();
        if(shared == null){
            return;
        }
        try{
            if(player.getScoreboard() != shared){
                player.setScoreboard(shared);
            }
        }
        catch(RuntimeException ignored){
            //玩家离线/状态异常时跳过：表现层清理不得打断阶段退出
        }
    }

    /** 插件共用的记分板（Waiting/RoleSelecting 用的 tempScoreboard）；插件未就绪返回 null */
    private static Scoreboard sharedScoreboard() {
        if(sharedScoreboardOverride != null){
            return sharedScoreboardOverride;
        }
        SHDFGamePlugin plugin = SHDFGamePlugin.getInstance();
        if(plugin == null){
            return null;
        }
        return plugin.getTempScoreboard();
    }

    private static ScoreboardManager scoreboardManager() {
        return Bukkit.getScoreboardManager();
    }

    private static ScoreboardFactory defaultScoreboardFactory() {
        return () -> {
            ScoreboardManager manager;
            try{
                manager = scoreboardManager();
            }
            catch(RuntimeException | LinkageError noServer){
                return null;
            }
            if(manager == null){
                return null;
            }
            return manager.getNewScoreboard();
        };
    }

    /** 取一份新的侧边栏记分板（走测试接缝 {@link #scoreboardFactory}） */
    private static Scoreboard newScoreboard() {
        ScoreboardFactory factory = scoreboardFactory;
        if(factory == null){
            return null;
        }
        try{
            return factory.create();
        }
        catch(RuntimeException | LinkageError noServer){
            return null;
        }
    }

    private static int scoreOf(int index, int size) {
        return size - index;
    }

    /**
     * 渲染行 → 记分板 token。
     * <p>
     * 侧边栏每行都有唯一标签（剩余时间 / 票数 / 据点 / 阵营 / 角色 …），
     * 因此直接用 {@link LegacyComponentSerializer#legacySection()} 序列化即可保证唯一，
     * 无需追加隐藏后缀（避免用非记录格式码污染客户端渲染）。
     * </p>
     */
    static List<String> tokenList(List<SidebarLine> lines) {
        List<String> tokens = new ArrayList<>();
        for(SidebarLine line : lines){
            tokens.add(tokenize(line.text()));
        }
        return tokens;
    }

    private static String tokenize(Component text) {
        return LegacyComponentSerializer.legacySection().serialize(text);
    }

    /** 一行侧边栏内容 */
    public static final class SidebarLine {

        private final Component text;

        public SidebarLine(Component text) {
            this.text = text == null ? Component.empty() : text;
        }

        public Component text() {
            return text;
        }
    }
}
