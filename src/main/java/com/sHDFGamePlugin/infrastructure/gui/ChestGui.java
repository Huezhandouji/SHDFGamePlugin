package com.sHDFGamePlugin.infrastructure.gui;


import com.sHDFGamePlugin.infrastructure.item.GameItem;
import com.sHDFGamePlugin.infrastructure.item.GameItemRegistry;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;


import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class ChestGui {

    private final Inventory inventory;
    private final Map<Integer, ItemStack> items;
    private final Consumer<Player> onClose;

    private ChestGui(Inventory inventory, Map<Integer, ItemStack> items, Consumer<Player> onClose) {
        this.inventory = inventory;
        this.items = items;
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
        private final Map<Integer, ItemStack> items = new HashMap<>();
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

        public Builder setSlot(int slot, ItemStack item){
            items.put(slot, item);
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
            for(Map.Entry<Integer, ItemStack> entry : items.entrySet()){
                inventory.setItem(entry.getKey(), entry.getValue());
            }
            for(int i = 0; i < rows * 9; i++){
                if(inventory.getItem(i) == null){
                    ItemStack holder = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
                    ItemMeta meta = holder.getItemMeta();
                    meta.displayName(Component.empty());
                    meta = GameItem.applyIdOnItemMeta(GameItemRegistry.ItemId.UTIL_CHEST_GUI_SLOT_HOLDER, meta);
                    holder.setItemMeta(meta);
                    inventory.setItem(i, holder);
                }
            }
            return new ChestGui(inventory, this.items, onClose);
        }


    }

}
