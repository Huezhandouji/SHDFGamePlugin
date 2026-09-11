package com.sHDFGamePlugin.phase.playing;

import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.domain.sector.Sector;
import com.sHDFGamePlugin.domain.sector.SectorManager;
import com.sHDFGamePlugin.domain.spawn.SpawnManager;
import com.sHDFGamePlugin.domain.team.PlayerState;
import com.sHDFGamePlugin.domain.team.PlayerStatus;
import com.sHDFGamePlugin.domain.team.ShdfTeam;
import com.sHDFGamePlugin.domain.team.TeamManager;
import com.sHDFGamePlugin.infrastructure.RoleBridge;
import com.sHDFGamePlugin.infrastructure.config.ConfigManager;
import com.sHDFGamePlugin.infrastructure.config.MapConfig;
import com.sHDFGamePlugin.infrastructure.regionNotation.CubeRegion;
import com.sHDFGamePlugin.util.MessageUtil;
import com.sHDFGamePlugin.util.SoundUtil;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * 部署与重生模块：等待态表现、重生计时驱动与自动部署、重连恢复、观战转换、等待期行为守卫。
 * <p>
 * 现状（等待重生入场模型）：
 * <ul>
 *     <li>{@link #enterAwaitingRespawn()}：所有参战玩家先视为"死亡"状态等待重生——
 *     DEPLOYING + 进重生队列（按 maps.yml 各阵营重生时间倒计时）+ 创造模式隐身 + 禁破坏/放置；
 *     等待部署的玩家与观战者一并传送至<b>旁观者出生点</b>等待；</li>
 *     <li>{@link #startRespawnTickTask()}：每 tick 递减队列、播报死亡倒计时、就绪即
 *     {@link #autoDeploy(Player, PlayerStatus)}（无需点击物品），失败则留待下一 tick 重试且日志去重；</li>
 *     <li>{@link #restoreCombatant(Player, PlayerStatus)} / {@link #restoreAwaitingRespawn(Player, PlayerStatus)}：
 *     断线重连按保留状态恢复；</li>
 *     <li>{@link #makeSpectator(Player)}：新来者/状态过期者转观战；</li>
 *     <li>{@link AwaitingGuardListener}：等待期禁破坏/放置/攻击（创造模式下玩家仍可破坏攻击）。</li>
 * </ul>
 * 共享状态集中在 {@link MatchSessionState}（任务句柄、监听器句柄、部署失败去重、死亡倒计时播报去重）。
 * 调用方：{@code PlayingPhase}（门面生命周期）、{@link DeathHandler}（死亡转入等待重生）。
 */
public final class DeploymentController {

    private static final DeploymentController INSTANCE = new DeploymentController();

    private DeploymentController() {}

    public static DeploymentController getInstance() {
        return INSTANCE;
    }

    // ==================== 入场：全员等待重生 ====================

    /**
     * 入场处理：所有参战玩家先视为"死亡"状态等待重生——DEPLOYING + 进重生队列（按阵营倒计时）
     * + 创造模式隐身（等待期预留给未来战术道具选择）+ 由守卫禁止破坏/放置。
     * 等待部署的玩家与观战者一并传送至<b>旁观者出生点</b>等待（不暴露在出生区），
     * 倒计时结束由 tick 驱动自动部署到本方出生区（见 {@link #autoDeployReadyPlayers()}）。
     */
    public void enterAwaitingRespawn(){
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

            ShdfTeam team = status.getTeam();
            if(team != null && team.isCombatant()){
                //进入等待重生状态：创造隐身 + 清空（保留战斗菜单）+ DEPLOYING + 进重生队列
                setAwaitingLook(player);
                //DEPLOYING + 进入重生队列（按地图配置的本方重生时间倒计时）
                status.setState(PlayerState.DEPLOYING);
                SpawnManager.getInstance().addPlayer(player.getUniqueId(), team);
            }
            else{
                //观战者：清空背包并保持观战模式
                player.getInventory().clear();
                player.setGameMode(GameMode.SPECTATOR);
            }
            //等待部署的玩家与观战者一并传送至旁观者出生点
            if(spectatorSpawn != null && world != null){
                player.teleport(spectatorSpawn.toLocation(world));
            }
        }
    }

    /** 设定"等待重生"表现：创造模式 + 无粒子永久隐身效果 + 不可碰撞 + 清空背包（保留战斗菜单） */
    public void setAwaitingLook(Player player){
        //清空背包但保留 slot 8 战斗菜单物品（战斗菜单在等待重生/死亡等状态下不被清除）
        PlayingItemFactory.getInstance().clearInventoryKeepBattleMenu(player);
        PlayingItemFactory.getInstance().giveBattleMenuItem(player);
        player.setGameMode(GameMode.CREATIVE);
        //不使用实体隐身标志位：改挂无粒子永久隐身效果（部署/转观战时移除该效果即恢复可见）
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,
                Integer.MAX_VALUE, 0, false, false, false));
        player.setCollidable(false);
    }

    /** 是否处于"等待重生"状态（用于守卫拦截） */
    public boolean isAwaitingRespawn(Player player){
        PlayerStatus status = TeamManager.getInstance().getPlayerStatus(player.getUniqueId());
        return status != null && status.getState() == PlayerState.DEPLOYING;
    }

    // ==================== 重生倒计时驱动与自动部署 ====================

    /** 启动每 tick 的重生驱动：递减重生队列，倒计时结束的玩家自动部署进场 */
    public void startRespawnTickTask(){
        MatchSessionState state = MatchSessionState.getInstance();
        ScheduledTask task = GameContext.getInstance().getPlugin().getServer().getGlobalRegionScheduler()
                .runAtFixedRate(GameContext.getInstance().getPlugin(),
                        scheduledTask -> {
                            if(MatchSessionState.getInstance().isMatchEnded()) return;
                            SpawnManager.getInstance().update();
                            sendDeathCountdownMessages();
                            autoDeployReadyPlayers();
                        },
                        1L, 1L);
        state.setRespawnTickTask(task);
    }

    public void stopRespawnTickTask(){
        ScheduledTask respawnTickTask = MatchSessionState.getInstance().getRespawnTickTask();
        if(respawnTickTask != null){
            respawnTickTask.cancel();
            MatchSessionState.getInstance().setRespawnTickTask(null);
        }
    }

    /** 检测重生倒计时已结束的在线参战玩家，直接自动部署（无需点击物品） */
    public void autoDeployReadyPlayers(){
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
    public void autoDeploy(Player player, PlayerStatus status){
        UUID uuid = player.getUniqueId();
        MatchSessionState state = MatchSessionState.getInstance();
        boolean success = SpawnManager.getInstance().deployPlayer(uuid, status.getSelectedRoleId());
        if(!success){
            //部署失败（角色应用失败/世界缺失等）：留待下一 tick 重试，失败日志只记一次
            if(state.addDeployFailureLogged(uuid)){
                GameContext.getInstance().getPlugin().getLogger().warning(
                        "[PlayingPhase] 玩家 " + player.getName() + " 自动部署失败, 将每 tick 重试!");
            }
            return;
        }

        state.removeDeployFailureLogged(uuid);
        state.removeDeathCountdown(uuid);
        //移除无粒子隐身效果即恢复可见（死亡事件被取消，另需手动复位生命值；等待期物品栏本就为空）
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        player.setCollidable(true);
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        PlayingItemFactory.getInstance().giveBombInteractionItem(player, status.getTeam());
        PlayingItemFactory.getInstance().giveBattleMenuItem(player);
        MessageUtil.sendMessageWithPrefix(player, Component.text("已部署进场, 开始行动!", NamedTextColor.GREEN));
        SoundUtil.playNoticeSuccessCombinedSound(player);
    }

    // ==================== 死亡后重生倒计时播报 ====================

    /** 对"死后等待重生"的玩家每秒播报一次重新部署倒计时（仅死亡玩家，开局等待不播报） */
    public void sendDeathCountdownMessages(){
        MatchSessionState state = MatchSessionState.getInstance();
        for(Player player : Bukkit.getOnlinePlayers()){
            UUID uuid = player.getUniqueId();
            if(!state.hasDeathCountdown(uuid)) continue;

            PlayerStatus status = TeamManager.getInstance().getPlayerStatus(uuid);
            if(status == null || status.getState() != PlayerState.DEPLOYING || !status.getTeam().isCombatant()){
                state.removeDeathCountdown(uuid);
                continue;
            }
            int remainingTicks = SpawnManager.getInstance().getRemainingRespawnTime(uuid);
            int seconds = (int) Math.ceil(remainingTicks / 20.0);
            if(seconds <= 0) continue;
            if(Integer.valueOf(seconds).equals(state.getDeathCountdownSeconds(uuid))) continue;
            state.putDeathCountdownSeconds(uuid, seconds);
            MessageUtil.sendMessageWithPrefix(player,
                    Component.text("将在 " + seconds + " 秒后重新部署", NamedTextColor.GRAY));
        }
    }

    /** 广播对局开始提示：以双方重生时间中较长者为"对局开始"倒计时（秒） */
    public void broadcastMatchStart(){
        MapConfig mapConfig = ConfigManager.getInstance().getSelectedMapConfig();
        if(mapConfig == null) return;
        int maxTicks = Math.max(mapConfig.getAttackerRespawnTime(), mapConfig.getDefenderRespawnTime());
        int seconds = (int) Math.ceil(maxTicks / 20.0);
        MessageUtil.sendPrefixedMessageToAllPlayers(
                Component.text("对局将在 " + seconds + " 秒后开始!", NamedTextColor.GOLD, TextDecoration.BOLD));
    }

    // ==================== 等待重生行为守卫 ====================

    /** 等待重生期间禁止破坏/放置方块、禁止攻击（创造模式下玩家仍可破坏/攻击） */
    private static class AwaitingGuardListener implements Listener {

        @EventHandler(ignoreCancelled = true)
        public void onBlockBreak(BlockBreakEvent event){
            if(DeploymentController.getInstance().isAwaitingRespawn(event.getPlayer())){
                event.setCancelled(true);
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onBlockPlace(BlockPlaceEvent event){
            if(DeploymentController.getInstance().isAwaitingRespawn(event.getPlayer())){
                event.setCancelled(true);
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onEntityDamageByEntity(EntityDamageByEntityEvent event){
            if(event.getDamager() instanceof Player damager && DeploymentController.getInstance().isAwaitingRespawn(damager)){
                event.setCancelled(true);
            }
        }
    }

    public void registerGuard(){
        MatchSessionState state = MatchSessionState.getInstance();
        Listener guardListener = new AwaitingGuardListener();
        Bukkit.getPluginManager().registerEvents(guardListener, GameContext.getInstance().getPlugin());
        state.setGuardListener(guardListener);
    }

    public void unregisterGuard(){
        MatchSessionState state = MatchSessionState.getInstance();
        Listener guardListener = state.getGuardListener();
        if(guardListener != null){
            for(HandlerList handlerList : HandlerList.getHandlerLists()){
                handlerList.unregister(guardListener);
            }
            state.setGuardListener(null);
        }
    }

    // ==================== 重连恢复 / 观战转换 ====================

    /** 恢复战斗身份：传送至当前据点本方出生区，重新应用角色并恢复可见 */
    public void restoreCombatant(Player player, PlayerStatus status){
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
        PlayingItemFactory.getInstance().giveBombInteractionItem(player, status.getTeam());
        PlayingItemFactory.getInstance().giveBattleMenuItem(player);

        MessageUtil.sendMessageWithPrefix(player, Component.text("欢迎回来, 你仍在对局中", NamedTextColor.GREEN));
    }

    /** 恢复"等待重生"身份：维持 DEPLOYING 表现并回旁观者出生点等待；若重生倒计时已结束则立即自动部署 */
    public void restoreAwaitingRespawn(Player player, PlayerStatus status){
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
    public void applyRole(Player player, PlayerStatus status){
        String roleId = status.getSelectedRoleId();
        if(roleId == null || roleId.isEmpty()) return;
        if(!RoleBridge.getInstance().setPlayerRole(player.getUniqueId(), roleId)){
            GameContext.getInstance().getPlugin().getLogger().warning(
                    "[PlayingPhase] 玩家 " + player.getName() + " 的角色应用失败: " + roleId);
        }
    }

    /** 转为观战者：传送至地图观战出生点 */
    public void makeSpectator(Player player){
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
        MatchSessionState state = MatchSessionState.getInstance();
        //清理重生队列中的残留记录后，释放旧角色占用并按观战者重新注册
        SpawnManager.getInstance().removePlayer(uuid);
        state.removeDeployFailureLogged(uuid);
        state.removeDeathCountdown(uuid);
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
}
