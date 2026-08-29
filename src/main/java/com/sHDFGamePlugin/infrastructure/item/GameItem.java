package com.sHDFGamePlugin.infrastructure.item;

import com.sHDFGamePlugin.SHDFGamePlugin;
import com.sHDFGamePlugin.infrastructure.item.component.InventoryClickComponent;
import com.sHDFGamePlugin.infrastructure.item.component.ItemComponent;
import com.sHDFGamePlugin.infrastructure.item.component.LeftClickComponent;
import com.sHDFGamePlugin.infrastructure.item.component.RightClickComponent;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 游戏物品逻辑对象（不持有 ItemStack）。
 * <p>
 * 以组件类型（Class）为键持有行为组件，同一类型只能有一个实例。
 * 通过 PDC 中的 {@link #GAME_ITEM_KEY} 与实体物品关联。
 */
public class GameItem {

    public static final NamespacedKey GAME_ITEM_KEY = new NamespacedKey(
            SHDFGamePlugin.getInstance(),
            "game_item_id"
    );

    private final Map<Class<? extends ItemComponent>, ItemComponent> components = new HashMap<>();

    private boolean canDrop = false;
    private boolean canMove = false;

    private String id;

    private GameItem(String id) {
        this.id = id;
    }


    public static ItemStack applyIdOnItemStack(String id, ItemStack baseItem){
        ItemStack copy = baseItem.clone();
        ItemMeta meta = copy.getItemMeta();
        meta.getPersistentDataContainer().set(GAME_ITEM_KEY, PersistentDataType.STRING, id);
        copy.setItemMeta(meta);
        return copy;
    }

    public static ItemMeta applyIdOnItemMeta(String id, ItemMeta baseMeta){
        ItemMeta copy = baseMeta.clone();
        copy.getPersistentDataContainer().set(GAME_ITEM_KEY, PersistentDataType.STRING, id);
        return copy;
    }



    private void addComponent(ItemComponent component) {
        components.put(component.getClass(), component);
    }

    public boolean hasComponent(Class<? extends ItemComponent> type) {
        return components.containsKey(type);
    }

    public <T extends ItemComponent> T getComponent(Class<T> type) {
        return type.cast(components.get(type));
    }

    public void handleRightClick(PlayerInteractEvent event) {
        RightClickComponent component = getComponent(RightClickComponent.class);
        if(component != null){
            component.handleRightClick(event);
        }
    }

    public void handleLeftClick(PlayerInteractEvent event) {
        LeftClickComponent component = getComponent(LeftClickComponent.class);
        if(component != null){
            component.handleLeftClick(event);
        }
    }

    public void handleInventoryClick(InventoryClickEvent event) {
        InventoryClickComponent component = getComponent(InventoryClickComponent.class);
        if(component != null){
            component.handleInventoryClick(event);
        }
    }

    public boolean isCanDrop() {
        return canDrop;
    }

    private void setCanDrop(boolean canDrop) {
        this.canDrop = canDrop;
    }

    public boolean isCanMove() {
        return canMove;
    }

    private void setCanMove(boolean canMove) {
        this.canMove = canMove;
    }

    public String getId() {
        return id;
    }

    public static boolean isGameItem(ItemMeta meta) {
        return meta.getPersistentDataContainer().has(GAME_ITEM_KEY, PersistentDataType.STRING);
    }

    public static String getGameItemId(ItemMeta meta) {
        return meta.getPersistentDataContainer().get(GAME_ITEM_KEY, PersistentDataType.STRING);
    }
    public static String getGameItemId(ItemStack itemStack) {
        return itemStack.getPersistentDataContainer().get(GAME_ITEM_KEY, PersistentDataType.STRING);
    }


    //构建器
    public static class Builder{
        private List<Component> lore = new ArrayList<>();
        private final String id;

        private Consumer<PlayerInteractEvent> rightClickHandler;
        private Consumer<PlayerInteractEvent> leftClickHandler;
        private Consumer<InventoryClickEvent> inventoryClickHandler;

        private boolean canDrop = false;
        private boolean canMove = false;

        public Builder(String id) {
            this.id = id;
        }


        public Builder canDrop(boolean canDrop) {
            this.canDrop = canDrop;
            return this;
        }

        public Builder canMove(boolean canMove) {
            this.canMove = canMove;
            return this;
        }

        public Builder rightClickHandler(Consumer<PlayerInteractEvent> rightClickHandler) {
            this.rightClickHandler = rightClickHandler;
            return this;
        }

        public Builder leftClickHandler(Consumer<PlayerInteractEvent> leftClickHandler) {
            this.leftClickHandler = leftClickHandler;
            return this;
        }

        public Builder inventoryClickHandler(Consumer<InventoryClickEvent> inventoryClickHandler) {
            this.inventoryClickHandler = inventoryClickHandler;
            return this;
        }



        public GameItem build() {

            GameItem gameItem = new GameItem(id);

            if(rightClickHandler != null) {
                RightClickComponent rc = new RightClickComponent();
                rc.setHandler(rightClickHandler);
                gameItem.addComponent(rc);
            }

            if(leftClickHandler != null) {
                LeftClickComponent lc = new LeftClickComponent();
                lc.setHandler(leftClickHandler);
                gameItem.addComponent(lc);
            }

            if(inventoryClickHandler != null) {
                InventoryClickComponent ic = new InventoryClickComponent();
                ic.setHandler(inventoryClickHandler);
                gameItem.addComponent(ic);
            }

            gameItem.setCanDrop(canDrop);
            gameItem.setCanMove(canMove);



            return gameItem;
        }




    }
}
