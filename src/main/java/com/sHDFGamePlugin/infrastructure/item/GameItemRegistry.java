package com.sHDFGamePlugin.infrastructure.item;

import java.util.function.Consumer;

/**
 * GameItem 注册表（外观类）：统一提供注册、注销、查询与"创建并注册"的入口。
 * <p>
 * 实际存储于 {@link InteractionManager}；本类由 {@link com.sHDFGamePlugin.core.GameContext}
 * 创建并持有（实例，非静态单例）。
 */
public final class GameItemRegistry {

    /** 占位物品 id 常量（纯常量，无状态，保持静态） */
    public static final class ItemId {
        private ItemId(){}
        public static final String UTIL_CHEST_GUI_SLOT_HOLDER = "GameItem_util_ChestGuiSlotHolder";

    }

    private final InteractionManager interactionManager;

    public GameItemRegistry(InteractionManager interactionManager) {
        this.interactionManager = interactionManager;
        //构造即注册 ChestGui 的占位物品（原先由静态初始化块完成，现随上下文生命周期）
        registerChestGuiSlotHolder();
    }

    public void register(GameItem gameItem){
        interactionManager.registerGameItem(gameItem.getId(), gameItem);
    }

    public void unregister(String id){
        interactionManager.unregisterGameItem(id);
    }

    public GameItem getGameItem(String id){
        return interactionManager.getGameItemById(id);
    }

    public GameItem createAndRegister(String id, Consumer<GameItem.Builder> builderCfg){
        GameItem.Builder builder = new GameItem.Builder(id);
        if(builderCfg != null){
            builderCfg.accept(builder);
        }
        GameItem gameItem = builder.build();
        register(gameItem);
        return gameItem;
    }

    //  GameItem_util_ChestGuiSlotHolder
    private void registerChestGuiSlotHolder(){
        GameItem gameItem = new GameItem.Builder(ItemId.UTIL_CHEST_GUI_SLOT_HOLDER)
                .canDrop(false).canMove(false)
                .build();
        register(gameItem);
    }



}
