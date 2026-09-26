package com.sHDFGamePlugin.infrastructure.item;

import com.sHDFGamePlugin.infrastructure.item.component.InventoryClickComponent;
import com.sHDFGamePlugin.infrastructure.item.component.LeftClickComponent;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * GameItem 交互管理器：持有全部已注册 GameItem，并把原版交互事件派发给它们。
 * <p>
 * 实例由 {@link com.sHDFGamePlugin.core.GameContext} 创建并持有；插件主类在 onEnable 里把它
 * 注册为事件监听器，onDisable 时由 {@link #unregister()} 注销，不残留静态引用。
 */
public class InteractionManager implements Listener {

    private final JavaPlugin plugin;
    /** 物品 id 的 PDC 键（构造时算一次；物品读写走热路径，不宜每次调用都新建） */
    private final NamespacedKey gameItemKey;

    private final Map<String, GameItem> gameItemSet = new HashMap<>();
    //由于mc在丢出物品时触发挥手，进而触发左键事件，所以设置一个标志位在丢东西时屏蔽左键事件的触发
    private final Map<UUID, Boolean> leftClickBlocked = new HashMap<>();
    //由于mc右击地面会发送右击空气的数据包，所以设置时间戳去重
    private final Map<UUID, Long> lastRightClickTime = new HashMap<>();

    public InteractionManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.gameItemKey = new NamespacedKey(plugin, GameItem.KEY_NAME);
    }

    public void init() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /** 插件禁用时注销自身监听器（幂等） */
    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    /** 物品 id 的 PDC 键（由插件实例派生，构造时缓存） */
    public NamespacedKey gameItemKey() {
        return gameItemKey;
    }

    public GameItem getGameItemById(String id) {
        return gameItemSet.get(id);
    }

    public void registerGameItem(String id, GameItem gameItem){
        if(id == null || gameItem == null) return;
        if(gameItemSet.containsKey(id)){
            plugin.getLogger().warning("[InteractionManager] GameItem '" + id + "' is already registered, it will be overwritten!");
        }
        gameItemSet.put(id, gameItem);
    }

    public void unregisterGameItem(String type) {
        gameItemSet.remove(type);
    }

    //丢弃物品时处理
    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        ItemMeta meta = item.getItemMeta();

        if(GameItem.isGameItem(gameItemKey(), meta)) {
            String id = GameItem.getGameItemId(gameItemKey(), meta);
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

        String id = GameItem.getGameItemId(gameItemKey(), meta);
        if(id == null) return;
        GameItem gameItem = gameItemSet.get(id);
        if(gameItem == null) return;


        if(event.getAction().isRightClick()) {
            UUID playerid = event.getPlayer().getUniqueId();
            long now = System.currentTimeMillis();
            Long last = lastRightClickTime.get(playerid);

            if(last != null && now - last < 100){
                return;
            }
            lastRightClickTime.put(playerid, now);


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
        String id = GameItem.getGameItemId(gameItemKey(), meta);
        GameItem gameItem = gameItemSet.get(id);
        if(gameItem != null){
            if(!gameItem.isCanMove()){
                event.setCancelled(true);
            }
            if((event.getClick() == ClickType.LEFT || event.getClick() == ClickType.SHIFT_LEFT)
                    && gameItem.getComponent(InventoryClickComponent.class) != null){
                gameItem.handleInventoryClick(event);
            }
        }

        ItemStack cursor = event.getCursor();
        if(cursor.getType().isAir()) return;

        GameItem cursorGameItem = gameItemSet.get(GameItem.getGameItemId(gameItemKey(), cursor.getItemMeta()));
        if(cursorGameItem != null && !cursorGameItem.isCanMove()){
            event.setCancelled(true);
        }
    }


}
