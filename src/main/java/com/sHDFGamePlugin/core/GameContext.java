package com.sHDFGamePlugin.core;

import com.sHDFGamePlugin.domain.sector.SectorManager;
import com.sHDFGamePlugin.domain.spawn.SpawnManager;
import com.sHDFGamePlugin.domain.team.TeamManager;
import com.sHDFGamePlugin.domain.ticket.TicketManager;
import com.sHDFGamePlugin.infrastructure.DisconnectProtection;
import com.sHDFGamePlugin.infrastructure.GameEventBus;
import com.sHDFGamePlugin.infrastructure.RoleBridge;
import com.sHDFGamePlugin.infrastructure.config.ConfigManager;
import com.sHDFGamePlugin.infrastructure.display.CompassItemFactory;
import com.sHDFGamePlugin.infrastructure.gui.ChestGui;
import com.sHDFGamePlugin.infrastructure.gui.RoleSelectionGui;
import com.sHDFGamePlugin.infrastructure.item.GameItemRegistry;
import com.sHDFGamePlugin.infrastructure.item.GuideBookFactory;
import com.sHDFGamePlugin.infrastructure.item.InteractionManager;
import com.sHDFGamePlugin.phase.FinishedPhase;
import com.sHDFGamePlugin.phase.GamePhase;
import com.sHDFGamePlugin.phase.IdlePhase;
import com.sHDFGamePlugin.phase.PlayingPhase;
import com.sHDFGamePlugin.phase.RoleSelectingPhase;
import com.sHDFGamePlugin.phase.WaitingPhase;
import com.sHDFGamePlugin.phase.playing.BombInteractionController;
import com.sHDFGamePlugin.phase.playing.DeathHandler;
import com.sHDFGamePlugin.phase.playing.DeploymentController;
import com.sHDFGamePlugin.phase.playing.IntermissionController;
import com.sHDFGamePlugin.phase.playing.MatchDisplayBridge;
import com.sHDFGamePlugin.phase.playing.MatchSessionState;
import com.sHDFGamePlugin.phase.playing.PlayingItemFactory;
import com.sHDFGamePlugin.phase.playing.SectorProgressController;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;

/**
 * 全局游戏上下文：<b>唯一</b>持有全部游戏组件的容器。
 * <p>
 * 本类取代了此前散落各处的 23 个静态单例（{@code XxxManager.getInstance()}）。
 * 全部组件都在这里由构造函数一次性创建并互相注入，因此：
 * <ul>
 *     <li>组件不再有任何静态实例字段，{@link #shutdown()} 后整个对象图（含队列表、重生队列、
 *     票数、任务句柄、事件订阅）随本实例一起失去引用——插件禁用后不会再有静态引用把世界、
 *     玩家或调度任务钉在堆里；</li>
 *     <li>依赖关系在构造顺序里显式可见（见构造函数的分段注释），不再有"谁先 init 谁后 init"
 *     的隐式全局状态；</li>
 *     <li>同一 JVM 内重新加载插件时会得到一份全新的上下文，不会复用上一轮的残留状态。</li>
 * </ul>
 * <p>
 * 生命周期：由 {@code SHDFGamePlugin#onEnable} 构造一次，并在 {@code onDisable} 调用
 * {@link #shutdown()} 清理（取消调度任务、注销监听器、退订事件总线）。
 * <p>
 * 本类是<b>实例</b>，不提供 {@code getInstance()}：需要它的组件在构造时接收它本身
 * （例如各阶段类与 PLAYING 控制器），其余组件只接收自己真正依赖的那几个管理器。
 * <p>
 * 例外说明（有意保留的静态设施，不属于本次移除的"单例"）：{@link GameEventBus}（纯静态发布/订阅
 * 总线，由各订阅者在 onExit 与 {@link #shutdown()} 中退订）、{@code MessageUtil} / {@code SoundUtil} /
 * {@code ParticleUtil}（无状态工具）、{@link ChestGui} 的"已打开 GUI"注册表。
 */
public class GameContext {

    private final JavaPlugin plugin;

    /** 准备/选角阶段共用的记分板；由插件主类创建后经 {@link #setTempScoreboard(Scoreboard)} 注入 */
    private Scoreboard tempScoreboard;

    //基础设施（无依赖，最先构造）
    private final ConfigManager configManager;
    private final InteractionManager interactionManager;
    private final GameItemRegistry gameItemRegistry;
    private final GuideBookFactory guideBookFactory;
    private final DisconnectProtection disconnectProtection;

    //领域层（持有本局运行数据）
    private final TeamManager teamManager;
    private final TicketManager ticketManager;
    private final SectorManager sectorManager;
    private final SpawnManager spawnManager;

