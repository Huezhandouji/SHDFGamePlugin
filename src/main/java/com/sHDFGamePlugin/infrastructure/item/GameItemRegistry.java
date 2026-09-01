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

/**
 * GameItem 注册表（外观类）：统一提供注册、注销、查询与"创建并注册"的入口。
 * <p>
 * 实际存储于 {@link InteractionManager}。
 */
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

    public static GameItem createAndRegister(String id, Consumer<GameItem.Builder> builderCfg){
        GameItem.Builder builder = new GameItem.Builder(id);
        if(builderCfg != null){
            builderCfg.accept(builder);
        }
        GameItem gameItem = builder.build();
        register(gameItem);
        return gameItem;
    }


    public static final class ItemId{
        private ItemId(){}
        public static final String UTIL_CHEST_GUI_SLOT_HOLDER = "GameItem_util_ChestGuiSlotHolder";

    }


    static {
        //chestGui占位物品
        registerChestGuiSlotHolder();
    }

    //  GameItem_util_ChestGuiSlotHolder
    private static void registerChestGuiSlotHolder(){
        GameItem gameItem = new GameItem.Builder(ItemId.UTIL_CHEST_GUI_SLOT_HOLDER)
                .canDrop(false).canMove(false)
                .build();
        GameItemRegistry.register(gameItem);
    }



}
