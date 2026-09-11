package com.sHDFGamePlugin.infrastructure.display;

import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.infrastructure.GameEventBus;
import com.sHDFGamePlugin.infrastructure.event.RightClickGameItemEvent;
import com.sHDFGamePlugin.infrastructure.item.GameItemRegistry;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 战斗表现层门面：把 BossBar 总览/明细、战斗侧边栏、slot 8 指南针收成一个生命周期对象。
 *
 * <h2>集成方（t23）只需三步</h2>
 * <ol>
 *   <li>{@code onEnter}：{@link #start()} → {@link #show(Player)}（对局开始时的在线玩家）；</li>
 *   <li>每 20 tick 或事件驱动：填好 {@link BattleDisplayState} 后调 {@link #update(BattleDisplayState, Function)}
 *       （<b>不要每 tick 调用</b>，本门面自带 20 tick 定时刷新）；</li>
 *   <li>{@code onExit}：{@link #stop()}（幂等：摘除全部 BossBar、注销侧边栏 objective、注销指南针 GameItem）。</li>
 * </ol>
 * 指南针物品由 {@link #compassFactory()} 提供：{@code createItem()} 造物品，
 * 槽位由集成方决定（约定 slot 8）。
 *
 * <h2>刷新节流</h2>
 * {@link #start()} 注册 20 tick 的全局区域调度任务（初始 delay 1L，避免 delay=0 抛
 * {@link IllegalArgumentException}），每周期回调 {@link #setRefreshAction(Runnable)} 注入的动作。
 * 集成方在动作里收集一帧数据（票数 / 据点链 / 倒计时）后调 {@link #update}；
 * 门面不会每 tick 重建任何 BossBar/Objective。
 */
public final class BattleDisplayService {

    /** 刷新周期（tick）：1 秒一次，避免每 tick 重建 BossBar/Objective */
    public static final long REFRESH_PERIOD_TICKS = 20L;

    private final BattleSectorBarRenderer barRenderer;
    private final BattleSidebarRenderer sidebarRenderer;
    private final CompassItemFactory compassFactory;

    //已显示的玩家（UUID -> 用于取阵营/角色的在线 Player 由渲染时解析）
    private final Set<UUID> displayedPlayers = new LinkedHashSet<>();

    private Runnable refreshAction;
    private ScheduledTask refreshTask;

    /**
     * 默认构建：指南针右键发布既有的 {@link RightClickGameItemEvent}
     * （工程内"快捷栏物品右键 → 事件总线"的统一模式）；
     * 集成方订阅该事件并判断 id 是否为 {@link CompassItemFactory#DEFAULT_ITEM_ID} 即可。
     * <p>构造时<b>不</b>注册任何 GameItem、<b>不</b>启动任何调度，需显式调 {@link #start()}。</p>
     */
    public BattleDisplayService() {
        this(new BattleSectorBarRenderer(), new BattleSidebarRenderer(),
                CompassItemFactory.builder()
                        .rightClickHandler(event -> GameEventBus.publish(
                                new RightClickGameItemEvent(event.getPlayer(), CompassItemFactory.DEFAULT_ITEM_ID)))
                        .build());
    }

    public BattleDisplayService(BattleSectorBarRenderer barRenderer,
                                BattleSidebarRenderer sidebarRenderer,
                                CompassItemFactory compassFactory) {
        if(barRenderer == null || sidebarRenderer == null || compassFactory == null){
            throw new IllegalArgumentException("renderers and compassFactory must not be null");
        }
        this.barRenderer = barRenderer;
        this.sidebarRenderer = sidebarRenderer;
        this.compassFactory = compassFactory;
    }

    public BattleSectorBarRenderer barRenderer() {
        return barRenderer;
    }

    public BattleSidebarRenderer sidebarRenderer() {
        return sidebarRenderer;
    }

    public CompassItemFactory compassFactory() {
        return compassFactory;
    }

    // ==================== 生命周期 ====================

    /**
     * 启动表现层：注册指南针 GameItem + 注册 20 tick 刷新任务（初始 delay 1L）。
     * <p>幂等：重复调用只保证任务唯一。</p>
     */
    public void start() {
        compassFactory.register();
        startRefreshTask();
    }

    /**
     * 一次性清理（阶段 {@code onExit} 调用，幂等）：
     * <ol>
     *   <li>取消 20 tick 刷新任务；</li>
     *   <li>摘除全部玩家的 BossBar（含明细条）并释放实例；</li>
     *   <li>注销侧边栏 objective 并清空记录（不留跨阶段冻结侧边栏）；</li>
     *   <li>注销指南针 GameItem 并清空指向记录。</li>
     * </ol>
     */
    public void stop() {
        stopRefreshTask();
        barRenderer.clear();
        sidebarRenderer.unregister();
        compassFactory.unregister();
        displayedPlayers.clear();
        refreshAction = null;
    }

    /** 给玩家显示表现层三件套（BossBar + 侧边栏）；幂等 */
    public void show(Player player, BattleDisplayState state, BattleDisplayState.PlayerView view) {
        if(player == null){
            return;
        }
        displayedPlayers.add(player.getUniqueId());
        barRenderer.show(player);
        sidebarRenderer.show(player, state, view);
        //把当前据点的炸弹列表注入指南针指向逻辑（左键切换用）
        compassFactory.updateBombs(player, currentBombs(state));
    }

    /** 单玩家隐藏（死亡转观战、踢出等场景），不影响其他玩家 */
    public void hide(Player player) {
        if(player == null){
            return;
        }
        displayedPlayers.remove(player.getUniqueId());
        barRenderer.hide(player);
        sidebarRenderer.hide(player);
        compassFactory.clearTarget(player);
    }

    /**
     * 全局刷新：更新总览条与每颗炸弹明细条，并按玩家刷新侧边栏。
     *
     * @param state    全局帧数据
     * @param viewOf   玩家维度数据解析器（阵营名 / 角色名）；返回 null 时按"未分配/未选择角色"渲染
     */
    public void update(BattleDisplayState state, Function<Player, BattleDisplayState.PlayerView> viewOf) {
        barRenderer.update(state);
        for(UUID uuid : new ArrayList<>(displayedPlayers)){
            Player player = Bukkit.getPlayer(uuid);
            if(player == null || !player.isOnline()){
                displayedPlayers.remove(uuid);
                continue;
            }
            compassFactory.updateBombs(player, currentBombs(state));
            BattleDisplayState.PlayerView view = viewOf == null ? null : viewOf.apply(player);
            sidebarRenderer.update(player, state, view);
        }
    }

    /** 便捷重载：不区分玩家，全部按同一 view 渲染 */
    public void update(BattleDisplayState state, BattleDisplayState.PlayerView view) {
        update(state, player -> view);
    }

    // ==================== 刷新任务 ====================

    /** 注入 20 tick 周期回调（通常在里面收集数据并调 {@link #update}） */
    public void setRefreshAction(Runnable refreshAction) {
        this.refreshAction = refreshAction;
    }

    /** 立即执行一次刷新回调（进入阶段时先渲染一帧，避免等 1 秒） */
    public void refreshNow() {
        if(refreshAction != null){
            refreshAction.run();
        }
    }

    private void startRefreshTask() {
        if(refreshTask != null){
            return;
        }
        refreshTask = GameContext.getInstance().getPlugin().getServer().getGlobalRegionScheduler()
                .runAtFixedRate(GameContext.getInstance().getPlugin(),
                        scheduledTask -> {
                            if(refreshAction != null){
                                refreshAction.run();
                            }
                        },
                        1L, REFRESH_PERIOD_TICKS);
    }

    private void stopRefreshTask() {
        if(refreshTask != null){
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    private static java.util.List<BattleBombInfo> currentBombs(BattleDisplayState state) {
        if(state == null || state.activeSector() == null){
            return java.util.List.of();
        }
        return state.activeSector().bombs();
    }

    /** 自检：当前记录为"已显示"的玩家数 */
    public int displayedPlayerCount() {
        return displayedPlayers.size();
    }

    /** 自检：刷新任务是否在跑 */
    public boolean isRefreshTaskRunning() {
        return refreshTask != null;
    }

    /** 自检：已注册的指南针 GameItem id（未注册返回 null） */
    public String registeredCompassItemId() {
        if(GameItemRegistry.getGameItem(compassFactory.itemId()) == null){
            return null;
        }
        return compassFactory.itemId();
    }
}
