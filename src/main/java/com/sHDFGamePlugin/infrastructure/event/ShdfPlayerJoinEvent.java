package com.sHDFGamePlugin.infrastructure.event;

import com.sHDFGamePlugin.infrastructure.SHDFGameEvent;
import org.bukkit.entity.Player;

public class ShdfPlayerJoinEvent extends SHDFGameEvent {

    private final Player player;

    public ShdfPlayerJoinEvent(Player player) {
        this.player = player;
    }

    public Player getPlayer() {
        return player;
    }

}
