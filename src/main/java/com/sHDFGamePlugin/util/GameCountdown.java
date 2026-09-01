package com.sHDFGamePlugin.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.Consumer;

/**
 * 通用倒计时（tick 制）：启动后每 tick 递减，可设置 tick/取消/完成回调。
 * <p>
 * 由 WaitingPhase / RoleSelectingPhase 等阶段共享。
 */
public class GameCountdown {

    private final JavaPlugin plugin;
    private final int totalTicks;
    private int remainingTicks;
    private ScheduledTask task;
    private Runnable onFinish;
    private Consumer<Integer> onTick;
    private Consumer<String> onCancel;

    public GameCountdown(JavaPlugin plugin, int totalTicks){
        this.plugin = plugin;
        this.totalTicks = totalTicks;
        this.remainingTicks = totalTicks;
    }

    public void setOnFinish(Runnable onFinish){
        this.onFinish = onFinish;
    }

    public void setOnTick(Consumer<Integer> onTick){
        this.onTick = onTick;
    }

    public void setOnCancel(Consumer<String> onCancel){
        this.onCancel = onCancel;
    }

    public void start(){
        if(task != null) return;

        task = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(
                plugin,
                new Consumer<ScheduledTask>() {
                    @Override
                    public void accept(ScheduledTask scheduledTask) {
                        remainingTicks -= 1;
                        if(remainingTicks <= 0){
                            finish();
                            return;
                        }
                        //onTick 为可选回调（如角色选择阶段未设置时不触发）
                        if(onTick != null){
                            onTick.accept(remainingTicks);
                        }
                    }
                },
                1L, 1L
        );
    }

    public void cancel(String reason){
        if(task == null) return;
        task.cancel();
        task = null;
        if(onCancel != null){
            onCancel.accept(reason);
        }
    }

    public void finish(){
        if(task != null){
            task.cancel();
            task = null;
        }
        if(onFinish != null){
            onFinish.run();
        }
    }

    public boolean isRunning(){
        return task != null;
    }

    public int getRemainingTicks(){
        return remainingTicks;
    }

    public double getRemainingSeconds(){
        return remainingTicks / 20d;
    }
}
