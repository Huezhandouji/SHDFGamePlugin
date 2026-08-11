package com.sHDFGamePlugin.infrastructure.item.component;

import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.function.Consumer;

public class LeftClickComponent implements ItemComponent {

    public final static String TYPE = "right_click";

    private Consumer<PlayerInteractEvent> handler;

    public LeftClickComponent() {}


    public LeftClickComponent setHandler(Consumer<PlayerInteractEvent> handler) {
        this.handler = handler;
        return this;
    }

    public void handleLeftClick(PlayerInteractEvent event) {
        if(handler == null) {
            return;
        }
        if (event.getAction().isLeftClick()) {
            handler.accept(event);
        }
    }

    @Override
    public String getType() {
        return TYPE;
    }
}
