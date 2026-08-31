package com.sHDFGamePlugin.listener;

import com.sHDFGamePlugin.infrastructure.GameEventBus;
import com.sHDFGamePlugin.infrastructure.event.ShdfPlayerJoinEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Bukkit 玩家加入事件 -> 游戏事件总线（薄适配层） */
public class PlayerJoinListener implements Listener {

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        GameEventBus.publish(new ShdfPlayerJoinEvent(event.getPlayer()));
    }

}
