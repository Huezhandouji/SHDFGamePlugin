package com.sHDFGamePlugin.domain.sector;


import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.infrastructure.GameEventBus;
import com.sHDFGamePlugin.infrastructure.event.SectorTimeLimitExpiredEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.function.Consumer;

public class SectorTimeLimit {

    private final Sector sector;
    private final int totalTicks;
    private int remainingTicks;
    private boolean running;
    private ScheduledTask task;

    public SectorTimeLimit(Sector sector) {
        this.sector = sector;
        this.totalTicks = sector.getTimeLimit();
        if(totalTicks < 0){
            throw new IllegalArgumentException("Total time limit cannot be negative");
        }
        this.remainingTicks = totalTicks;
        this.running = false;
    }


    //启动倒计时
    public void start() {
        if (running) return;

        running = true;
        task = GameContext.getInstance().getPlugin().getServer().getGlobalRegionScheduler()
        .runAtFixedRate(GameContext.getInstance().getPlugin(),
                new Consumer<ScheduledTask>() {
                    @Override
                    public void accept(ScheduledTask scheduledTask) {
                        if(!running) return;
                        remainingTicks -= 1;
                        if(remainingTicks <= 0) {
                            stop();
                            GameEventBus.publish(new SectorTimeLimitExpiredEvent(sector));
                        }
                    }
                },
                0L, 1L);
    }

    //暂停倒计时
    public void stop() {
        if(!running) return;
        if(task != null){
            task.cancel();
            task = null;
        }
        running = false;
    }

    //暂停倒计时后把时间恢复到最大时间
    public void reset() {
        stop();
        this.remainingTicks = totalTicks;
    }

    public int getRemainingTicks() {
        return remainingTicks;
    }

    public int getTotalTicks() {
        return totalTicks;
    }

    public boolean isRunning() {
        return running;
    }

    public Sector getSector() {
        return sector;
    }

}
