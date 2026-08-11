package com.sHDFGamePlugin.infrastructure.item.component;

import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.function.Consumer;

public class InventoryClickComponent implements ItemComponent {

    public final static String TYPE = "inventory_click";
    private Consumer<InventoryClickEvent> handler;

    public InventoryClickComponent() {}

    public void setHandler(Consumer<InventoryClickEvent> handler) {
        this.handler = handler;
    }

    public void handleInventoryClick(InventoryClickEvent event) {
        if(event == null) return;
        if(handler == null) return;
        handler.accept(event);
    }

    @Override
    public String getType() {
        return TYPE;
    }
}
