package com.sHDFGamePlugin.phase;

import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.core.GameState;
import com.sHDFGamePlugin.core.GameStateMachine;
import com.sHDFGamePlugin.domain.sector.Sector;
import com.sHDFGamePlugin.domain.sector.SectorManager;
import com.sHDFGamePlugin.domain.team.PlayerState;
import com.sHDFGamePlugin.domain.team.PlayerStatus;
import com.sHDFGamePlugin.domain.team.ShdfTeam;
import com.sHDFGamePlugin.domain.team.TeamManager;
import com.sHDFGamePlugin.infrastructure.GameEventBus;
import com.sHDFGamePlugin.infrastructure.RoleBridge;
import com.sHDFGamePlugin.infrastructure.config.ConfigManager;
import com.sHDFGamePlugin.infrastructure.event.ShdfPlayerJoinEvent;
import com.sHDFGamePlugin.infrastructure.event.ShdfPlayerQuitEvent;
import com.sHDFGamePlugin.infrastructure.regionExpression.CubeRegion;
import com.sHDFGamePlugin.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * 对局阶段：炸弹爆破推进（核心玩法待实现）。
 * <p>
 * 目前已实现玩家的加入/退出处理：
 * - 退出：保留 PlayerStatus（断线保护），超时仍未重连才移除；
 * - 加入：保留的 IN_BATTLE 战斗身份恢复，否则转为观战者并传送观战出生点。
 */
public class PlayingPhase implements GamePhase {

    private static final PlayingPhase INSTANCE = new PlayingPhase();

    //断线保护时长（tick）：退出后保留 PlayerStatus，超时仍未重连才移除
    private static final long DISCONNECT_PROTECTION_TICKS = 1200;

    private PlayingPhase() {}

    public static PlayingPhase getInstance() {
        return INSTANCE;
    }

    //事件订阅
    private GameEventBus.Subscription joinSubscription;
    private GameEventBus.Subscription quitSubscription;

    @Override
    public void onEnter() {
        GameContext.getInstance().getPlugin().getLogger().info("游戏进入 PLAYING 状态");
        subscribeEvents();
    }

    @Override
    public void onExit() {
        unsubscribeEvents();
    }

    // ==================== 玩家加入/退出 ====================

    private void handlePlayerJoin(ShdfPlayerJoinEvent event){
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        TeamManager teamManager = TeamManager.getInstance();

        PlayerStatus status = teamManager.getPlayerStatus(uuid);
        //断线重连：保留的 PlayerStatus 且处于战斗状态 → 恢复战斗身份
        if(status != null && status.getState() == PlayerState.IN_BATTLE && status.getTeam().isCombatant()){
            restoreCombatant(player, status);
            return;
        }

        //新来者 / 状态过期 → 转为观战者并传送观战出生点
        makeSpectator(player);
    }

    /** 恢复战斗身份：传送至当前据点本方复活点 */
    private void restoreCombatant(Player player, PlayerStatus status){
        player.setGameMode(GameMode.ADVENTURE);

        ConfigManager config = ConfigManager.getInstance();
        Sector sector = SectorManager.getInstance().getCurrentSector();
        World world = sector != null && config.getSelectedMapConfig() != null
                ? Bukkit.getWorld(config.getSelectedMapConfig().getWorld())
                : null;
        if(sector != null && world != null){
            CubeRegion spawnRegion = status.getTeam() == ShdfTeam.ATTACKER
                    ? sector.getAttackerSpawnRegion()
                    : sector.getDefenderSpawnRegion();
            player.teleport(spawnRegion.randomPoint().toLocation(world));
        }

        MessageUtil.sendMessageWithPrefix(player, Component.text("欢迎回来, 你仍在对局中", NamedTextColor.GREEN));
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
        //释放旧角色占用后按观战者重新注册
        RoleBridge.getInstance().clearPlayerRole(uuid);
        teamManager.removePlayer(uuid);
        teamManager.addPlayer(uuid, ShdfTeam.SPECTATOR, PlayerState.IN_BATTLE);

        MessageUtil.sendMessageWithPrefix(player, Component.text("你已在对局中成为旁观者, 请等待对局结束"));
        player.getInventory().clear();
        player.setGameMode(GameMode.SPECTATOR);
    }

    private void handlePlayerQuit(ShdfPlayerQuitEvent event){
        UUID uuid = event.getPlayer().getUniqueId();

        if(Bukkit.getOnlinePlayers().isEmpty()){
            GameStateMachine.getInstance().transitionTo(GameState.IDLE);
            return;
        }

        //断线保护：保留 PlayerStatus 与角色占用，超时仍未重连才移除
        scheduleDisconnectRemoval(uuid);
    }

    /** 断线保护：一段时间后玩家仍未上线，则移除记录并释放角色 */
    private void scheduleDisconnectRemoval(UUID uuid){
        GameContext.getInstance().getPlugin().getServer().getGlobalRegionScheduler().runDelayed(
                GameContext.getInstance().getPlugin(),
                task -> {
                    if(Bukkit.getPlayer(uuid) == null){
                        TeamManager.getInstance().removePlayer(uuid);
                        RoleBridge.getInstance().clearPlayerRole(uuid);
                    }
                },
                DISCONNECT_PROTECTION_TICKS
        );
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
