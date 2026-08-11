package com.sHDFGamePlugin.infrastructure.item.component;

import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.function.Consumer;

public class RightClickComponent implements ItemComponent {

    public final static String TYPE = "right_click";

    private Consumer<PlayerInteractEvent> handler;

    public RightClickComponent() {}

    public void setHandler(Consumer<PlayerInteractEvent> handler) {
        this.handler = handler;
    }

    public void handleRightClick(PlayerInteractEvent event) {
        if (event.getAction().isRightClick()) {
            handler.accept(event);
        }
    }

    @Override
    public String getType() {
        return TYPE;
    }
}
