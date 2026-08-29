package com.sHDFGamePlugin.infrastructure.gui;


import com.sHDFGamePlugin.infrastructure.item.GameItem;
import com.sHDFGamePlugin.infrastructure.item.GameItemRegistry;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 箱子 GUI。
 * <p>
 * 能力：
 * - Builder 构建（标题、行数、槽位、onClose 回调）；
 * - 空槽自动填充占位物品（不可移动，防止误操作）；
 * - 动态刷新：{@link #setSlot} / {@link #refresh} 对已打开的玩家实时生效；
 * - 打开状态注册表：{@link #getOpenGui} / {@link #closeAllGuis} 支持批量刷新与阶段切换清理。
 */
public class ChestGui {

    //所有已打开的 GUI：player -> gui（用于批量刷新 / 阶段切换时清理）
    private static final Map<Player, ChestGui> OPEN_GUIS = new HashMap<>();

    private final Inventory inventory;
    private final Consumer<Player> onClose;

    private ChestGui(Inventory inventory, Consumer<Player> onClose) {
        this.inventory = inventory;
        this.onClose = onClose;
    }

    public Inventory getInventory() {
        return inventory;
    }

    public void open(Player player) {
        OPEN_GUIS.put(player, this);
        player.openInventory(inventory);
    }

    /** 由 InventoryCloseEvent 监听器（或阶段清理）调用 */
    public void handleClose(Player player) {
        OPEN_GUIS.remove(player);
        if (onClose != null) {
            onClose.accept(player);
        }
    }

    // ==================== 动态内容刷新 ====================

    /** 更新某个槽位的内容（对已打开的玩家实时生效） */
    public void setSlot(int slot, ItemStack item) {
        inventory.setItem(slot, item);
    }

    /** 清空某个槽位 */
    public void clearSlot(int slot) {
        inventory.setItem(slot, null);
    }

    /** 刷新：把空槽重新填上占位物品（内容更新后调用） */
    public void refresh() {
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, createSlotHolder());
            }
        }
    }

    // ==================== 打开状态管理 ====================

    /** 获取玩家当前打开的 GUI；未打开返回 null */
    public static ChestGui getOpenGui(Player player) {
        return OPEN_GUIS.get(player);
    }

    /** 全部已打开的 GUI */
    public static Collection<ChestGui> getAllOpenGuis() {
        return OPEN_GUIS.values();
    }

    /** 关闭某玩家的 GUI（若他打开的是本插件 GUI） */
    public static void closeOpenGui(Player player) {
        ChestGui gui = OPEN_GUIS.get(player);
        if (gui != null) {
            gui.close(player);
        }
    }

    /** 关闭并清空所有已打开的 GUI（阶段切换/回收时调用） */
    public static void closeAllGuis() {
        for (Player player : new ArrayList<>(OPEN_GUIS.keySet())) {
            ChestGui gui = OPEN_GUIS.get(player);
            if (gui != null) {
                gui.close(player);
            }
        }
        OPEN_GUIS.clear();
    }

    private void close(Player player) {
        if (player.getOpenInventory().getTopInventory() == inventory) {
            player.closeInventory();
        }
        OPEN_GUIS.remove(player);
    }

    private static ItemStack createSlotHolder() {
        ItemStack holder = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = holder.getItemMeta();
        meta.displayName(Component.empty());
        meta = GameItem.applyIdOnItemMeta(GameItemRegistry.ItemId.UTIL_CHEST_GUI_SLOT_HOLDER, meta);
        holder.setItemMeta(meta);
        return holder;
    }

    public static class Builder {
        private Component title;
        private int rows = 6;
        private final Map<Integer, ItemStack> items = new HashMap<>();
        private Consumer<Player> onClose;

        private Builder() {}

        public static Builder create() {
            return new Builder();
        }

        public Builder title(Component title) {
            this.title = title;
            return this;
        }

        public Builder rows(int rows) {
            if (rows < 1 || rows > 6) {
                throw new IllegalArgumentException("rows must be between 1 and 6");
            }
            this.rows = rows;
            return this;
        }

        public Builder setSlot(int slot, ItemStack item) {
            items.put(slot, item);
            return this;
        }

        public Builder onClose(Consumer<Player> onClose) {
            this.onClose = onClose;
            return this;
        }

        public ChestGui build() {
            if (title == null) {
                title = Component.text("unnamed chest GUI");
            }
            Inventory inventory = Bukkit.createInventory(null, rows * 9, title);
            for (Map.Entry<Integer, ItemStack> entry : items.entrySet()) {
                inventory.setItem(entry.getKey(), entry.getValue());
            }
            ChestGui gui = new ChestGui(inventory, onClose);
            gui.refresh(); //空槽填充占位物品
            return gui;
        }
    }
}
