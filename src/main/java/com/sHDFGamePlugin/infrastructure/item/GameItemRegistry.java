package com.sHDFGamePlugin.infrastructure.item;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;

public class GameItemRegistry {

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

    static {
        //chestGui占位物品
        registerChestGuiSlotHolder();
    }

    //  GameItem_util_ChestGuiSlotHolder
    private static void registerChestGuiSlotHolder(){
        GameItem gameItem = new GameItem.Builder("GameItem_util_ChestGuiSlotHolder", Material.LIGHT_GRAY_STAINED_GLASS_PANE)
                .displayName(Component.empty())
                .canDrop(false).canMove(false)
                .build();
        GameItemRegistry.register(gameItem);
    }



}
