package com.sHDFGamePlugin.infrastructure;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 断线保护管理器（单例）：玩家退出时保留其状态一段时间（重连时限），超时仍未重连则执行清理。
 * <p>
 * 任务句柄集中持有：
 * - 玩家重连时可单独 {@link #cancel(UUID)}；
 * - 对局结束时可统一 {@link #cancelAll()}，避免残留回调跨局触发。
 */
public class DisconnectProtection {

    private static final DisconnectProtection INSTANCE = new DisconnectProtection();

    /** 超时动作：由使用方定义（如"移除 PlayerStatus 并释放角色"） */
    public interface TimeoutAction {
        void onExpired(UUID uuid);
    }

    //玩家 uuid -> 挂起的超时任务
    private final Map<UUID, ScheduledTask> pendingTasks = new HashMap<>();

    private DisconnectProtection() {}

    public static DisconnectProtection getInstance() {
        return INSTANCE;
    }

    /**
     * 玩家退出时开启保护：先取消该玩家已有的旧任务，再挂一个新的。
     *
     * @param plugin     插件实例
     * @param uuid       退出的玩家
     * @param delayTicks 重连保护时长（tick），到点仍未重连则执行 action
     * @param action     超时动作
     */
    public void start(JavaPlugin plugin, UUID uuid, long delayTicks, TimeoutAction action){
        cancel(uuid);
        ScheduledTask task = plugin.getServer().getGlobalRegionScheduler().runDelayed(
                plugin,
                scheduledTask -> {
                    pendingTasks.remove(uuid);
                    //触发时玩家仍未上线才算超时
                    if(Bukkit.getPlayer(uuid) == null){
                        action.onExpired(uuid);
                    }
                },
                delayTicks
        );
        pendingTasks.put(uuid, task);
    }

    /** 玩家重连时调用：取消其挂起的保护任务（无则空操作） */
    public void cancel(UUID uuid){
        ScheduledTask task = pendingTasks.remove(uuid);
        if(task != null){
            task.cancel();
        }
    }

    /** 对局结束清理时调用：取消所有挂起的保护任务 */
    public void cancelAll(){
        for(ScheduledTask task : pendingTasks.values()){
            task.cancel();
        }
        pendingTasks.clear();
    }
}
