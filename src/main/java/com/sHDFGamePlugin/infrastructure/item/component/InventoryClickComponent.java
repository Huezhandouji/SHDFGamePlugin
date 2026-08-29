package com.sHDFGamePlugin.infrastructure.item.component;

import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.function.Consumer;

/** 物品栏点击组件（区分左/右/Shift+左/Shift+右） */
public class InventoryClickComponent implements ItemComponent {

    private Consumer<InventoryClickEvent> leftClickHandler;
    private Consumer<InventoryClickEvent> leftShiftClickHandler;
    private Consumer<InventoryClickEvent> rightClickHandler;
    private Consumer<InventoryClickEvent> rightShiftClickHandler;

    public InventoryClickComponent() {}

    public void setLeftClickHandler(Consumer<InventoryClickEvent> leftClickHandler) {
        this.leftClickHandler = leftClickHandler;
    }

    public void setLeftShiftClickHandler(Consumer<InventoryClickEvent> leftShiftClickHandler) {
        this.leftShiftClickHandler = leftShiftClickHandler;
    }

    public void setRightClickHandler(Consumer<InventoryClickEvent> rightClickHandler) {
        this.rightClickHandler = rightClickHandler;
    }

    public void setRightShiftClickHandler(Consumer<InventoryClickEvent> rightShiftClickHandler) {
        this.rightShiftClickHandler = rightShiftClickHandler;
    }

    public void setHandler(Consumer<InventoryClickEvent> handler) {
        leftClickHandler = handler;
        leftShiftClickHandler = handler;
        rightClickHandler = handler;
        rightShiftClickHandler = handler;
    }

    public void handleInventoryClick(InventoryClickEvent event) {
        if(event == null) return;
        if(event.isShiftClick()){
            if(event.isLeftClick()){
                leftShiftClickHandler.accept(event);
                return;
            }
            if(event.isRightClick()){
                rightShiftClickHandler.accept(event);
                return;
            }
        }
        else{
            if(event.isLeftClick()){
                leftClickHandler.accept(event);
                return;
            }
            if(event.isRightClick()){
                rightClickHandler.accept(event);
                return;
            }
        }
    }
}
