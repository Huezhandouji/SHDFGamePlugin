package com.sHDFGamePlugin.listener;

import com.sHDFGamePlugin.domain.spawn.SpawnManager;
import com.sHDFGamePlugin.infrastructure.GameEventBus;
import com.sHDFGamePlugin.infrastructure.RoleBridge;
import com.sHDFGamePlugin.infrastructure.event.ShdfPlayerQuitEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerQuitListener implements Listener {

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event){
        GameEventBus.publish(new ShdfPlayerQuitEvent(event.getPlayer()));
    }

}
