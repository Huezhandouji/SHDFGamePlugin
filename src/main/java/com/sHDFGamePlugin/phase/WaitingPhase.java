package com.sHDFGamePlugin.phase;

import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.core.GameState;
import com.sHDFGamePlugin.core.GameStateMachine;
import com.sHDFGamePlugin.domain.spawn.SpawnManager;
import com.sHDFGamePlugin.domain.team.Team;
import com.sHDFGamePlugin.domain.team.TeamManager;
import com.sHDFGamePlugin.infrastructure.GameEventBus;
import com.sHDFGamePlugin.infrastructure.RoleBridge;
import com.sHDFGamePlugin.infrastructure.config.ConfigManager;
import com.sHDFGamePlugin.infrastructure.event.InventoryClickGameItemEvent;
import com.sHDFGamePlugin.infrastructure.event.RightClickGameItemEvent;
import com.sHDFGamePlugin.infrastructure.event.ShdfPlayerJoinEvent;
import com.sHDFGamePlugin.infrastructure.event.ShdfPlayerQuitEvent;
import com.sHDFGamePlugin.infrastructure.gui.ChestGui;
import com.sHDFGamePlugin.infrastructure.item.GameItem;
import com.sHDFGamePlugin.infrastructure.item.GameItemRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.UUID;

public class WaitingPhase implements GamePhase {

    private static final WaitingPhase INSTANCE = new WaitingPhase();


    //物品
    private GameItem teamSelectorGameItem;
    private GameItem readyTrueGameItem;
    private GameItem readyFalseGameItem;
    private GameItem voteGameItem;

    //物品id
    private final String TEAM_SELECTOR_ID = GameItemRegistry.ItemId.WAITING_PHASE_TEAM_SELECTOR;
    private final String READY_TOGGLE_FALSE_ID = GameItemRegistry.ItemId.WAITING_PHASE_READY_TOGGLE_FALSE;
    private final String READY_TOGGLE_TRUE_ID = GameItemRegistry.ItemId.WAITING_PHASE_READY_TOGGLE_TRUE;
    private final String MAP_VOTE_ID = GameItemRegistry.ItemId.WAITING_PHASE_MAP_VOTE;

    private final String TEAM_BUTTON_ATTACKER_ID = GameItemRegistry.ItemId.WAITING_PHASE_TEAM_BUTTON_ATTACKER;
    private final String TEAM_BUTTON_DEFENDER_ID = GameItemRegistry.ItemId.WAITING_PHASE_TEAM_BUTTON_DEFENDER;
    private final String TEAM_BUTTON_SPECTATOR_ID = GameItemRegistry.ItemId.WAITING_PHASE_TEAM_BUTTON_SPECTATOR;
    private final String TEAM_BUTTON_UNKNOWN_ID = GameItemRegistry.ItemId.WAITING_PHASE_TEAM_BUTTON_UNKNOWN;
    //事件订阅
    private GameEventBus.Subscription joinSubscription;
    private GameEventBus.Subscription rightClickSubscription;
    private GameEventBus.Subscription inventoryClickSubscription;
    private GameEventBus.Subscription quitSubscription;



    private WaitingPhase(){
    }

    public static WaitingPhase getInstance(){
        return INSTANCE;
    }



    @Override
    public void onEnter() {
        createWaitingGameItems();

        joinSubscription = GameEventBus.subscribe(ShdfPlayerJoinEvent.class, event -> {
            handlePlayerJoin(event.getPlayer());
        });
        rightClickSubscription = GameEventBus.subscribe(RightClickGameItemEvent.class, this::handleRightClickGameItem);
        inventoryClickSubscription = GameEventBus.subscribe(InventoryClickGameItemEvent.class, this::handleInventoryClickGameItem);
        quitSubscription = GameEventBus.subscribe(ShdfPlayerQuitEvent.class, event -> handlePlayerQuit(event.getPlayer()));

        for(Player player : Bukkit.getOnlinePlayers()){
            registerPlayer(player);
        }

        checkStartConditions();
    }

    @Override
    public void onExit() {
        if(joinSubscription != null){
            joinSubscription.unsubscribe();
            joinSubscription = null;
        }
        if(rightClickSubscription != null){
            rightClickSubscription.unsubscribe();
            rightClickSubscription = null;
        }
        if(inventoryClickSubscription != null){
            inventoryClickSubscription.unsubscribe();
            inventoryClickSubscription = null;
        }
        if(quitSubscription != null){
            quitSubscription.unsubscribe();
            quitSubscription = null;
        }
    }

