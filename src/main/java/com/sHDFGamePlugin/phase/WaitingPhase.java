package com.sHDFGamePlugin.phase;

import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.core.GameState;
import com.sHDFGamePlugin.core.GameStateMachine;
import com.sHDFGamePlugin.domain.spawn.SpawnManager;
import com.sHDFGamePlugin.domain.team.PlayerStatus;
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
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class WaitingPhase implements GamePhase {

    private static final WaitingPhase INSTANCE = new WaitingPhase();

    //GameItem的id
    private final String teamSelectorId = "gameItem_waitingPhase_teamSelector";
    private final String readyToggleId = "gameItem_waitingPhase_readyToggle";
    private final String mapVoteId = "gameItem_waitingPhase_mapVote";

    private final String teamButtonAttackerId = "gameItem_waitingPhase_teamButtonAttacker";
    private final String teamButtonDefenderId = "gameItem_waitingPhase_teamButtonDefender";
    private final String teamButtonSpectatorId = "gameItem_waitingPhase_teamButtonSpectator";
    private final String teamButtonUnknownId = "gameItem_waitingPhase_teamButtonUnknown";
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
        registerGameItems();

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

    private void registerGameItems(){
        //快捷栏物品
        GameItemRegistry.createAndRegister(
                teamSelectorId,
                builder -> {
                    builder.canDrop(false).canMove(false)
                            .rightClickHandler(event -> {
                                GameEventBus.publish(new RightClickGameItemEvent(event.getPlayer(), teamSelectorId));
                            })
                            .build();
                }
        );
        GameItemRegistry.createAndRegister(
                readyToggleId,
                builder -> {
                    builder.canDrop(false).canMove(false)
                            .rightClickHandler(event -> {
                                GameEventBus.publish(new RightClickGameItemEvent(event.getPlayer(), readyToggleId));
                            })
                            .build();
                }
        );
        GameItemRegistry.createAndRegister(
                mapVoteId,
                builder -> {
                    builder.canDrop(false).canMove(false)
                            .rightClickHandler(event -> {
                                GameEventBus.publish(new RightClickGameItemEvent(event.getPlayer(), mapVoteId));
                            })
                            .build();
                }
        );
        //选队菜单物品
        GameItemRegistry.createAndRegister(
                teamButtonAttackerId,
                builder -> {
                    builder.canDrop(false).canMove(false)
                            .inventoryClickHandler(event -> {
                                GameEventBus.publish(new InventoryClickGameItemEvent((Player)event.getWhoClicked(), teamButtonAttackerId));
                            })
                            .build();
                }
        );
        GameItemRegistry.createAndRegister(
                teamButtonDefenderId,
                builder -> {
                    builder.canDrop(false).canMove(false)
                            .inventoryClickHandler(event -> {
                                GameEventBus.publish(new InventoryClickGameItemEvent((Player)event.getWhoClicked(), teamButtonDefenderId));
                            })
                            .build();
                }
        );
        GameItemRegistry.createAndRegister(
                teamButtonSpectatorId,
                builder -> {
                    builder.canDrop(false).canMove(false)
                            .inventoryClickHandler(event -> {
                                GameEventBus.publish(new InventoryClickGameItemEvent((Player)event.getWhoClicked(), teamButtonSpectatorId));
                            })
                            .build();
                }
        );
        GameItemRegistry.createAndRegister(
                teamButtonUnknownId,
                builder -> {
                    builder.canDrop(false).canMove(false)
                            .inventoryClickHandler(event -> {
                                GameEventBus.publish(new InventoryClickGameItemEvent((Player)event.getWhoClicked(), teamButtonUnknownId));
                            })
                            .build();
                }
        );
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
        TeamManager teamManager = TeamManager.getInstance();
        UUID playerId = player.getUniqueId();
        Team team = teamManager.getTeam(playerId);
        if(team == null) return;
        boolean isCombatant = team.isCombatant();

        Inventory inv = player.getInventory();

        for(int i = 0; i < 3; i++){
            inv.setItem(i, null);
        }

        ItemStack teamSelectorItem = new ItemStack(Material.NETHER_STAR);
        ItemMeta teamSelectorItemMeta = teamSelectorItem.getItemMeta();
        teamSelectorItemMeta.displayName(Component.text("选择阵营", NamedTextColor.GOLD, TextDecoration.BOLD));
        teamSelectorItemMeta = GameItem.applyIdOnItemMeta(teamSelectorId, teamSelectorItemMeta);
        teamSelectorItem.setItemMeta(teamSelectorItemMeta);
        inv.setItem(0, teamSelectorItem);

        //准备物品发给非观战者
        if(isCombatant && ConfigManager.getInstance().isRequireReady()){
            ItemStack readyItem;
            if(teamManager.isReady(playerId)){
                readyItem = new ItemStack(Material.DIAMOND_SWORD);
                ItemMeta meta = readyItem.getItemMeta();
                meta.displayName(Component.text("已经准备", NamedTextColor.GREEN, TextDecoration.BOLD));
                readyItem.setItemMeta(meta);
            }
            else{
                readyItem = new ItemStack(Material.WOODEN_SWORD);
                ItemMeta meta = readyItem.getItemMeta();
                meta.displayName(Component.text("尚未准备", NamedTextColor.RED, TextDecoration.BOLD));
                readyItem.setItemMeta(meta);
            }
            ItemMeta meta = readyItem.getItemMeta();
            meta.lore(List.of(
                    Component.text("只有所有除了随机和观战阵营的玩家准备后, 游戏才会开始", NamedTextColor.GRAY)
            ));
            readyItem = GameItem.applyIdOnItemStack(readyToggleId, readyItem);
            inv.setItem(1, readyItem);
        }

        ItemStack mapVoteItem = new ItemStack(Material.PAPER);
        ItemMeta mapvoteItemMeta = mapVoteItem.getItemMeta();
        mapvoteItemMeta.displayName(Component.text("票选地图", NamedTextColor.BLUE, TextDecoration.BOLD));
        mapvoteItemMeta.lore(List.of(
                Component.text("尚未实现", NamedTextColor.GRAY)
        ));
        mapvoteItemMeta = GameItem.applyIdOnItemMeta(mapVoteId, mapvoteItemMeta);
        mapVoteItem.setItemMeta(mapvoteItemMeta);
        inv.setItem(2, mapVoteItem);

    }

    private void handleRightClickGameItem(RightClickGameItemEvent event){
        String itemId = event.getGameItemId();
        Player player = event.getPlayer();

        switch (itemId){
            case teamSelectorId -> openTeamSelectionGui(player);
            case readyToggleId-> toggleReady(player);
            case mapVoteId -> useMapSelector(player);
            default -> {
                GameContext.getInstance().getPlugin().getLogger().warning("Player " + player.getName() + " try to use a GameItem not belonging to WaitingPhase!");
            }
        }
    }

    private void handleInventoryClickGameItem(InventoryClickGameItemEvent event){
        String itemId = event.getGameItemId();
        Player player = event.getPlayer();

        switch (itemId){
            case teamButtonAttackerId -> handleTeamSelect(player, Team.ATTACKER);
            case teamButtonDefenderId -> handleTeamSelect(player, Team.DEFENDER);
            case teamButtonSpectatorId -> handleTeamSelect(player, Team.SPECTATOR);
            case teamButtonUnknownId -> handleTeamSelect(player, Team.UNKNOWN);
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
        ItemStack buttonAttacker = new ItemStack(Material.NETHERITE_SWORD);
        {
            ItemMeta meta = buttonAttacker.getItemMeta();
            meta.displayName(Component.text("进攻方-SHADOW",  NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("这个队伍有 " + TeamManager.getInstance().getPlayerCount(Team.ATTACKER) + " 名玩家:", NamedTextColor.GRAY));
            for(UUID pid : TeamManager.getInstance().getAllPlayersUuidsInTeam(Team.ATTACKER)){
                Player p = Bukkit.getPlayer(pid);
                if(p == null){
                    lore.add(Component.text("#无法通过uuid获取玩家", NamedTextColor.RED));
                }
                else{
                    lore.add(Component.text(p.getName(), NamedTextColor.GRAY));
                }
            }
            meta.lore(lore);
            meta = GameItem.applyIdOnItemMeta(teamButtonAttackerId, meta);
            buttonAttacker.setItemMeta(meta);
        }

        ItemStack buttonDefender = new ItemStack(Material.BEDROCK);
        {
            ItemMeta meta = buttonDefender.getItemMeta();
            meta.displayName(Component.text("防守方-SHADOW",  NamedTextColor.YELLOW, TextDecoration.BOLD));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("这个队伍有 " + TeamManager.getInstance().getPlayerCount(Team.ATTACKER) + " 名玩家:", NamedTextColor.GRAY));
            for(UUID pid : TeamManager.getInstance().getAllPlayersUuidsInTeam(Team.DEFENDER)){
                Player p = Bukkit.getPlayer(pid);
                if(p == null){
                    lore.add(Component.text("#无法通过uuid获取玩家", NamedTextColor.RED));
                }
                else{
                    lore.add(Component.text(p.getName(), NamedTextColor.GRAY));
                }
            }
            meta.lore(lore);
            meta = GameItem.applyIdOnItemMeta(teamButtonDefenderId, meta);
            buttonDefender.setItemMeta(meta);
        }

        ItemStack buttonSpectator = new ItemStack(Material.PLAYER_HEAD);
        {
            ItemMeta meta = buttonSpectator.getItemMeta();
            meta.displayName(Component.text("观众",  NamedTextColor.BLUE, TextDecoration.BOLD));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("对局开始后, 你将以观战者加入!", NamedTextColor.YELLOW, TextDecoration.BOLD));
            lore.add(Component.text("这个队伍有 " + TeamManager.getInstance().getPlayerCount(Team.ATTACKER) + " 名玩家:", NamedTextColor.GRAY));
            for(UUID pid : TeamManager.getInstance().getAllPlayersUuidsInTeam(Team.SPECTATOR)){
                Player p = Bukkit.getPlayer(pid);
                if(p == null){
                    lore.add(Component.text("#无法通过uuid获取玩家", NamedTextColor.RED));
                }
                else{
                    lore.add(Component.text(p.getName(), NamedTextColor.GRAY));
                }
            }
            meta.lore(lore);
            meta = GameItem.applyIdOnItemMeta(teamButtonSpectatorId, meta);
            buttonSpectator.setItemMeta(meta);
        }

        ItemStack buttonUnknown = new ItemStack(Material.STRUCTURE_VOID);
        {
            ItemMeta meta = buttonUnknown.getItemMeta();
            meta.displayName(Component.text("随机阵营",  NamedTextColor.GREEN, TextDecoration.BOLD));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("你将会在游戏开始时被随机分配到进攻方或者防守方!", NamedTextColor.YELLOW, TextDecoration.BOLD));
            lore.add(Component.text("这个队伍有 " + TeamManager.getInstance().getPlayerCount(Team.ATTACKER) + " 名玩家:", NamedTextColor.GRAY));
            for(UUID pid : TeamManager.getInstance().getAllPlayersUuidsInTeam(Team.ATTACKER)){
                Player p = Bukkit.getPlayer(pid);
                if(p == null){
                    lore.add(Component.text("#无法通过uuid获取玩家", NamedTextColor.RED));
                }
                else{
                    lore.add(Component.text(p.getName(), NamedTextColor.GRAY));
                }
            }
            meta.lore(lore);
            meta = GameItem.applyIdOnItemMeta(teamButtonUnknownId, meta);
            buttonUnknown.setItemMeta(meta);
        }
        ChestGui gui = ChestGui.Builder.create()
                .title(Component.text("选择阵营", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD))
                .rows(1)
                .setSlot(0, buttonAttacker)
                .setSlot(1, buttonDefender)
                .setSlot(2, buttonSpectator)
                .setSlot(3, buttonUnknown)
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

        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);

        updateWaitingGameItems(player);

        checkStartConditions();

    }
    private void useMapSelector(Player player){
    }

}
