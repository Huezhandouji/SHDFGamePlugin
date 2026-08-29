package com.sHDFGamePlugin.infrastructure.item.component;

import org.bukkit.event.player.PlayerInteractEvent;

import java.util.function.Consumer;

/** 右键点击组件 */
public class RightClickComponent implements ItemComponent {

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
}