    //桥接表现层
    private final RoleBridge roleBridge;

    //PLAYING 阶段的共享运行时状态与控制器（同包内互相配合）
    private final MatchSessionState matchSessionState;
    private final CompassItemFactory compassItemFactory;
    private final PlayingItemFactory playingItemFactory;
    private final MatchDisplayBridge matchDisplayBridge;
    private final IntermissionController intermissionController;
    private final DeathHandler deathHandler;
    private final DeploymentController deploymentController;
    private final BombInteractionController bombInteractionController;
    private final SectorProgressController sectorProgressController;

    //阶段与状态机
    private final IdlePhase idlePhase;
    private final WaitingPhase waitingPhase;
    private final RoleSelectingPhase roleSelectingPhase;
    private final PlayingPhase playingPhase;
    private final FinishedPhase finishedPhase;
    private final GameStateMachine gameStateMachine;

    /**
     * 创建上下文并一次性装配全部组件。
     * <p>
     * 构造顺序即依赖顺序，四段之间<b>不可调换</b>：
     * <ol>
     *     <li>基础设施：配置 → 交互/物品注册表 → 说明书 → 断线保护；</li>
     *     <li>领域层：队伍 → 票数 → 角色桥接（依赖队伍） → 据点（依赖插件取调度与日志）
     *     → 重生（依赖队伍/据点/角色桥接）；</li>
     *     <li>PLAYING 控制器：会话状态 → 指南针工厂 → 战斗物品工厂 → 表现层桥接 → 间歇期/死亡/
     *     部署/炸弹交互/对局闭环（这些控制器只互相持有引用，构造期间不调用任何阶段方法）；</li>
     *     <li>阶段与状态机：五个阶段 → 状态机（按状态注册阶段）。</li>
     * </ol>
     * 构造过程<b>不</b>注册监听器、<b>不</b>启动调度任务、<b>不</b>读取配置文件（{@link ConfigManager#init}
     * 由插件主类显式调用），因此构造本身很快，也不会在插件尚未就绪时产生副作用。
     *
     * @param plugin 插件主类实例（所有组件共用它取日志、调度与数据目录）
     */
    public GameContext(JavaPlugin plugin) {
        if(plugin == null){
            throw new IllegalArgumentException("plugin must not be null");
        }
        this.plugin = plugin;

        //1. 基础设施
        this.configManager = new ConfigManager(plugin);
        this.interactionManager = new InteractionManager(plugin);
        this.gameItemRegistry = new GameItemRegistry(interactionManager);
        this.guideBookFactory = new GuideBookFactory(plugin);
        this.disconnectProtection = new DisconnectProtection(plugin);

        //2. 领域层
        this.teamManager = new TeamManager();
        this.ticketManager = new TicketManager();
        this.roleBridge = new RoleBridge(plugin, configManager, teamManager);
        this.sectorManager = new SectorManager(plugin);
        this.spawnManager = new SpawnManager(plugin, sectorManager, teamManager, roleBridge);

        //3. PLAYING 阶段控制器
        this.matchSessionState = new MatchSessionState();
        this.compassItemFactory = CompassItemFactory.builder()
                .rightClickHandler(event -> GameEventBus.publish(
                        new com.sHDFGamePlugin.infrastructure.event.RightClickGameItemEvent(
                                event.getPlayer(), CompassItemFactory.DEFAULT_ITEM_ID)))
                .leftClickListener((player, bomb) -> announceCompassTarget(player, bomb))
                .gameItemRegistry(gameItemRegistry)
                .build();
        this.playingItemFactory = new PlayingItemFactory(compassItemFactory, gameItemRegistry,
                interactionManager.gameItemKey());
        this.matchDisplayBridge = new MatchDisplayBridge(compassItemFactory, this);
        this.intermissionController = new IntermissionController(this, playingItemFactory, roleBridge,
                RoleSelectionGui.create("gameItem_playingPhase_role_", null, true,
                        roleBridge, teamManager, interactionManager.gameItemKey()),
                RoleSelectionGui.create("gameItem_playingPhase_role_", null, false,
                        roleBridge, teamManager, interactionManager.gameItemKey()));
        this.deathHandler = new DeathHandler(this);
        this.deploymentController = new DeploymentController(this, playingItemFactory);
        this.bombInteractionController = new BombInteractionController(this);
        this.sectorProgressController = new SectorProgressController(this);

        //4. 阶段与状态机
        this.idlePhase = new IdlePhase(this);
        this.waitingPhase = new WaitingPhase(this);
        this.roleSelectingPhase = new RoleSelectingPhase(this,
                RoleSelectionGui.create("gameItem_roleSelectingPhase_role_",
                        "gameItem_roleSelectingPhase_clearRoleButton", true,
                        roleBridge, teamManager, interactionManager.gameItemKey()));
        this.playingPhase = new PlayingPhase(this);
        this.finishedPhase = new FinishedPhase(this);

        this.gameStateMachine = new GameStateMachine(plugin, idlePhase, waitingPhase, roleSelectingPhase,
                playingPhase, finishedPhase);
    }

