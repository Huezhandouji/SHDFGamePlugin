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

public class GameItem {

    public static final NamespacedKey GAME_ITEM_KEY = new NamespacedKey(
            SHDFGamePlugin.getInstance(),
            "game_item_id"
    );
    private final ItemStack itemStack;
    private final Map<String, ItemComponent> components = new HashMap<>();

    private boolean canDrop = false;
    private boolean canMove = false;

    private String id;

    private GameItem(ItemStack itemStack) {
        this.itemStack = itemStack;
    }

    public ItemStack getItemStack() {
        return itemStack.clone();
    }

    private void addComponent(ItemComponent component) {
        components.put(component.getType(), component);
    }

    public boolean hasComponent(String type) {
        return components.containsKey(type);
    }

    @SuppressWarnings("unchecked")
    public <T extends ItemComponent> T getComponent(String type) {
        return (T) components.get(type);
    }

    public void handleRightClick(PlayerInteractEvent event) {
        if(!hasComponent(RightClickComponent.TYPE)) return;
        ItemComponent component = getComponent(RightClickComponent.TYPE);
        if(component instanceof RightClickComponent component1){
            component1.handleRightClick(event);
        }
    }

    public void handleLeftClick(PlayerInteractEvent event) {
        if(!hasComponent(LeftClickComponent.TYPE)) return;
        ItemComponent component = getComponent(LeftClickComponent.TYPE);
        if(component instanceof LeftClickComponent component1){
            component1.handleLeftClick(event);
        }
    }

    public void handleInventoryClick(InventoryClickEvent event) {
        if(!hasComponent(InventoryClickComponent.TYPE)) return;
        ItemComponent component = getComponent(InventoryClickComponent.TYPE);
        if(component instanceof InventoryClickComponent component1){
            component1.handleInventoryClick(event);
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

    private void setId(String id) {
        this.id = id;
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

    public static class Builder{

        private Material material;
        private Component displayName;
        private List<Component> lore = new ArrayList<>();
        private int amount = 1;
        private String id;

        private Consumer<PlayerInteractEvent> rightClickHandler;
        private Consumer<PlayerInteractEvent> leftClickHandler;
        private Consumer<InventoryClickEvent> inventoryClickHandler;

        private boolean canDrop = false;
        private boolean canMove = false;

        public Builder(String id, Material material) {
            this.material = material;
            this.id = id;
        }

        public Builder displayName(Component displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder lore(List<Component> lore) {
            this.lore = lore;
            return this;
        }

        public Builder addLineOfLore(Component lore) {
            this.lore.add(lore);
            return this;
        }

        public Builder amount(int amount) {
            this.amount = amount;
            return this;
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
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();

            if(displayName != null) {
                meta.displayName(displayName);
            }

            if(lore != null && !lore.isEmpty()) {
                meta.lore(lore);
            }

            meta.getPersistentDataContainer().set(GAME_ITEM_KEY, PersistentDataType.STRING, id);
            item.setItemMeta(meta);

            GameItem gameItem = new GameItem(item);

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
            gameItem.setId(id);



            //

            return gameItem;
        }




    }
}