    private void createWaitingGameItems(){
        teamSelectorGameItem = GameItemRegistry.getGameItem(TEAM_SELECTOR_ID);
        readyFalseGameItem = GameItemRegistry.getGameItem(READY_TOGGLE_FALSE_ID);
        readyTrueGameItem = GameItemRegistry.getGameItem(READY_TOGGLE_TRUE_ID);
        voteGameItem = GameItemRegistry.getGameItem(MAP_VOTE_ID);
    }

    private void handlePlayerJoin(Player player){
        registerPlayer(player);
        checkStartConditions();
    }

    private void handlePlayerQuit(Player player){
        TeamManager.getInstance().removePlayer(player.getUniqueId());
        SpawnManager.getInstance().removePlayer(player.getUniqueId());
        RoleBridge.getInstance().clearPlayerRole(player.getUniqueId());
        checkStartConditions();
    }

    private void registerPlayer(Player player){
        TeamManager teamManager = TeamManager.getInstance();
        if(teamManager.getTeam(player.getUniqueId()) == null){
            ConfigManager config =  ConfigManager.getInstance();
            Team initialTeam = config.isDefaultSpectatorOrUnknown() ? Team.SPECTATOR : Team.UNKNOWN;
            teamManager.addPlayer(player.getUniqueId(), initialTeam);
        }
        updateWaitingGameItems(player);
    }

    private void checkStartConditions(){
        ConfigManager config = ConfigManager.getInstance();
        TeamManager teamManager = TeamManager.getInstance();

        //双方人数达到最低要求
        if(teamManager.getPlayerCount(Team.ATTACKER) + teamManager.getPlayerCount(Team.UNKNOWN) / 2 < config.getMinPopulationPerSide()) return;
        if(teamManager.getPlayerCount(Team.DEFENDER) + teamManager.getPlayerCount(Team.UNKNOWN) / 2 < config.getMinPopulationPerSide()) return;
        //人数差不超过限制
        if(teamManager.getSideDiff() > config.getMaxSideDiff()) return;
        //如果要求准备
        if(config.isRequireReady()){
            for(UUID uuid : teamManager.getAllPlayersUuidsInTeam(Team.ATTACKER)){
                if(!teamManager.isReady(uuid)) return;
            }
            for(UUID uuid : teamManager.getAllPlayersUuidsInTeam(Team.DEFENDER)){
                if(!teamManager.isReady(uuid)) return;
            }
            for(UUID uuid : teamManager.getAllPlayersUuidsInTeam(Team.UNKNOWN)){
                if(!teamManager.isReady(uuid)) return;
            }
        }
        //至少有一张可用地图
        if(config.getMapNames().isEmpty()) return;

        //切换到下一个阶段
        GameStateMachine.getInstance().transitionTo(GameState.COUNTDOWN);

    }

    private void updateWaitingGameItems(Player player){
        Team team = TeamManager.getInstance().getTeam(player.getUniqueId());
        if(team == null) return;
        boolean isCombatant = team.isCombatant();

        Inventory inv = player.getInventory();

        for(int i = 0; i < 3; i++){
            inv.setItem(i, null);
        }

        if(teamSelectorGameItem != null){
            inv.setItem(0, teamSelectorGameItem.getItemStack().clone());
        }

        //准备物品发给非观战者
        if(isCombatant && ConfigManager.getInstance().isRequireReady() && readyFalseGameItem != null){
            inv.setItem(1, readyFalseGameItem.getItemStack().clone());
        }
        else{
            inv.setItem(1, null);
        }

        if(voteGameItem != null){
            inv.setItem(2, voteGameItem.getItemStack().clone());
        }
    }

    private void handleRightClickGameItem(RightClickGameItemEvent event){
        String itemId = event.getGameItemId();
        Player player = event.getPlayer();

        switch (itemId){
            case TEAM_SELECTOR_ID -> openTeamSelectionGui(player);
            case READY_TOGGLE_FALSE_ID, READY_TOGGLE_TRUE_ID -> toggleReady(player);
            case MAP_VOTE_ID -> useMapSelector(player);
            default -> {
                GameContext.getInstance().getPlugin().getLogger().warning("Player " + player.getName() + " try to use a GameItem not belonging to WaitingPhase!");
            }
        }
    }

