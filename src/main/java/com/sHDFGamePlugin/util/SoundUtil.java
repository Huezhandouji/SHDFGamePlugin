package com.sHDFGamePlugin.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.function.Consumer;

/**
 * 音效工具：播放组合音效（成功/失败反馈等）。
 * <p>
 * 无状态静态工具，调度宿主在每次调用时从 {@link Bukkit#getPluginManager()} 解析本插件实例
 * （{@link #PLUGIN_NAME}），不持有静态引用；插件未启用时静默返回，
 * 不会因为取不到插件而抛出异常打断调用方。
 */
public class SoundUtil {

    /** 调度宿主插件名（与 plugin.yml 的 name 一致） */
    private static final String PLUGIN_NAME = "SHDFGamePlugin";

    public static void playNoticeSuccessCombinedSound(Player player) {
        Plugin plugin = plugin();
        if(plugin == null){
            return;
        }
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(
                plugin,
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
        Plugin plugin = plugin();
        if(plugin == null){
            return;
        }
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(
                plugin,
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

    /** 调度宿主插件；未启用/未安装时返回 null */
    private static Plugin plugin(){
        return Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
    }

}
