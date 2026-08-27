package com.sHDFGamePlugin.util;

import com.sHDFGamePlugin.core.GameContext;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.function.Consumer;

public class SoundUtil {

    public static void playNoticeSuccessCombinedSound(Player player) {
        GameContext.getInstance().getPlugin().getServer().getGlobalRegionScheduler().runAtFixedRate(
                GameContext.getInstance().getPlugin(),
                new Consumer<ScheduledTask>() {
                    int count = 0;
                    @Override
                    public void accept(ScheduledTask scheduledTask) {
                        if(!player.isOnline() || player.isDead() || count >= 4){
                            scheduledTask.cancel();
                            return;
                        }
                        if (count == 0) {
                            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.8f);
                        } else if (count == 1) {
                            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
                        } else if (count == 2 || count == 3) {
                            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);
                        }
                        count += 1;
                    }
                },
                1L, 2L
        );
    }

    public static void playNoticeFailCombinedSound(Player player) {
        GameContext.getInstance().getPlugin().getServer().getGlobalRegionScheduler().runAtFixedRate(
                GameContext.getInstance().getPlugin(),
                new Consumer<ScheduledTask>() {
                    int count = 0;
                    @Override
                    public void accept(ScheduledTask scheduledTask) {
                        if(!player.isOnline() || player.isDead() || count >= 3){
                            scheduledTask.cancel();
                            return;
                        }
                        if (count == 0) {
                            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);
                        }
                        if(count >= 1){
                            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.8f);
                        }
                        count += 1;
                    }
                },
                1L, 2L
        );
    }

}
