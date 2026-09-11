package com.sHDFGamePlugin.phase;

import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.core.GameState;
import com.sHDFGamePlugin.core.GameStateMachine;
import com.sHDFGamePlugin.domain.sector.SectorManager;
import com.sHDFGamePlugin.domain.spawn.SpawnManager;
import com.sHDFGamePlugin.domain.team.PlayerState;
import com.sHDFGamePlugin.domain.team.PlayerStatus;
import com.sHDFGamePlugin.domain.team.ShdfTeam;
import com.sHDFGamePlugin.domain.team.TeamManager;
import com.sHDFGamePlugin.domain.ticket.TicketManager;
import com.sHDFGamePlugin.infrastructure.DisconnectProtection;
import com.sHDFGamePlugin.infrastructure.GameEventBus;
import com.sHDFGamePlugin.infrastructure.RoleBridge;
import com.sHDFGamePlugin.infrastructure.config.ConfigManager;
import com.sHDFGamePlugin.infrastructure.config.MapConfig;
import com.sHDFGamePlugin.infrastructure.event.RightClickGameItemEvent;
import com.sHDFGamePlugin.infrastructure.event.ShdfPlayerJoinEvent;
import com.sHDFGamePlugin.infrastructure.event.ShdfPlayerQuitEvent;
import com.sHDFGamePlugin.infrastructure.gui.ChestGui;
import com.sHDFGamePlugin.phase.playing.BombInteractionController;
import com.sHDFGamePlugin.phase.playing.DeathHandler;
import com.sHDFGamePlugin.phase.playing.DeploymentController;
import com.sHDFGamePlugin.phase.playing.IntermissionController;
import com.sHDFGamePlugin.phase.playing.MatchDisplayBridge;
import com.sHDFGamePlugin.phase.playing.MatchSessionState;
import com.sHDFGamePlugin.phase.playing.PlayingItemFactory;
import com.sHDFGamePlugin.phase.playing.SectorProgressController;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * 对局阶段：炸弹据点推进玩法——<b>门面</b>。
 * <p>
 * 本类只负责阶段编排：{@code onEnter/onExit} 生命周期、事件订阅/退订、join/quit 路由、右键交互路由、
 * 控制器装配与本局系统初始化/清理。具体行为已按模块拆分（模块地图见 docs/08-playing-modules.md）：
 * <ul>
 *     <li>{@link MatchSessionState} —— 本局共享运行时状态（进度表、标志位、任务/监听器句柄）；</li>
 *     <li>{@link DeploymentController} —— 等待态、自动部署、重生计时驱动、重连恢复、观战转换、等待期守卫；</li>
 *     <li>{@link DeathHandler} —— 死亡接管、击杀广播、扣票、转入等待重生；</li>
 *     <li>{@link BombInteractionController} —— 装弹/拆弹读条、打断校验、移动冻结、已安放炸弹粒子；</li>
 *     <li>{@link PlayingItemFactory} —— 战斗物品构建与发放（slot 7 装弹/拆弹、slot 8 战斗菜单）；</li>
 *     <li>{@link SectorProgressController} —— 对局闭环：据点推进与胜负判定（票尽/据点超时=防守方胜，全据点攻占=进攻方胜）；</li>
 *     <li>{@link IntermissionController} —— 区域推进间隔（间歇期）：炸弹门闩、新据点时限起点、战斗菜单切角色入口；</li>
 *     <li>{@link MatchDisplayBridge} —— 表现层桥接：BossBar 据点链与炸弹明细、战斗侧边栏、slot 8 指南针。</li>
 * </ul>
 * 现状（等待重生入场模型）：
 * - onEnter 初始化本局系统（据点/炸弹、票数、重生配置）后，<b>所有参战玩家先视为"死亡"状态等待重生</b>：
 *   DEPLOYING + 进入重生队列（按 maps.yml 各阵营重生时间倒计时）+ 创造模式隐身 + 禁止破坏/放置方块
 *   （等待部署的玩家与观战者一并传送至<b>旁观者出生点</b>等待；物品栏保持为空，预留给未来的战术道具选择）；
 * - 重生倒计时结束后<b>自动部署进场</b>（无需点击物品）：由
 *   {@link SpawnManager#deployPlayer(UUID, String)} 传送至<b>当前据点本方出生区</b>
 *   （部署点取自 maps.yml 各 objective 出生区域，`role_selection.*_spawnpoint` 只是选角大厅）→
 *   ADVENTURE → 应用整场角色（RoleBridge 占用表此时填充）→ IN_BATTLE；
 * - join/quit：退出保留 PlayerStatus（断线保护）；加入时 IN_BATTLE 直接恢复战斗、
 *   DEPLOYING 恢复等待重生（倒计时已结束则立即自动部署），否则转观战者；
 *   空服退出先清理本局状态再回 IDLE。
 * - 真实死亡：见 {@link DeathHandler}。
 * <p>
 * 后续里程碑（docs/06 TODO）：战斗菜单、据点推进与对局结束判定（t20 的 SectorProgressController）、
 * 间歇期（t21 的 IntermissionController）、结算（t22）、表现层集成（t23 的 MatchDisplayBridge）。
 */
public class PlayingPhase implements GamePhase {

    private static final PlayingPhase INSTANCE = new PlayingPhase();

    private PlayingPhase() {}

    public static PlayingPhase getInstance() {
        return INSTANCE;
    }

    //事件订阅
    private GameEventBus.Subscription joinSubscription;
    private GameEventBus.Subscription quitSubscription;
    private GameEventBus.Subscription rightClickSubscription;

    @Override
    public void onEnter() {
        GameContext.getInstance().getPlugin().getLogger().info("游戏进入 PLAYING 状态");
        MatchSessionState.getInstance().setMatchEnded(false);

        //1. 初始化本局系统：防御性清理后加载据点/票数/重生配置
        initMatchSystems();
        //2. 入场：所有参战玩家先视为死亡状态等待重生（各阵营倒计时结束后自动部署）
        DeploymentController.getInstance().enterAwaitingRespawn();
        //3. 广播对局开始提示（以较长的阵营重生时间为准）
        DeploymentController.getInstance().broadcastMatchStart();
        //4. 注册等待期行为守卫（禁破坏/放置/攻击）
        DeploymentController.getInstance().registerGuard();
        //5. 注册战斗死亡监听器（真实死亡流程）
        DeathHandler.getInstance().register();
        //5.1 注册装弹/拆弹移动冻结守卫
        BombInteractionController.getInstance().registerFreezeGuard();
        //6. 启动重生倒计时驱动（每 tick 递减队列、播报死亡倒计时、就绪自动部署）
        DeploymentController.getInstance().startRespawnTickTask();
        //7. 订阅事件
        subscribeEvents();
        //7.1 间歇期模块：必须在订阅对局闭环之前——GameEventBus 按注册顺序分发，
        //    间歇期要在"据点推进（advanceToNextSector）激活新据点"之前看到"当前据点全部炸弹爆炸"
        IntermissionController.getInstance().start();
        //7.2 订阅对局闭环事件（据点攻占推进 / 票数耗尽 / 据点时限超时 → FINISHED）
        SectorProgressController.getInstance().subscribe();
        //8. 注册对局物品（装弹/拆弹/战斗菜单）并订阅右键交互
        PlayingItemFactory.getInstance().registerPlayingItems();
        subscribeRightClick();
        //9. 启动装弹/拆弹进度驱动与已激活炸弹粒子效果
        BombInteractionController.getInstance().startBombProgressTickTask();
        BombInteractionController.getInstance().startBombParticleTask();
        //10. 表现层桥接：BossBar/侧边栏/指南针（刷新任务按玩家状态自动收敛，部署/死亡/观战/重连无需单独接线）
        MatchDisplayBridge.getInstance().start();
    }

    @Override
    public void onExit() {
        MatchDisplayBridge.getInstance().stop();
        unsubscribeEvents();
        unsubscribeRightClick();
        SectorProgressController.getInstance().unsubscribe();
        IntermissionController.getInstance().stop();
        BombInteractionController.getInstance().stopBombProgressTickTask();
        BombInteractionController.getInstance().stopBombParticleTask();
        MatchSessionState.getInstance().clearActiveProgresses();
        PlayingItemFactory.getInstance().unregisterPlayingItems();
        DeploymentController.getInstance().stopRespawnTickTask();
        DeploymentController.getInstance().unregisterGuard();
        DeathHandler.getInstance().unregister();
        BombInteractionController.getInstance().unregisterFreezeGuard();
        MatchSessionState.getInstance().clearDeployFailureLogged();
        MatchSessionState.getInstance().clearDeathCountdown();
        //阶段切换清理：关闭所有打开的游戏 GUI，回收快捷栏中的阶段物品（slot 0 / 7 / 8）
        ChestGui.closeAllGuis();
        for(Player player : Bukkit.getOnlinePlayers()){
            player.getInventory().setItem(0, null);
            player.getInventory().setItem(7, null);
            player.getInventory().setItem(8, null);
        }
    }

    // ==================== 本局系统初始化 ====================

    /** 初始化本局据点/票数/重生系统（先防御性清理，防止上一局残留任务影响本局） */
    private void initMatchSystems(){
        ConfigManager config = ConfigManager.getInstance();
        MapConfig mapConfig = config.getSelectedMapConfig();
        if(mapConfig == null){
            GameContext.getInstance().getPlugin().getLogger().warning("[PlayingPhase] 未选择地图, 无法初始化对局系统!");
            return;
        }

        //防御性清理：停掉可能残留的引信/据点时限任务与队列
        SectorManager.getInstance().cleanup();
        SpawnManager.getInstance().clearAll();
        TicketManager.getInstance().reset();

        //区域推进间隔：来自地图级配置（缺键回退 0 = 攻占后立即开启，等同旧行为）
        SectorManager.getInstance().setSectorAdvanceInterval(mapConfig.getSectorAdvanceInterval());

        //载入当前地图的据点列表，激活并开启第一个据点（第一个据点开局即开启，间歇期只在推进时出现）
        SectorManager.getInstance().loadMap(mapConfig.getSectors());
        TicketManager.getInstance().init(mapConfig.getInitialTickets(), mapConfig.getMaxTickets());
        SpawnManager.getInstance().setCurrentMapConfig(mapConfig);
    }

    // ==================== 玩家加入/退出 ====================

    /** 加入事件入口：先取消挂起的断线保护；按保留状态恢复（IN_BATTLE 直接回场 / DEPLOYING 恢复等待重生），否则转观战者 */
    private void handlePlayerJoin(ShdfPlayerJoinEvent event){
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        TeamManager teamManager = TeamManager.getInstance();

        //玩家已上线，取消其挂起的断线保护任务
        DisconnectProtection.getInstance().cancel(uuid);

        PlayerStatus status = teamManager.getPlayerStatus(uuid);
        if(status != null && status.getTeam() != null && status.getTeam().isCombatant()){
            //断线重连：按保留的玩家状态恢复
            if(status.getState() == PlayerState.IN_BATTLE){
                DeploymentController.getInstance().restoreCombatant(player, status);
                return;
            }
            if(status.getState() == PlayerState.DEPLOYING){
                DeploymentController.getInstance().restoreAwaitingRespawn(player, status);
                return;
            }
        }

        //新来者 / 状态过期 → 转为观战者并传送观战出生点
        DeploymentController.getInstance().makeSpectator(player);
    }

    /** 退出事件入口：空服则清理本局状态后回 IDLE；否则保留 PlayerStatus（断线保护），超过重连时限仍未上线才移除 */
    private void handlePlayerQuit(ShdfPlayerQuitEvent event){
        UUID uuid = event.getPlayer().getUniqueId();
        MatchSessionState state = MatchSessionState.getInstance();

        //空服判定：PlayerQuitEvent 触发时退出者仍留在在线列表，必须按"除退出者外无人在线"判断
        if(isServerEmptyExcept(uuid)){
            //空服：先清理本局系统状态（引信/据点时限/重生队列/票数/角色占用/断线保护任务），防止跨局残留
            cleanupMatchState();
            GameStateMachine.getInstance().transitionTo(GameState.IDLE);
            return;
        }

        //断线保护：保留 PlayerStatus 与角色占用，超过重连时限仍未上线才移除
        DisconnectProtection.getInstance().start(
                GameContext.getInstance().getPlugin(),
                uuid,
                ConfigManager.getInstance().getPlayingReconnectTimeLimit(),
                expiredUuid -> {
                    TeamManager.getInstance().removePlayer(expiredUuid);
                    SpawnManager.getInstance().removePlayer(expiredUuid);
                    state.removeDeployFailureLogged(expiredUuid);
                    state.removeDeathCountdown(expiredUuid);
                    RoleBridge.getInstance().clearPlayerRole(expiredUuid);
                }
        );
    }

    /** 除指定玩家外是否已无人在线（quit 事件触发时退出者仍在在线列表，不能直接用 getOnlinePlayers().isEmpty()） */
    private boolean isServerEmptyExcept(UUID uuid){
        for(Player player : Bukkit.getOnlinePlayers()){
            if(!player.getUniqueId().equals(uuid)){
                return false;
            }
        }
        return true;
    }

    /** 空服回 IDLE 前的本局状态清理（与 RoleSelectingPhase/FinishedPhase 的清理语义一致） */
    private void cleanupMatchState(){
        //据点/炸弹：停止引信与据点时限任务
        SectorManager.getInstance().cleanup();
        //队伍/玩家状态：清空全部 PlayerStatus
        TeamManager.getInstance().reset();
        //重生队列
        SpawnManager.getInstance().clearAll();
        //票数
        TicketManager.getInstance().reset();
        //角色占用记录清空，重复规则还原为配置默认值
        RoleBridge.getInstance().clearAllOccupiedRoles();
        RoleBridge.getInstance().setAllowDuplicateRoles(ConfigManager.getInstance().isAllowDuplicateRoles());
        //取消所有挂起的断线保护任务（服务器已空，无重连可能）
        DisconnectProtection.getInstance().cancelAll();
        //关闭所有打开的游戏 GUI
        ChestGui.closeAllGuis();
    }

    // ==================== 右键物品交互路由 ====================

    private void subscribeRightClick(){
        rightClickSubscription = GameEventBus.subscribe(RightClickGameItemEvent.class, this::handleRightClickGameItem);
    }

    private void unsubscribeRightClick(){
        if(rightClickSubscription != null){
            rightClickSubscription.unsubscribe();
            rightClickSubscription = null;
        }
    }

    /** 右键物品入口：战斗菜单任意状态可用；装弹/拆弹需 IN_BATTLE 参战玩家 */
    private void handleRightClickGameItem(RightClickGameItemEvent event){
        if(MatchSessionState.getInstance().isMatchEnded()) return;
        Player player = event.getPlayer();
        String itemId = event.getGameItemId();

        //slot 8 战斗指南针：任意状态右键打开战斗菜单（左键切换跟踪目标由 CompassItemFactory 内部处理）
        if(itemId.equals(PlayingItemFactory.COMPASS_ITEM_ID)){
            openBattleMenu(player);
            return;
        }

        //装弹/拆弹：仅 IN_BATTLE 参战玩家
        PlayerStatus status = TeamManager.getInstance().getPlayerStatus(player.getUniqueId());
        if(status == null || status.getState() != PlayerState.IN_BATTLE || !status.getTeam().isCombatant()) return;

        boolean isPlant;
        if(itemId.equals(PlayingItemFactory.PLANT_ITEM_ID)){
            if(status.getTeam() != ShdfTeam.ATTACKER) return;
            isPlant = true;
        }
        else if(itemId.equals(PlayingItemFactory.DEFUSE_ITEM_ID)){
            if(status.getTeam() != ShdfTeam.DEFENDER) return;
            isPlant = false;
        }
        else{
            return;
        }

        BombInteractionController.getInstance().tryStartBombProgress(player, isPlant);
    }

    /**
     * 打开战斗菜单：间歇期内提供"切换角色"入口，间歇期结束/非间歇期为不可点击的屏障。
     * <p>
     * 菜单句柄交给 {@link IntermissionController} 跟踪，供间歇期开始/结束时刷新该入口的可用状态。
     */
    private void openBattleMenu(Player player){
        ChestGui gui = ChestGui.Builder.create()
                .title(Component.text("战斗菜单", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
                .rows(3)
                .build();
        gui.setSlot(PlayingItemFactory.BATTLE_MENU_SWITCH_ROLE_SLOT,
                PlayingItemFactory.getInstance().createSwitchRoleMenuItem(
                        IntermissionController.getInstance().isIntermissionActive()));
        gui.open(player);
        IntermissionController.getInstance().trackBattleMenu(player, gui);
    }

    // ==================== 事件订阅 ====================

    private void subscribeEvents(){
        joinSubscription = GameEventBus.subscribe(ShdfPlayerJoinEvent.class, this::handlePlayerJoin);
        quitSubscription = GameEventBus.subscribe(ShdfPlayerQuitEvent.class, this::handlePlayerQuit);
    }

    private void unsubscribeEvents(){
        if(joinSubscription != null){
            joinSubscription.unsubscribe();
            joinSubscription = null;
        }
        if(quitSubscription != null){
            quitSubscription.unsubscribe();
            quitSubscription = null;
        }
    }
}
