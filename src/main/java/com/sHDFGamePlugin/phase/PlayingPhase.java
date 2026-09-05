package com.sHDFGamePlugin.phase;

import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.core.GameState;
import com.sHDFGamePlugin.core.GameStateMachine;
import com.sHDFGamePlugin.domain.sector.Sector;
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
import com.sHDFGamePlugin.infrastructure.event.ShdfPlayerJoinEvent;
import com.sHDFGamePlugin.infrastructure.event.ShdfPlayerQuitEvent;
import com.sHDFGamePlugin.infrastructure.gui.ChestGui;
import com.sHDFGamePlugin.infrastructure.regionNotation.CubeRegion;
import com.sHDFGamePlugin.util.MessageUtil;
import com.sHDFGamePlugin.util.SoundUtil;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 对局阶段：炸弹据点推进玩法。
 * <p>
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
 * - 真实死亡：IN_BATTLE 参战玩家死亡 → 取消原版死亡（保持原地不传送）→ 死亡瞬间广播击杀信息
 *   （经 RoleAPI.getLastDamagerUuid 定位击杀者，勿用原版 getKiller）→ 受害者"你死了！"标题 →
 *   进攻方扣 1 票 → 原地转等待重生（创造隐身）并每秒播报"将在 X 秒后重新部署"，
 *   倒计时结束自动部署回本方出生区。
 * <p>
 * 后续里程碑（docs/06 TODO）：战斗菜单、安放/拆弹进度与打断、据点推进与对局结束判定。
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

    //重生倒计时 tick 驱动任务
    private ScheduledTask respawnTickTask;

    //自动部署失败的玩家（用于失败日志去重，成功后移除）
    private final Set<UUID> deployFailureLogged = new HashSet<>();

    //等待重生玩家的行为守卫（禁破坏/放置、禁攻击）
    private AwaitingGuardListener guardListener;

    //战斗死亡监听器（仅 PLAYING 期间生效）
    private CombatDeathListener deathListener;

    //死后等待重生的玩家 -> 最近一次播报的秒数（每秒播报去重；开局等待的玩家不在此表）
    private final Map<UUID, Integer> deathCountdownLastSecond = new HashMap<>();

    //本局是否已结束（防多个结束条件重复触发结算，战斗玩法里程碑使用）
    private boolean matchEnded;

    @Override
    public void onEnter() {
        GameContext.getInstance().getPlugin().getLogger().info("游戏进入 PLAYING 状态");
        matchEnded = false;

        //1. 初始化本局系统：防御性清理后加载据点/票数/重生配置
        initMatchSystems();
        //2. 入场：所有参战玩家先视为死亡状态等待重生（各阵营倒计时结束后自动部署）
        enterAwaitingRespawn();
        //3. 广播对局开始提示（以较长的阵营重生时间为准）
        broadcastMatchStart();
        //4. 注册等待期行为守卫（禁破坏/放置/攻击）
        registerGuard();
        //5. 注册战斗死亡监听器（真实死亡流程）
        registerDeathListener();
        //6. 启动重生倒计时驱动（每 tick 递减队列、播报死亡倒计时、就绪自动部署）
        startRespawnTickTask();
        //7. 订阅事件
        subscribeEvents();
    }

    @Override
    public void onExit() {
        unsubscribeEvents();
        stopRespawnTickTask();
        unregisterGuard();
        unregisterDeathListener();
        deployFailureLogged.clear();
        deathCountdownLastSecond.clear();
        //阶段切换清理：关闭所有打开的游戏 GUI，回收快捷栏中的阶段物品（slot 0 / 8）
        ChestGui.closeAllGuis();
        for(Player player : Bukkit.getOnlinePlayers()){
            player.getInventory().setItem(0, null);
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

        //载入当前地图的据点列表并激活第一个据点（内部启动据点时限）
        SectorManager.getInstance().loadMap(mapConfig.getSectors());
        TicketManager.getInstance().init(mapConfig.getInitialTickets(), mapConfig.getMaxTickets());
        SpawnManager.getInstance().setCurrentMapConfig(mapConfig);
    }

    // ==================== 入场：全员等待重生 ====================

    /**
     * 入场处理：所有参战玩家先视为"死亡"状态等待重生——DEPLOYING + 进重生队列（按阵营倒计时）
     * + 创造模式隐身（等待期预留给未来战术道具选择）+ 由守卫禁止破坏/放置。
     * 等待部署的玩家与观战者一并传送至<b>旁观者出生点</b>等待（不暴露在出生区），
     * 倒计时结束由 tick 驱动自动部署到本方出生区（见 {@link #autoDeployReadyPlayers()}）。
     */
    private void enterAwaitingRespawn(){
        ConfigManager config = ConfigManager.getInstance();
        MapConfig mapConfig = config.getSelectedMapConfig();
        if(mapConfig == null) return;

        World world = Bukkit.getWorld(mapConfig.getWorld());
        if(world == null){
            GameContext.getInstance().getPlugin().getLogger().warning("[PlayingPhase] 地图世界不存在, 玩家将停留在原地等待重生!");
        }
        //等待部署的玩家与观战者的统一等待点：旁观者出生点
        Vector spectatorSpawn = config.getSpectatorSpawnpoint();

        TeamManager teamManager = TeamManager.getInstance();
        for(Player player : Bukkit.getOnlinePlayers()){
            PlayerStatus status = teamManager.getPlayerStatus(player.getUniqueId());
            if(status == null) continue;

            //清理上一阶段遗留物品（角色选择物品等）；等待期物品栏保持为空（无任何 GameItem）
            player.getInventory().clear();

            ShdfTeam team = status.getTeam();
            if(team != null && team.isCombatant()){
                //进入等待重生状态：创造模式隐身 + 不可碰撞
                setAwaitingLook(player);
                //DEPLOYING + 进入重生队列（按地图配置的本方重生时间倒计时）
                status.setState(PlayerState.DEPLOYING);
                SpawnManager.getInstance().addPlayer(player.getUniqueId(), team);
            }
            else{
                //观战者：保持观战模式
                player.setGameMode(GameMode.SPECTATOR);
            }
            //等待部署的玩家与观战者一并传送至旁观者出生点
            if(spectatorSpawn != null && world != null){
                player.teleport(spectatorSpawn.toLocation(world));
            }
        }
    }

    /** 设定"等待重生"表现：创造模式 + 无粒子永久隐身效果 + 不可碰撞 + 空背包（破坏/放置由守卫拦截） */
    private void setAwaitingLook(Player player){
        player.getInventory().clear();
        player.setGameMode(GameMode.CREATIVE);
        //不使用实体隐身标志位：改挂无粒子永久隐身效果（部署/转观战时移除该效果即恢复可见）
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,
                Integer.MAX_VALUE, 0, false, false, false));
        player.setCollidable(false);
    }

    /** 是否处于"等待重生"状态（用于守卫拦截） */
    private boolean isAwaitingRespawn(Player player){
        PlayerStatus status = TeamManager.getInstance().getPlayerStatus(player.getUniqueId());
        return status != null && status.getState() == PlayerState.DEPLOYING;
    }

    // ==================== 重生倒计时驱动与自动部署 ====================

    /** 启动每 tick 的重生驱动：递减重生队列，倒计时结束的玩家自动部署进场 */
    private void startRespawnTickTask(){
        respawnTickTask = GameContext.getInstance().getPlugin().getServer().getGlobalRegionScheduler()
                .runAtFixedRate(GameContext.getInstance().getPlugin(),
                        scheduledTask -> {
                            if(matchEnded) return;
                            SpawnManager.getInstance().update();
                            sendDeathCountdownMessages();
                            autoDeployReadyPlayers();
                        },
                        1L, 1L);
    }

    private void stopRespawnTickTask(){
        if(respawnTickTask != null){
            respawnTickTask.cancel();
            respawnTickTask = null;
        }
    }

    /** 检测重生倒计时已结束的在线参战玩家，直接自动部署（无需点击物品） */
    private void autoDeployReadyPlayers(){
        TeamManager teamManager = TeamManager.getInstance();
        SpawnManager spawnManager = SpawnManager.getInstance();
        for(Player player : Bukkit.getOnlinePlayers()){
            UUID uuid = player.getUniqueId();
            PlayerStatus status = teamManager.getPlayerStatus(uuid);
            if(status == null || status.getState() != PlayerState.DEPLOYING) continue;
            if(!status.getTeam().isCombatant()) continue;
            if(!spawnManager.canRespawn(uuid)) continue;

            autoDeploy(player, status);
        }
    }

    /** 自动部署：调用 SpawnManager.deployPlayer（传送出生区 + ADVENTURE + 应用角色 + IN_BATTLE） */
    private void autoDeploy(Player player, PlayerStatus status){
        UUID uuid = player.getUniqueId();
        boolean success = SpawnManager.getInstance().deployPlayer(uuid, status.getSelectedRoleId());
        if(!success){
            //部署失败（角色应用失败/世界缺失等）：留待下一 tick 重试，失败日志只记一次
            if(deployFailureLogged.add(uuid)){
                GameContext.getInstance().getPlugin().getLogger().warning(
                        "[PlayingPhase] 玩家 " + player.getName() + " 自动部署失败, 将每 tick 重试!");
            }
            return;
        }

        deployFailureLogged.remove(uuid);
        deathCountdownLastSecond.remove(uuid);
        //移除无粒子隐身效果即恢复可见（死亡事件被取消，另需手动复位生命值；等待期物品栏本就为空）
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        player.setCollidable(true);
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        MessageUtil.sendMessageWithPrefix(player, Component.text("已部署进场, 开始行动!", NamedTextColor.GREEN));
        SoundUtil.playNoticeSuccessCombinedSound(player);
    }

    // ==================== 真实死亡与死后等待 ====================

    /** 对"死后等待重生"的玩家每秒播报一次重新部署倒计时（仅死亡玩家，开局等待不播报） */
    private void sendDeathCountdownMessages(){
        for(Player player : Bukkit.getOnlinePlayers()){
            UUID uuid = player.getUniqueId();
            if(!deathCountdownLastSecond.containsKey(uuid)) continue;

            PlayerStatus status = TeamManager.getInstance().getPlayerStatus(uuid);
            if(status == null || status.getState() != PlayerState.DEPLOYING || !status.getTeam().isCombatant()){
                deathCountdownLastSecond.remove(uuid);
                continue;
            }
            int remainingTicks = SpawnManager.getInstance().getRemainingRespawnTime(uuid);
            int seconds = (int) Math.ceil(remainingTicks / 20.0);
            if(seconds <= 0) continue;
            if(Integer.valueOf(seconds).equals(deathCountdownLastSecond.get(uuid))) continue;
            deathCountdownLastSecond.put(uuid, seconds);
            MessageUtil.sendMessageWithPrefix(player,
                    Component.text("将在 " + seconds + " 秒后重新部署", NamedTextColor.GRAY));
        }
    }

    /** 构建击杀信息：经 RoleAPI.getLastDamagerUuid 定位击杀者（勿用原版 getKiller）；无击杀者则报"阵亡" */
    private Component buildKillMessage(Player victim){
        UUID killerUuid = RoleBridge.getInstance().getLastDamagerUuid(victim);
        Player killer = killerUuid == null ? null : Bukkit.getPlayer(killerUuid);
        if(killer != null && killer != victim){
            PlayerStatus killerStatus = TeamManager.getInstance().getPlayerStatus(killerUuid);
            if(killerStatus != null && killerStatus.getTeam() != null && killerStatus.getTeam().isCombatant()){
                return Component.text(victim.getName() + " 被 " + killer.getName() + " 击杀了", NamedTextColor.GRAY);
            }
        }
        return Component.text(victim.getName() + " 阵亡了", NamedTextColor.GRAY);
    }

    /** 死亡处理：仅处理 IN_BATTLE 参战玩家；取消原版死亡以保持原地（不传送），转入等待重生流程 */
    private void handlePlayerDeath(PlayerDeathEvent event){
        Player victim = event.getEntity();
        if(matchEnded) return;

        PlayerStatus status = TeamManager.getInstance().getPlayerStatus(victim.getUniqueId());
        if(status == null || status.getTeam() == null || !status.getTeam().isCombatant()) return;
        if(status.getState() != PlayerState.IN_BATTLE) return;

        //取消原版死亡：不掉落物品、不出现死亡界面，玩家停留在死亡位置（不传送）
        event.setCancelled(true);
        victim.setHealth(victim.getMaxHealth());
        victim.setFireTicks(0);

        //死亡瞬间广播击杀信息
        MessageUtil.broadcastPrefixedMessage(buildKillMessage(victim));

        //受害者死亡标题
        victim.showTitle(Title.title(
                Component.text("你死了！", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("等待重新部署", NamedTextColor.RED),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ofMillis(500))));

        //进攻方死亡扣 1 票（耗尽时发布 TicketDepletedEvent，对局结束判定后续里程碑接听）
        if(status.getTeam() == ShdfTeam.ATTACKER){
            TicketManager.getInstance().decreaseTicket(1);
        }

        //原地转为等待重生（创造隐身，不传送）+ DEPLOYING + 进重生队列
        setAwaitingLook(victim);
        status.setState(PlayerState.DEPLOYING);
        SpawnManager.getInstance().addPlayer(victim.getUniqueId(), status.getTeam());
        //标记为"死后等待"，开始每秒播报重新部署倒计时
        deathCountdownLastSecond.put(victim.getUniqueId(), -1);
    }

    /** 战斗死亡监听器（随阶段注册/注销） */
    private class CombatDeathListener implements Listener {

        @EventHandler
        public void onPlayerDeath(PlayerDeathEvent event){
            handlePlayerDeath(event);
        }
    }

    private void registerDeathListener(){
        deathListener = new CombatDeathListener();
        Bukkit.getPluginManager().registerEvents(deathListener, GameContext.getInstance().getPlugin());
    }

    private void unregisterDeathListener(){
        if(deathListener != null){
            for(HandlerList handlerList : HandlerList.getHandlerLists()){
                handlerList.unregister(deathListener);
            }
            deathListener = null;
        }
    }

    /** 广播对局开始提示：以双方重生时间中较长者为"对局开始"倒计时（秒） */
    private void broadcastMatchStart(){
        MapConfig mapConfig = ConfigManager.getInstance().getSelectedMapConfig();
        if(mapConfig == null) return;
        int maxTicks = Math.max(mapConfig.getAttackerRespawnTime(), mapConfig.getDefenderRespawnTime());
        int seconds = (int) Math.ceil(maxTicks / 20.0);
        MessageUtil.sendPrefixedMessageToAllPlayers(
                Component.text("对局将在 " + seconds + " 秒后开始!", NamedTextColor.GOLD, TextDecoration.BOLD));
    }

    // ==================== 等待重生行为守卫 ====================

    /** 等待重生期间禁止破坏/放置方块、禁止攻击（创造模式下玩家仍可破坏/攻击） */
    private class AwaitingGuardListener implements Listener {

        @EventHandler(ignoreCancelled = true)
        public void onBlockBreak(BlockBreakEvent event){
            if(isAwaitingRespawn(event.getPlayer())){
                event.setCancelled(true);
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onBlockPlace(BlockPlaceEvent event){
            if(isAwaitingRespawn(event.getPlayer())){
                event.setCancelled(true);
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onEntityDamageByEntity(EntityDamageByEntityEvent event){
            if(event.getDamager() instanceof Player damager && isAwaitingRespawn(damager)){
                event.setCancelled(true);
            }
        }
    }

    private void registerGuard(){
        guardListener = new AwaitingGuardListener();
        Bukkit.getPluginManager().registerEvents(guardListener, GameContext.getInstance().getPlugin());
    }

    private void unregisterGuard(){
        if(guardListener != null){
            for(HandlerList handlerList : HandlerList.getHandlerLists()){
                handlerList.unregister(guardListener);
            }
            guardListener = null;
        }
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
                restoreCombatant(player, status);
                return;
            }
            if(status.getState() == PlayerState.DEPLOYING){
                restoreAwaitingRespawn(player, status);
                return;
            }
        }

        //新来者 / 状态过期 → 转为观战者并传送观战出生点
        makeSpectator(player);
    }

    /** 恢复战斗身份：传送至当前据点本方出生区，重新应用角色并恢复可见 */
    private void restoreCombatant(Player player, PlayerStatus status){
        player.setGameMode(GameMode.ADVENTURE);
        //恢复战斗身份：移除无粒子隐身效果（断线期间效果可能残留）
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        player.setCollidable(true);

        ConfigManager config = ConfigManager.getInstance();
        Sector sector = SectorManager.getInstance().getCurrentSector();
        World world = null;
        if(sector != null && config.getSelectedMapConfig() != null){
            world = Bukkit.getWorld(config.getSelectedMapConfig().getWorld());
        }
        if(sector != null && world != null){
            CubeRegion spawnRegion;
            if(status.getTeam() == ShdfTeam.ATTACKER){
                spawnRegion = sector.getAttackerSpawnRegion();
            }
            else{
                spawnRegion = sector.getDefenderSpawnRegion();
            }
            player.teleport(spawnRegion.randomPoint().toLocation(world));
        }
        applyRole(player, status);

        MessageUtil.sendMessageWithPrefix(player, Component.text("欢迎回来, 你仍在对局中", NamedTextColor.GREEN));
    }

    /** 恢复"等待重生"身份：维持 DEPLOYING 表现并回旁观者出生点等待；若重生倒计时已结束则立即自动部署 */
    private void restoreAwaitingRespawn(Player player, PlayerStatus status){
        player.getInventory().clear();
        setAwaitingLook(player);

        //与观战者一致：在旁观者出生点等待部署（自动部署时才会传送至本方出生区）
        ConfigManager config = ConfigManager.getInstance();
        MapConfig mapConfig = config.getSelectedMapConfig();
        if(mapConfig != null){
            World world = Bukkit.getWorld(mapConfig.getWorld());
            Vector spectatorSpawn = config.getSpectatorSpawnpoint();
            if(world != null && spectatorSpawn != null){
                player.teleport(spectatorSpawn.toLocation(world));
            }
        }

        //重生倒计时可能已在其离线期间结束：直接自动部署
        if(SpawnManager.getInstance().canRespawn(player.getUniqueId())){
            autoDeploy(player, status);
            return;
        }
        MessageUtil.sendMessageWithPrefix(player, Component.text("欢迎回来, 你仍在等待重生, 倒计时结束将自动部署", NamedTextColor.GOLD));
    }

    /** 应用玩家整场选定的角色；selectedRoleId 缺失或应用失败仅告警，不阻断流程 */
    private void applyRole(Player player, PlayerStatus status){
        String roleId = status.getSelectedRoleId();
        if(roleId == null || roleId.isEmpty()) return;
        if(!RoleBridge.getInstance().setPlayerRole(player.getUniqueId(), roleId)){
            GameContext.getInstance().getPlugin().getLogger().warning(
                    "[PlayingPhase] 玩家 " + player.getName() + " 的角色应用失败: " + roleId);
        }
    }

    /** 转为观战者：传送至地图观战出生点 */
    private void makeSpectator(Player player){
        ConfigManager configManager = ConfigManager.getInstance();
        if(configManager.getSelectedMapConfig() == null){
            GameContext.getInstance().getPlugin().getLogger().warning("Selected map is null when a player joined in PlayingPhase!");
            return;
        }
        World world = Bukkit.getWorld(configManager.getSelectedMapConfig().getWorld());
        if(world == null){
            GameContext.getInstance().getPlugin().getLogger().warning("World could not be found when a player joined in PlayingPhase! Player Kicked!");
            player.kick(Component.text("SHDF插件出现意外错误", NamedTextColor.RED, TextDecoration.BOLD));
            return;
        }
        Location spawnLocation = configManager.getSpectatorSpawnpoint().toLocation(world);
        player.teleport(spawnLocation);

        UUID uuid = player.getUniqueId();
        TeamManager teamManager = TeamManager.getInstance();
        //清理重生队列中的残留记录后，释放旧角色占用并按观战者重新注册
        SpawnManager.getInstance().removePlayer(uuid);
        deployFailureLogged.remove(uuid);
        deathCountdownLastSecond.remove(uuid);
        RoleBridge.getInstance().clearPlayerRole(uuid);
        teamManager.removePlayer(uuid);
        teamManager.addPlayer(uuid, ShdfTeam.SPECTATOR, PlayerState.IN_BATTLE);

        MessageUtil.sendMessageWithPrefix(player, Component.text("你已在对局中成为旁观者, 请等待对局结束"));
        player.getInventory().clear();
        player.setGameMode(GameMode.SPECTATOR);
        //转观战：移除无粒子隐身效果（观战模式自身即隐身）
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        player.setCollidable(true);
    }

    /** 退出事件入口：空服则清理本局状态后回 IDLE；否则保留 PlayerStatus（断线保护），超过重连时限仍未上线才移除 */
    private void handlePlayerQuit(ShdfPlayerQuitEvent event){
        UUID uuid = event.getPlayer().getUniqueId();

        if(Bukkit.getOnlinePlayers().isEmpty()){
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
                    deployFailureLogged.remove(expiredUuid);
                    deathCountdownLastSecond.remove(expiredUuid);
                    RoleBridge.getInstance().clearPlayerRole(expiredUuid);
                }
        );
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
