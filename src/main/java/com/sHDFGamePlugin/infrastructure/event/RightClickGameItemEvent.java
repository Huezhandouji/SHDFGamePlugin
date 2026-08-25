package com.sHDFGamePlugin.infrastructure.event;

import org.bukkit.entity.Player;

public class RightClickGameItemEvent {

    private Player player;
    private String gameItemId;

    public RightClickGameItemEvent(Player player, String gameItemId) {
        this.player = player;
        this.gameItemId = gameItemId;
    }

    public Player getPlayer() {
        return player;
    }

    public String getGameItemId() {
        return gameItemId;
    }

}
