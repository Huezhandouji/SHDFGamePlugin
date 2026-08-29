package com.sHDFGamePlugin.phase;

import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.domain.team.PlayerState;
import com.sHDFGamePlugin.domain.team.ShdfTeam;
import com.sHDFGamePlugin.domain.team.TeamManager;
import com.sHDFGamePlugin.infrastructure.GameEventBus;
import com.sHDFGamePlugin.infrastructure.RoleBridge;
import com.sHDFGamePlugin.infrastructure.config.ConfigManager;
import com.sHDFGamePlugin.infrastructure.event.InventoryClickGameItemEvent;
import com.sHDFGamePlugin.infrastructure.event.RightClickGameItemEvent;
import com.sHDFGamePlugin.infrastructure.event.ShdfPlayerJoinEvent;
import com.sHDFGamePlugin.infrastructure.event.ShdfPlayerQuitEvent;
import com.sHDFGamePlugin.infrastructure.item.GameItem;
import com.sHDFGamePlugin.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.*;

public class RoleSelectingPhase implements GamePhase {

    private RoleSelectingPhase(){}

    private static final RoleSelectingPhase INSTANCE = new RoleSelectingPhase();
    public static RoleSelectingPhase getInstance(){
        return INSTANCE;
    }

    //双方可用角色
    private List<String> availableAttackerRoleIds;
    private List<String> availableDefenderRoleIds;

    //GameItem Id
    //物品栏
    private final String attackerRoleSelectorId = "gameItem_RoleSelectingPhase_roleSelector_attacker";
    private final String defenderRoleSelectorId = "gameItem_RoleSelectingPhase_roleSelector_defender";
    //箱子菜单
    private final String shadowRoleSelectorGameItemIdPrefix = "gameItem_RoleSelectingPhase_roleSelector_shadow_";
    private final String hunterRoleSelectorGameItemIdPrefix = "gameItem_RoleSelectingPhase_roleSelector_hunter_";
    //-影
    private final Map<String, GameItem> shadowRoleSelectorButtons = new HashMap<>();
    //-猎影人
    private final Map<String, GameItem> hunterRoleSelectorButtons = new HashMap<>();

    //事件订阅
    private GameEventBus.Subscription joinSubscription;
    private GameEventBus.Subscription quitSubscription;
    private GameEventBus.Subscription inventoryClickSubscription;
    private GameEventBus.Subscription rightClickGameItemSubscription;


    @Override
    public void onEnter() {
        subscribeEvents();
    }

    @Override
    public void onExit() {
        unsubscribeEvents();
    }

    private void loadAvailableRoleIds(){
        ConfigManager config =  ConfigManager.getInstance();
        availableAttackerRoleIds = config.getSelectedMapConfig().getAttackerRoles();
        availableDefenderRoleIds = config.getSelectedMapConfig().getAttackerRoles();
    }

    private void createRoleSelectorButtons() {
        RoleBridge roleBridge = RoleBridge.getInstance();
        //shadow
        for(String roleId : availableAttackerRoleIds){
            if(roleBridge.isValidRoleId(roleId)) continue;

            String gameItemId = shadowRoleSelectorGameItemIdPrefix + roleId;
            GameItem.Builder builder = new GameItem.Builder(gameItemId)
                    .canDrop(false).canMove(false)
                    .inventoryClickHandler(
                            event -> {
                                GameEventBus.publish(
                                        new InventoryClickGameItemEvent((Player) event.getWhoClicked(), gameItemId)
                                );
                            }
                    );

        }
    }

    private void subscribeEvents(){
        joinSubscription = GameEventBus.subscribe(ShdfPlayerJoinEvent.class, this::handlePlayerJoin);
        quitSubscription = GameEventBus.subscribe(ShdfPlayerQuitEvent.class, this::handlePlayerQuit);
        inventoryClickSubscription = GameEventBus.subscribe(InventoryClickGameItemEvent.class, this::handleInventoryClick);
        rightClickGameItemSubscription =  GameEventBus.subscribe(RightClickGameItemEvent.class, this::handleRightClickGameItem);
    }
    private void unsubscribeEvents(){
        joinSubscription.unsubscribe();
        joinSubscription = null;
        quitSubscription.unsubscribe();
        quitSubscription = null;
        inventoryClickSubscription.unsubscribe();
        inventoryClickSubscription = null;
        rightClickGameItemSubscription.unsubscribe();
        rightClickGameItemSubscription = null;
    }

    private void handlePlayerJoin(ShdfPlayerJoinEvent event){
        Player player = event.getPlayer();
        ConfigManager configManager = ConfigManager.getInstance();

        World world = Bukkit.getWorld(configManager.getSelectedMapConfig().getWorld());
        if(world == null){
            GameContext.getInstance().getPlugin().getLogger().warning("World could not be found when a player joined in RoleSelectingPhase! Player Kicked!");
            player.kick(Component.text("SHDF插件出现意外错误", NamedTextColor.RED, TextDecoration.BOLD));
            return;
        }
        Location spawnLocation = configManager.getSpectatorSpawnpoint().toLocation(world);
        player.teleport(spawnLocation);

        UUID pid = player.getUniqueId();
        TeamManager teamManager = TeamManager.getInstance();

        teamManager.removePlayer(pid);
        teamManager.addPlayer(pid, ShdfTeam.SPECTATOR, PlayerState.ROLE_SELECTING);

        MessageUtil.sendMessageWithPostfix(player, Component.text("你在对局中加入, 已经被自动设置为旁观者, 请等待对局结束"));
        player.getInventory().clear();
        player.setGameMode(GameMode.SPECTATOR);
    }

    private void handlePlayerQuit(ShdfPlayerQuitEvent event){
        
    }

    private void handleInventoryClick(InventoryClickGameItemEvent event){

    }

    private void handleRightClickGameItem(RightClickGameItemEvent event){

    }
}
