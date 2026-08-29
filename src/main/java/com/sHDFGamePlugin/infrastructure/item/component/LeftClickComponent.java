package com.sHDFGamePlugin.infrastructure.item.component;

import org.bukkit.event.player.PlayerInteractEvent;

import java.util.function.Consumer;

/**
 * 左键点击组件。
 * <p>
 * 注：旧实现 TYPE 误写为 "right_click"，导致左/右键组件互相覆盖；
 * 现以 Class 为键存储，不再需要字符串类型标识。
 */
public class LeftClickComponent implements ItemComponent {

    private Consumer<PlayerInteractEvent> handler;

    public LeftClickComponent() {}

    public LeftClickComponent setHandler(Consumer<PlayerInteractEvent> handler) {
        this.handler = handler;
        return this;
    }

    public void handleLeftClick(PlayerInteractEvent event) {
        if (handler == null) {
            return;
        }
        if (event.getAction().isLeftClick()) {
            handler.accept(event);
        }
    }
}
