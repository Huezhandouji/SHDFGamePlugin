package com.sHDFGamePlugin.infrastructure.gui;


import com.sHDFGamePlugin.infrastructure.item.GameItem;
import com.sHDFGamePlugin.infrastructure.item.GameItemRegistry;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;


import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class ChestGui {

    private final Inventory inventory;
    private final Map<Integer, GameItem> gameItems;
    private final Consumer<Player> onClose;

    private ChestGui(Inventory inventory, Map<Integer, GameItem> gameItems, Consumer<Player> onClose) {
        this.inventory = inventory;
        this.gameItems = gameItems;
        this.onClose = onClose;
    }

    public Inventory getInventory() {
        return inventory;
    }

    public void open(Player player){
        player.openInventory(inventory);
    }

    public void handleClose(Player player){
        if(onClose != null){
            onClose.accept(player);
        }
    }

    public static class Builder{
        private Component title;
        private int rows = 6;
        private final Map<Integer, GameItem>  gameItems = new HashMap<>();
        private Consumer<Player> onClose;

        private Builder(){}

        public static Builder create(){
            return new Builder();
        }

        public Builder title(Component title){
            this.title = title;
            return this;
        }

        public Builder rows(int rows){
            if(rows < 1 || rows > 6){
                throw new IllegalArgumentException("rows must be between 1 and 6");
            }
            this.rows = rows;
            return this;
        }

        public Builder setSlot(int slot, GameItem gameItem){
            gameItems.put(slot, gameItem);
            return this;
        }

        public Builder onClose(Consumer<Player> onClose){
            this.onClose = onClose;
            return this;
        }

        public ChestGui build(){
            if(title == null) {
                title = Component.text("unnamed chest GUI");
            }
            Inventory inventory =  Bukkit.createInventory(null, rows * 9, title);
            for(Map.Entry<Integer, GameItem> entry : gameItems.entrySet()){
                inventory.setItem(entry.getKey(), entry.getValue().getItemStack());
            }
            for(int i = 0; i < rows * 9; i++){
                if(inventory.getItem(i) == null){
                    inventory.setItem(i, GameItemRegistry.getGameItem("GameItem_util_ChestGuiSlotHolder").getItemStack());
                }
            }
            return new ChestGui(inventory, gameItems, onClose);
        }


    }

}
