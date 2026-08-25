package com.sHDFGamePlugin.infrastructure.item;

import com.sHDFGamePlugin.infrastructure.GameEventBus;
import com.sHDFGamePlugin.infrastructure.event.InventoryClickGameItemEvent;
import com.sHDFGamePlugin.infrastructure.event.RightClickGameItemEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.function.Consumer;

public final class GameItemRegistry {

    private GameItemRegistry(){}

    public static void register(GameItem gameItem){
        InteractionManager.getInstance().registerGameItem(gameItem.getId(), gameItem);
    }

    public static void unregister(String id){
        InteractionManager.getInstance().unregisterGameItem(id);
    }

    public static GameItem getGameItem(String id){
        return InteractionManager.getInstance().getGameItemById(id);
    }

    public static GameItem createAndRegister(String id, Material material, Consumer<GameItem.Builder> builderCfg){
        GameItem.Builder builder = new GameItem.Builder(id, material);
        if(builderCfg != null){
            builderCfg.accept(builder);
        }
        GameItem gameItem = builder.build();
        register(gameItem);
        return gameItem;
    }



    public static final class ItemId{
        private ItemId(){}
        public static final String WAITING_PHASE_TEAM_BUTTON_ATTACKER = "GameItem_waitingPhase_teamButton_attacker";
        public static final String WAITING_PHASE_TEAM_BUTTON_DEFENDER = "GameItem_waitingPhase_teamButton_defender";
        public static final String WAITING_PHASE_TEAM_BUTTON_SPECTATOR = "GameItem_waitingPhase_teamButton_spectator";
        public static final String WAITING_PHASE_TEAM_BUTTON_UNKNOWN = "GameItem_waitingPhase_teamButton_unknown";

        public static final String WAITING_PHASE_TEAM_SELECTOR = "GameItem_waitingPhase_teamSelector";
        public static final String WAITING_PHASE_READY_TOGGLE_FALSE = "GameItem_waitingPhase_readyToggle_false";
        public static final String WAITING_PHASE_READY_TOGGLE_TRUE = "GameItem_waitingPhase_readyToggle_true";
        public static final String WAITING_PHASE_MAP_VOTE =  "GameItem_waitingPhase_mapVote";

        public static final String UTIL_CHEST_GUI_SLOT_HOLDER = "GameItem_util_ChestGuiSlotHolder";

    }


    static {
        //chestGui占位物品
        registerChestGuiSlotHolder();
        registerGameItemWaitingPhaseMapVote();
        registerGameItemWaitingPhaseReadyToggle(ItemId.WAITING_PHASE_READY_TOGGLE_FALSE, Material.GRAY_DYE, Component.text("尚未准备").color(NamedTextColor.DARK_GRAY).decorate(TextDecoration.BOLD));
        registerGameItemWaitingPhaseReadyToggle(ItemId.WAITING_PHASE_READY_TOGGLE_TRUE, Material.GREEN_DYE, Component.text("准备就绪").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD));
        registerGameItemWaitingPhaseTeamSelector();
        registerWaitingPhaseTeamButton(ItemId.WAITING_PHASE_TEAM_BUTTON_ATTACKER, Material.NETHERITE_SWORD, Component.text("进攻方-影").color(NamedTextColor.LIGHT_PURPLE).decorate(TextDecoration.BOLD));
        registerWaitingPhaseTeamButton(ItemId.WAITING_PHASE_TEAM_BUTTON_DEFENDER, Material.PLAYER_HEAD, Component.text("防守方-猎影人").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        registerWaitingPhaseTeamButton(ItemId.WAITING_PHASE_TEAM_BUTTON_SPECTATOR, Material.GRAY_WOOL, Component.text("观战者").color(NamedTextColor.GRAY).decorate(TextDecoration.BOLD));
        registerWaitingPhaseTeamButton(ItemId.WAITING_PHASE_TEAM_BUTTON_UNKNOWN, Material.COMPASS, Component.text("随机队伍").color(NamedTextColor.GRAY).decorate(TextDecoration.BOLD));
    }

    //  GameItem_util_ChestGuiSlotHolder
    private static void registerChestGuiSlotHolder(){
        GameItem gameItem = new GameItem.Builder(ItemId.UTIL_CHEST_GUI_SLOT_HOLDER, Material.LIGHT_GRAY_STAINED_GLASS_PANE)
                .displayName(Component.empty())
                .canDrop(false).canMove(false)
                .build();
        GameItemRegistry.register(gameItem);
    }

    //等待阶段物品

    //**GameItem_waitingPhase_teamSelector
    private static void registerGameItemWaitingPhaseTeamSelector(){
        String id = ItemId.WAITING_PHASE_TEAM_SELECTOR;
        GameItem.Builder builder = new GameItem.Builder(id, Material.NETHER_STAR)
                .displayName(Component.text("选择阵营", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD))
                .addLineOfLore(Component.text("右键打开选边面板", NamedTextColor.GRAY));

        builder.rightClickHandler(event -> {
            GameEventBus.publish(new RightClickGameItemEvent(event.getPlayer(), id));
        });

        builder.canDrop(false).canMove(false);

        GameItem gameItem = builder.build();

        GameItemRegistry.register(gameItem);
    }

    //**GameItem_waitingPhase_readyToggle_xxxxx
    private static void registerGameItemWaitingPhaseReadyToggle(String id, Material material, Component displayName){
        GameItem.Builder builder = new GameItem.Builder(id, material)
                .displayName(displayName);

        builder.rightClickHandler(event -> {
            GameEventBus.publish(new RightClickGameItemEvent(event.getPlayer(), id));
        });

        builder.canDrop(false).canMove(false);

        GameItem gameItem = builder.build();

        GameItemRegistry.register(gameItem);
    }

    //**GameItem_waitingPhase_mapVote
    private static void registerGameItemWaitingPhaseMapVote(){
        String id = ItemId.WAITING_PHASE_MAP_VOTE;

        GameItem.Builder builder = new GameItem.Builder(id, Material.PAPER)
                .displayName(Component.text("票选地图", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD))
                .addLineOfLore(Component.text("暂未开放", NamedTextColor.RED));

        builder.canDrop(false).canMove(false);

        GameItem gameItem = builder.build();
        GameItemRegistry.register(gameItem);
    }

    //**选队按钮
    private static void registerWaitingPhaseTeamButton(String id, Material material, Component displayName){
        GameItem.Builder builder = new GameItem.Builder(id, material)
                .displayName(displayName)
                .canDrop(false).canMove(false);

        builder.inventoryClickHandler(event -> {
            GameEventBus.publish(new InventoryClickGameItemEvent((Player) event.getWhoClicked(), id));
        });

        GameItem gameItem = builder.build();
        GameItemRegistry.register(gameItem);

    }



}