    private void handleInventoryClickGameItem(InventoryClickGameItemEvent event){
        String itemId = event.getGameItemId();
        Player player = event.getPlayer();

        switch (itemId){
            case TEAM_BUTTON_ATTACKER_ID -> handleTeamSelect(player, Team.ATTACKER);
            case TEAM_BUTTON_DEFENDER_ID -> handleTeamSelect(player, Team.DEFENDER);
            case TEAM_BUTTON_SPECTATOR_ID -> handleTeamSelect(player, Team.SPECTATOR);
            case TEAM_BUTTON_UNKNOWN_ID -> handleTeamSelect(player, Team.UNKNOWN);
            default -> {
                //忽略其他物品
            }
        }
    }

    private void handleTeamSelect(Player player, Team targetTeam){
        TeamManager teamManager = TeamManager.getInstance();
        UUID uuid = player.getUniqueId();
        Team currentTeam = teamManager.getTeam(uuid);

        //检查人数差是否可接受
        if(targetTeam == Team.ATTACKER || targetTeam == Team.DEFENDER){
            int attackers = teamManager.getPlayerCount(Team.ATTACKER);
            int defenders = teamManager.getPlayerCount(Team.DEFENDER);
            if(currentTeam == Team.ATTACKER) attackers--;
            if(currentTeam == Team.DEFENDER) defenders--;
            if(targetTeam == Team.ATTACKER) attackers++;
            if(targetTeam == Team.DEFENDER) defenders++;

            int diff = Math.abs(attackers - defenders);
            if(diff > ConfigManager.getInstance().getMaxSideDiff()){
                player.sendMessage(Component.text("SHDF>>无法加入该阵营, 因为切换后人数差过大"));
                return;
            }
        }

        teamManager.setTeam(uuid, targetTeam);
        player.closeInventory();

        //切换准备状态
        if(targetTeam.isCombatant()){
            TeamManager.getInstance().setReady(uuid, false);
        }
        else{
            TeamManager.getInstance().setReady(uuid, true);
        }


        player.sendMessage(Component.text("SHDF>>你加入了" + targetTeam.name()));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1);
        updateWaitingGameItems(player);
        checkStartConditions();
    }

    private void openTeamSelectionGui(Player player){
        ChestGui gui = ChestGui.Builder.create()
                .title(Component.text("选择阵营", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD))
                .rows(1)
                .setSlot(0, GameItemRegistry.getGameItem(GameItemRegistry.ItemId.WAITING_PHASE_TEAM_BUTTON_ATTACKER))
                .setSlot(1, GameItemRegistry.getGameItem(GameItemRegistry.ItemId.WAITING_PHASE_TEAM_BUTTON_DEFENDER))
                .setSlot(2, GameItemRegistry.getGameItem(GameItemRegistry.ItemId.WAITING_PHASE_TEAM_BUTTON_UNKNOWN))
                .setSlot(3, GameItemRegistry.getGameItem(GameItemRegistry.ItemId.WAITING_PHASE_TEAM_BUTTON_SPECTATOR))
                .build();
        gui.open(player);
    }

    private void toggleReady(Player player) {

        TeamManager teamManager = TeamManager.getInstance();
        UUID uuid = player.getUniqueId();

        //只有战斗人员才能准备
        Team team = teamManager.getTeam(uuid);
        if (team == null || !team.isCombatant()) {
            player.sendMessage(Component.text("SHDF>>只有战斗人员才能准备").color(NamedTextColor.GRAY));
            return;
        }

        boolean curret = teamManager.isReady(uuid);
        boolean newReady = !curret;
        teamManager.setReady(uuid, newReady);

        player.sendMessage(Component.text(
                curret ? "你已取消准备" : "你已准备就绪",
                curret ? NamedTextColor.RED : NamedTextColor.GREEN
        ));

        Inventory inv = player.getInventory();
        inv.setItem(1, null);
        if(newReady == true && readyTrueGameItem != null){
            inv.setItem(1, readyTrueGameItem.getItemStack().clone());
        }
        else if(newReady == false && readyFalseGameItem != null){
            inv.setItem(1, readyFalseGameItem.getItemStack().clone());
        }

        checkStartConditions();

    }
    private void useMapSelector(Player player){
    }

}
