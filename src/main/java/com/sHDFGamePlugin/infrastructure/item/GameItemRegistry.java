package com.sHDFGamePlugin.infrastructure.item;

public class GameItemRegistry {

    private GameItemRegistry(){}

    public static void register(GameItem gameItem){
        InteractionManager.getInstance().registerGameItem(gameItem.getId(), gameItem);
    }

    public static void unregister(String id){
        InteractionManager.getInstance().unregisterGameItem(id);
    }

}