    /**
     * 指南针左键反馈的转发：注册进 {@link CompassItemFactory} 的回调在构造期间创建，
     * 而真正的实现（ActionBar 播报 + 音效）在 {@link MatchDisplayBridge} 里，因此这里做一次
     * 延迟解析——回调只会在玩家左键时触发，那时 {@code matchDisplayBridge} 早已构造完成。
     */
    private void announceCompassTarget(org.bukkit.entity.Player player,
                                       com.sHDFGamePlugin.infrastructure.display.BattleBombInfo bomb) {
        if(matchDisplayBridge != null){
            matchDisplayBridge.announceCompassTarget(player, bomb);
        }
    }

    // ==================== 访问器 ====================

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public Scoreboard getTempScoreboard() {
        return tempScoreboard;
    }

    /** 由插件主类在创建记分板后注入（准备/选角阶段与表现层复位共用） */
    public void setTempScoreboard(Scoreboard tempScoreboard) {
        this.tempScoreboard = tempScoreboard;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public InteractionManager getInteractionManager() {
        return interactionManager;
    }

    public GameItemRegistry getGameItemRegistry() {
        return gameItemRegistry;
    }

    public GuideBookFactory getGuideBookFactory() {
        return guideBookFactory;
    }

    public DisconnectProtection getDisconnectProtection() {
        return disconnectProtection;
    }

    public TeamManager getTeamManager() {
        return teamManager;
    }

    public TicketManager getTicketManager() {
        return ticketManager;
    }

    public SectorManager getSectorManager() {
        return sectorManager;
    }

    public SpawnManager getSpawnManager() {
        return spawnManager;
    }

    public RoleBridge getRoleBridge() {
        return roleBridge;
    }

    public MatchSessionState getMatchSessionState() {
        return matchSessionState;
    }

    public CompassItemFactory getCompassItemFactory() {
        return compassItemFactory;
    }

    public PlayingItemFactory getPlayingItemFactory() {
        return playingItemFactory;
    }

    public MatchDisplayBridge getMatchDisplayBridge() {
        return matchDisplayBridge;
    }

    public IntermissionController getIntermissionController() {
        return intermissionController;
    }

    public DeathHandler getDeathHandler() {
        return deathHandler;
    }

    public DeploymentController getDeploymentController() {
        return deploymentController;
    }

    public BombInteractionController getBombInteractionController() {
        return bombInteractionController;
    }

    public SectorProgressController getSectorProgressController() {
        return sectorProgressController;
    }

    public IdlePhase getIdlePhase() {
        return idlePhase;
    }

    public WaitingPhase getWaitingPhase() {
        return waitingPhase;
    }

    public RoleSelectingPhase getRoleSelectingPhase() {
        return roleSelectingPhase;
    }

    public PlayingPhase getPlayingPhase() {
        return playingPhase;
    }

    public FinishedPhase getFinishedPhase() {
        return finishedPhase;
    }

    public GameStateMachine getGameStateMachine() {
        return gameStateMachine;
    }

    /** 按状态取对应阶段（阶段实现固定，见构造函数第 4 段） */
    public GamePhase getPhase(GameState state) {
        return gameStateMachine.getPhase(state);
    }

    // ==================== 生命周期收尾 ====================

    /**
     * 插件禁用时的收尾：触发状态机当前阶段的 onExit、注销监听器、清空事件总线订阅与"已打开 GUI"表。
     * <p>
     * 本方法之后不应再有任何静态引用指向本上下文的组件；插件主类在调用它之后应把上下文引用置空。
     * 幂等：重复调用安全（各组件自身的清理方法都带空判）。
     */
    public void shutdown() {
        //1. 状态机收尾：触发当前阶段的 onExit（退订事件、取消倒计时/任务、注销物品与侧边栏）
        gameStateMachine.shutdown();
        //2. 兜底清空事件总线：任何未被 onExit 覆盖的订阅都不再持有本上下文的引用
        GameEventBus.unsubscribeAll();
        //3. 交互管理器注销自身监听器（方块交互/丢弃/背包点击）
        interactionManager.unregister();
        //4. 关闭所有打开的 GUI 并释放其对玩家的引用
        ChestGui.closeAllGuis();
    }
}
