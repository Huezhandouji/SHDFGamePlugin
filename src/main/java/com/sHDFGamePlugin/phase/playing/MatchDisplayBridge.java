package com.sHDFGamePlugin.phase.playing;

import com.sHDFGamePlugin.domain.sector.ActiveBomb;
import com.sHDFGamePlugin.domain.sector.Sector;
import com.sHDFGamePlugin.domain.sector.SectorManager;
import com.sHDFGamePlugin.domain.team.PlayerState;
import com.sHDFGamePlugin.domain.team.PlayerStatus;
import com.sHDFGamePlugin.domain.team.ShdfTeam;
import com.sHDFGamePlugin.domain.team.TeamManager;
import com.sHDFGamePlugin.domain.ticket.TicketManager;
import com.sHDFGamePlugin.infrastructure.GameEventBus;
import com.sHDFGamePlugin.infrastructure.RoleBridge;
import com.sHDFGamePlugin.infrastructure.config.ConfigManager;
import com.sHDFGamePlugin.infrastructure.config.MapConfig;
import com.sHDFGamePlugin.infrastructure.display.BattleBombInfo;
import com.sHDFGamePlugin.infrastructure.display.BattleDisplayService;
import com.sHDFGamePlugin.infrastructure.display.BattleDisplayState;
import com.sHDFGamePlugin.infrastructure.display.BattleSectorBarRenderer;
import com.sHDFGamePlugin.infrastructure.display.BattleSectorInfo;
import com.sHDFGamePlugin.infrastructure.display.BattleSidebarRenderer;
import com.sHDFGamePlugin.infrastructure.display.CompassItemFactory;
import com.sHDFGamePlugin.infrastructure.event.RightClickGameItemEvent;
import com.sHDFGamePlugin.util.MessageUtil;
import com.sHDFGamePlugin.util.SoundUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 表现层桥接：把 {@code infrastructure/display} 的可复用渲染组件接入战斗阶段。
 * <p>
 * 本类是表现层与 domain 之间的<b>唯一数据适配点</b>（即 {@code fromManagers} 语义的落点）：
 * <ul>
 *     <li>只读地收集 {@link SectorManager} / {@link TicketManager} / {@link IntermissionController} /
 *     {@link TeamManager} / {@link RoleBridge} 的数据，组装成 {@link BattleDisplayState}；</li>
 *     <li>每帧做两件事：① 收敛显示集合（{@link BattleDisplayService#show}/{@link BattleDisplayService#hide}，
 *     只负责"挂载/卸载显示"，会把玩家记分板设成侧边栏用的那一份）；
 *     ② 刷新帧数据（{@link BattleDisplayService#update}，真正写入 BossBar 名称/进度与侧边栏各行）；</li>
 *     <li>持有 slot 8 指南针物品的构建与注册，以及左键切换指向的玩家可见反馈；</li>
 *     <li>所有文本格式化仍在 display 包内（本类只做数据收集与阵营/角色名解析）。</li>
 * </ul>
 *
 * <h2>刷新节流</h2>
 * 刷新周期由 {@link BattleDisplayService} 的 <b>20 tick</b> 全局区域调度任务驱动（初始 delay 1L；
 * 见 {@link BattleDisplayService#REFRESH_PERIOD_TICKS}）；本类通过
 * {@link BattleDisplayService#setRefreshAction(Runnable)} 注入"收集 + 投影"动作，
 * <b>不</b>每 tick 重建任何 BossBar/objective。侧边栏"剩余时间/下一据点倒计时"按秒变化，
 * 炸弹明细的引信剩余由 {@code BattleSectorBarRenderer.renderBombDetailName} 向上取整到秒，
 * 因此 20 tick 与显示精度一致。
 *
 * <h2>显示生命周期（覆盖部署 / 死亡等待 / 观战 / 断线重连）</h2>
 * 每帧按"在线玩家 ∩ 参战阵营"投影：{@link PlayerState#IN_BATTLE}（战斗中）与
 * {@link PlayerState#DEPLOYING}（开局等待部署 / 死亡等待重生 / 断线重连等待）都显示；
 * 观战者、状态过期的玩家一律 {@link BattleDisplayService#hide}。
 * 因此 {@code DeploymentController.restoreCombatant/restoreAwaitingRespawn/makeSpectator} 等路径
 * <b>无需显式调用显示/隐藏</b>——玩家显示最迟 1 秒内自动归位，不会出现"某状态看不到"或"某状态残留"。
 *
 * <h2>据点链口径（队长拍板；t21 之后语义已确定）</h2>
 * 前段 = 已被进攻方攻占的据点；中段 = <b>当前据点</b>；后段 = 尚未开启的据点。
 * <p>关键事实（读 t21 落地代码 {@link SectorManager#advanceToNextSector()} 得到）：据点攻占时
 * <b>立即</b> {@code currentIndex += 1} 并激活新据点的炸弹（只建炸弹、<b>不起表</b>）；
 * 若地图配置 {@code sector_advance_interval > 0}，新据点保持"已激活未开启"，此时它<b>就是</b>
 * {@code currentIndex}；时限由 {@code IntermissionController} 倒计时结束时调用
 * {@link SectorManager#openCurrentSector()} 才启动。
 * 因此：
 * <ul>
 *     <li>间歇期内：{@code index < currentIndex} → 已攻占（前段，含刚被清空的那一个）；
 *     {@code index == currentIndex} → <b>中段</b>，渲染
 *     {@link BattleSectorInfo#opening(String, Component, int)}（{@code 名称(Ns)}，倒计时 = 间歇期剩余，
 *     且<b>不</b>显示炸弹三态——间歇期内炸弹不可交互，显示"绿=可安放"会误导玩家）；
 *     {@code index > currentIndex} → 尚未开启；</li>
 *     <li>非间歇期（含 interval=0 的立即开启）：{@code index == currentIndex} → 中段 active，
 *     显示名称 + 各炸弹三态；开启瞬间倒计时消失、三态出现，中段标签<b>始终落在 currentIndex 上、不跳动</b>。</li>
 * </ul>
 */
public final class MatchDisplayBridge {

    private static final MatchDisplayBridge INSTANCE = new MatchDisplayBridge();

    private MatchDisplayBridge() {}

    public static MatchDisplayBridge getInstance() {
        return INSTANCE;
    }

    //表现层门面（display 包）：BossBar + 侧边栏 + 指南针（注入左键反馈回调）
    private final BattleDisplayService displayService = new BattleDisplayService(
            new BattleSectorBarRenderer(),
            new BattleSidebarRenderer(),
            CompassItemFactory.builder()
                    .rightClickHandler(event -> GameEventBus.publish(
                            new RightClickGameItemEvent(event.getPlayer(), CompassItemFactory.DEFAULT_ITEM_ID)))
                    .leftClickListener(this::announceCompassTarget)
                    .build());

    /** 指南针 GameItem id（供门面右键路由判断） */
    public String compassItemId() {
        return displayService.compassFactory().itemId();
    }

    // ==================== 生命周期（门面 onEnter/onExit 调用） ====================

    /**
     * 启动表现层：注册指南针 GameItem（左键切换指向 / 右键发布 {@code RightClickGameItemEvent}）
     * 并注册 20 tick 刷新任务；随后立即渲染一帧，避免开局等 1 秒。幂等。
     */
    public void start() {
        displayService.setRefreshAction(this::refreshDisplay);
        displayService.start();
        displayService.refreshNow();
    }

    /**
     * 一次性清理：取消 20 tick 刷新任务、摘除全部玩家 BossBar（含炸弹明细）、
     * 注销 {@code playing_phase_sidebar} objective 并置 null、清空指南针指向缓存。
     * {@link BattleDisplayService#stop()} 自身幂等。
     * <p>指南针 GameItem 的注销由门面既有的 {@code PlayingItemFactory.unregisterPlayingItems()}
     * 统一负责（保持注册/注销集中在同一处，便于对照）。</p>
     */
    public void stop() {
        displayService.stop();
    }

    /** 指南针物品（slot 8）：材质 COMPASS、不可丢弃/不可移动、左键切换指向、右键打开战斗菜单 */
    public ItemStack createCompassItem() {
        return displayService.compassFactory().createItem();
    }

    /**
     * 为玩家立即挂载显示（BossBar + 侧边栏；门面部署/重连时调用可避免等 1 秒）。
     * <p><b>语义</b>：只负责"显示"本身——挂载 BossBar、建立/挂上该玩家的侧边栏 objective 并写入当前一帧；
     * <b>不</b>承担"帧数据刷新"（总览条名称/进度与明细条由 {@link #refreshNow()}/{@code refreshDisplay()} 的
     * {@code displayService.update(...)} 负责）。幂等。</p>
     */
    public void showPlayer(Player player) {
        displayService.show(player, collectState(), resolveView(player));
    }

    /**
     * 立即卸载某玩家的显示（门面可选调用；每帧的显示集合收敛也会自动 hide）。
     * <p><b>语义</b>：摘除 BossBar（含明细条）、注销该玩家侧边栏 objective 并清其记分板显示槽、
     * 清其指南针指向；仅影响该玩家。</p>
     */
    public void hidePlayer(Player player) {
        displayService.hide(player);
    }

    /**
     * 立即刷新一帧（进入阶段时先渲染，避免等 1 秒）。
     * <p><b>语义</b>：完整执行一帧 = 收敛显示集合（show/hide）+ 刷新帧数据
     * （{@code displayService.update}：BossBar 名称/进度/明细 + 侧边栏各行）。</p>
     */
    public void refreshNow() {
        refreshDisplay();
    }

    // ==================== 自检 / 观测（供验收与排查） ====================

    public BattleDisplayService displayService() {
        return displayService;
    }

    /** 当前显示集合大小 */
    public int displayedPlayerCount() {
        return displayService.displayedPlayerCount();
    }

    /** 20 tick 刷新任务是否在跑 */
    public boolean isRefreshTaskRunning() {
        return displayService.isRefreshTaskRunning();
    }

    // ==================== 每帧：收集 + 投影 ====================

    /**
     * 一帧刷新：
     * <ol>
     *   <li>对局已结束（{@code matchEnded}）→ 隐藏全部并停手，保证结束瞬间不残留；</li>
     *   <li>否则先按"在线 ∩ 参战阵营"收敛显示集合（观战者/状态过期者 hide），</li>
     *   <li>再把本帧数据投影给显示中的玩家（{@link BattleDisplayService#update}）。</li>
     * </ol>
     */
    private void refreshDisplay() {
        if(MatchSessionState.getInstance().isMatchEnded()){
            //对局已结束：摘掉表现层（FinishedPhase 会清理踢人，这里保证结束瞬间不残留）
            for(Player player : Bukkit.getOnlinePlayers()){
                displayService.hide(player);
            }
            return;
        }

        BattleDisplayState state = collectState();
        //先收敛显示集合：参战者 show（挂载 BossBar/侧边栏并设好玩家记分板），观战者/状态过期者 hide
        for(Player player : Bukkit.getOnlinePlayers()){
            if(isVisible(player)){
                displayService.show(player, state, resolveView(player));
            }
            else{
                //观战者 / 状态过期者：确保不残留上一状态的显示
                displayService.hide(player);
            }
        }
        //再刷新本帧帧数据（每帧恰好一次）
        projectFrame(state);
    }

    /**
     * 把本帧数据投影到显示层：{@link BattleDisplayService#update} 内部执行
     * {@code barRenderer.update(state)}（BossBar 名称/进度/条色，含每颗炸弹明细）与对每个显示中玩家的
     * {@code sidebarRenderer.update(...)}。
     * <p>缺了这一步，总览条会停在 show() 时的占位文案（"[--:--] 进攻方票数: 0"）。
     * 包级可见：便于用探针以真实的 {@code BattleDisplayService.update} 计数验证"每帧只调一次"。</p>
     */
    void projectFrame(BattleDisplayState state) {
        displayService.update(state, this::resolveView);
    }

    /** 是否应显示表现层：仍是参战阵营（含开局等待部署 / 死亡等待重生 / 断线重连等待） */
    private boolean isVisible(Player player) {
        PlayerStatus status = TeamManager.getInstance().getPlayerStatus(player.getUniqueId());
        return status != null && status.getTeam() != null && status.getTeam().isCombatant();
    }

    // ==================== 数据适配（只读 domain） ====================

    /** 收集本帧全局数据：据点链 / 票数 / 时限剩余与总长 / 间歇期剩余 */
    private BattleDisplayState collectState() {
        SectorManager sectorManager = SectorManager.getInstance();
        TicketManager ticketManager = TicketManager.getInstance();
        Sector current = sectorManager.getCurrentSector();

        int remainingTicks = sectorManager.getCurrentTimeLimitRemaining();
        int totalTicks = current == null ? 0 : current.getTimeLimit();

        return BattleDisplayState.builder()
                .remainingSectorTicks(remainingTicks)
                //据点未正式开启（间歇期）时时限未起表，总长置 0 → 总览条退化为票数比例
                .sectorTimeLimitTotalTicks(sectorManager.isCurrentSectorOpen() ? totalTicks : 0)
                .attackerTickets(ticketManager.getCurrentTickets())
                .attackerMaxTickets(ticketManager.getMaxTickets())
                .intermissionRemainingTicks(intermissionRemainingTicks())
                .sectors(buildSectorChain(sectorManager, current))
                .build();
    }

    private int intermissionRemainingTicks() {
        IntermissionController intermission = IntermissionController.getInstance();
        if(!intermission.isIntermissionActive()){
            return 0;
        }
        return intermission.getRemainingTicks();
    }

    /**
     * 组装三段据点链（口径见类注释）：
     * <pre>
     * index &lt;  currentIndex              → captured（前段）
     * index == currentIndex   间歇期      → opening 名称(Ns)（已激活未开启者就是 currentIndex）
     * index == currentIndex   非间歇期    → active（含各炸弹三态）
     * index &gt;  currentIndex              → pending（后段）
     * </pre>
     * 中段标签始终落在 {@code currentIndex} 上：间歇期结束调 {@link SectorManager#openCurrentSector()}
     * 时 {@code currentIndex} 不变，因此开启瞬间只会从"名称(Ns)"切换为"名称 + 三态"，不会跳动到别的据点。
     */
    private List<BattleSectorInfo> buildSectorChain(SectorManager sectorManager, Sector current) {
        MapConfig mapConfig = ConfigManager.getInstance().getSelectedMapConfig();
        List<Sector> sectors = mapConfig == null ? null : mapConfig.getSectors();
        if(sectors == null || sectors.isEmpty()){
            if(current == null){
                return List.of();
            }
            //地图配置缺失时退化为"只有当前据点"的单段链
            return List.of(currentSectorNode(current, sectorManager));
        }

        int currentIndex = Math.max(sectors.indexOf(current), 0);
        int openingTicks = intermissionRemainingTicks();

        List<BattleSectorInfo> chain = new ArrayList<>();
        for(int index = 0; index < sectors.size(); index++){
            Sector sector = sectors.get(index);
            switch (segmentOf(index, currentIndex, openingTicks > 0)){
                //前段：已被进攻方攻占的据点
                case CAPTURED -> chain.add(BattleSectorInfo.captured(sector.getId(), sector.getName()));
                //中段·正常：当前据点，显示名称 + 各炸弹三态
                case ACTIVE -> chain.add(currentSectorNode(sector, sectorManager));
                //中段·待开启：间歇期的当前据点（已激活未开启），显示 名称(Ns) 且不显示炸弹三态
                case OPENING -> chain.add(BattleSectorInfo.opening(sector.getId(), sector.getName(), openingTicks));
                //后段：尚未开启
                case PENDING -> chain.add(BattleSectorInfo.pending(sector.getId(), sector.getName()));
            }
        }
        return chain;
    }

    // ==================== 结点细分（纯函数，供探针断言"中段标签落在 currentIndex 且开启瞬间不跳动"） ====================

    /** 单个据点在地图列表中的段位 */
    public enum Segment {
        /** 前段：已被进攻方攻占 */
        CAPTURED,
        /** 中段·正常：显示名称 + 各炸弹三态 */
        ACTIVE,
        /** 中段·待开启（间歇期）：显示 名称(Ns)，不显示炸弹三态 */
        OPENING,
        /** 后段：尚未开启 */
        PENDING
    }

    /**
     * 判定某个据点索引落在哪一段（口径见类注释；纯函数，便于断言）。
     * <p>注意：中段始终是 {@code index == currentIndex}——间歇期内它是"已激活未开启"的当前据点，
     * 间歇期结束调 {@code openCurrentSector()} 时 {@code currentIndex} <b>不变</b>，
     * 因此开启瞬间只发生 {@link Segment#OPENING} → {@link Segment#ACTIVE}，标签不会跳到别的据点。</p>
     *
     * @param index               据点在地图列表中的索引
     * @param currentIndex        {@code SectorManager} 当前索引
     * @param intermissionActive  是否处于间歇期
     */
    static Segment segmentOf(int index, int currentIndex, boolean intermissionActive) {
        if(index < currentIndex){
            return Segment.CAPTURED;
        }
        if(index == currentIndex){
            return intermissionActive ? Segment.OPENING : Segment.ACTIVE;
        }
        return Segment.PENDING;
    }

    /** 中段节点：携带当前据点各炸弹的三态快照（含已爆炸的——明细条要保留） */
    private BattleSectorInfo currentSectorNode(Sector sector, SectorManager sectorManager) {        List<ActiveBomb> bombs = sectorManager.getActiveBombs();
        World world = resolveWorld();
        List<BattleBombInfo> bombInfos = new ArrayList<>(bombs.size());
        for(ActiveBomb bomb : bombs){
            bombInfos.add(BattleBombInfo.of(bomb, world));
        }
        return BattleSectorInfo.active(sector.getId(), sector.getName(), bombInfos);
    }

    private World resolveWorld() {
        MapConfig mapConfig = ConfigManager.getInstance().getSelectedMapConfig();
        if(mapConfig == null){
            return null;
        }
        return Bukkit.getWorld(mapConfig.getWorld());
    }

    /** 玩家维度数据：我方阵营名 + 所持角色名（缺失时交渲染层显示中性占位） */
    private BattleDisplayState.PlayerView resolveView(Player player) {
        PlayerStatus status = TeamManager.getInstance().getPlayerStatus(player.getUniqueId());
        ShdfTeam team = status == null ? null : status.getTeam();
        String roleId = status == null ? null : status.getSelectedRoleId();
        return new BattleDisplayState.PlayerView(teamName(team), roleName(roleId));
    }

    /** 阵营名：与选角/准备阶段的表述一致（#进攻方SHADOW# / #防守方HUNTER# / #旁观者#） */
    private static Component teamName(ShdfTeam team) {
        if(team == null){
            return Component.text("未分配", NamedTextColor.DARK_GRAY);
        }
        return switch (team){
            case ATTACKER -> Component.text("#进攻方SHADOW#", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD);
            case DEFENDER -> Component.text("#防守方HUNTER#", NamedTextColor.YELLOW, TextDecoration.BOLD);
            case SPECTATOR -> Component.text("#旁观者#", NamedTextColor.GRAY, TextDecoration.BOLD);
            case UNKNOWN -> Component.text("#随机分配#", NamedTextColor.BLUE, TextDecoration.BOLD);
        };
    }

    /**
     * 角色显示名：经 {@link RoleBridge#getRoleDisplayName}（外部角色插件）；
     * 无效/缺失时回退到 roleId 文本或"未选择角色"，绝不抛异常打断对局。
     */
    private static Component roleName(String roleId) {
        if(roleId == null || roleId.isEmpty()){
            return Component.text("未选择角色", NamedTextColor.DARK_GRAY);
        }
        try{
            RoleBridge roleBridge = RoleBridge.getInstance();
            if(roleBridge.isValidRoleId(roleId)){
                Component displayName = roleBridge.getRoleDisplayName(roleId);
                if(displayName != null && !Component.empty().equals(displayName)){
                    return displayName;
                }
            }
        }
        catch(RuntimeException ignored){
            //角色插件尚未就绪等异常：回退到 roleId 文本，表现层不得打断对局
        }
        return Component.text(roleId, NamedTextColor.WHITE);
    }

    // ==================== 指南针左键反馈 ====================

    /** 左键切换指向后的玩家可见反馈：ActionBar 显示当前跟踪的炸弹名（无可用炸弹时给提示） */
    private void announceCompassTarget(Player player, BattleBombInfo bomb) {
        if(player == null || !player.isOnline()){
            return;
        }
        if(bomb == null){
            player.sendActionBar(Component.text("当前据点没有可跟踪的炸弹", NamedTextColor.RED));
            SoundUtil.playNoticeFailCombinedSound(player);
            return;
        }
        player.sendActionBar(Component.text("跟踪炸弹: ", NamedTextColor.GRAY)
                .append(bomb.displayName().colorIfAbsent(NamedTextColor.YELLOW)));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.2f);
    }

    /** 立即把"当前跟踪炸弹"以聊天栏消息告知玩家（排查/调试用） */
    public void describeTrackedBomb(Player player) {
        String bombId = displayService.compassFactory().bombTargetId(player);
        if(bombId == null){
            MessageUtil.sendMessageWithPrefix(player, Component.text("当前未跟踪任何炸弹", NamedTextColor.GRAY));
            return;
        }
        MessageUtil.sendMessageWithPrefix(player, Component.text("当前跟踪: " + bombId, NamedTextColor.GRAY));
    }
}
