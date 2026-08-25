package com.sHDFGamePlugin.infrastructure.item;

import com.sHDFGamePlugin.infrastructure.item.component.InventoryClickComponent;
import com.sHDFGamePlugin.infrastructure.item.component.LeftClickComponent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class InteractionManager implements Listener {

    private static final InteractionManager INSTANCE = new InteractionManager();

    private final Map<String, GameItem> gameItemSet = new HashMap<>();
    //由于mc在丢出物品时触发挥手，进而触发左键事件，所以设置一个标志位在丢东西时屏蔽左键事件的触发
    private final Map<UUID, Boolean> leftClickBlocked = new HashMap<>();

    private InteractionManager() {}

    public static InteractionManager getInstance() {
        return INSTANCE;
    }

    public void init(JavaPlugin plugin) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public GameItem getGameItemById(String id) {
        return gameItemSet.get(id);
    }

    public void registerGameItem(String id, GameItem gameItem){
        if(id == null || gameItem == null) return;
        gameItemSet.put(id, gameItem);
    }

    public void unregisterGameItem(String type) {
        gameItemSet.remove(type);
    }

    private GameItem getGameItem(String type) {
        return gameItemSet.get(type);
    }

    //丢弃物品时处理
    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        ItemMeta meta = item.getItemMeta();

        if(GameItem.isGameItem(meta)) {
            String id = GameItem.getGameItemId(meta);
            GameItem gameItemData = gameItemSet.get(id);

            if(gameItemData == null) return;

            if(!gameItemData.isCanDrop()){
                leftClickBlocked.put(event.getPlayer().getUniqueId(), true);
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {

        ItemStack item = event.getItem();
        if(item == null) return;
        ItemMeta meta = item.getItemMeta();
        if(meta == null) return;

        String id = GameItem.getGameItemId(meta);
        if(id == null) return;
        GameItem gameItem = gameItemSet.get(id);
        if(gameItem == null) return;


        if(event.getAction().isRightClick()) {
            event.setCancelled(true);
            gameItem.handleRightClick(event);
        }
        else if(event.getAction().isLeftClick()) {
            UUID playerId = event.getPlayer().getUniqueId();
            if(leftClickBlocked.remove(playerId) != null){
                return;
            }
            event.setCancelled(true);
            gameItem.handleLeftClick(event);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        ItemStack item = event.getCurrentItem();
        if(item == null) return;
        ItemMeta meta = item.getItemMeta();
        if(meta == null) return;
        String id = GameItem.getGameItemId(meta);
        GameItem gameItem = gameItemSet.get(id);
        if(gameItem != null){
            if(!gameItem.isCanMove()){
                event.setCancelled(true);
            }
            if((event.getClick() == ClickType.LEFT || event.getClick() == ClickType.SHIFT_LEFT)
                    && gameItem.getComponent(InventoryClickComponent.TYPE) != null){
                gameItem.handleInventoryClick(event);
            }
        }

        ItemStack cursor = event.getCursor();
        if(cursor.getType().isAir()) return;

        GameItem cursorGameItem = gameItemSet.get(GameItem.getGameItemId(cursor.getItemMeta()));
        if(cursorGameItem != null && !cursorGameItem.isCanMove()){
            event.setCancelled(true);
        }
    }


}
