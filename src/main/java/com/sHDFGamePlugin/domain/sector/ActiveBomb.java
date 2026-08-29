package com.sHDFGamePlugin.domain.sector;

import com.sHDFGamePlugin.infrastructure.config.BombConfig;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/**
 * 运行时炸弹：静态配置 {@link BombConfig} + 当前据点的可变状态。
 * <p>
 * 由 SectorManager 创建与维护。引信倒计时的调度由 SectorManager 负责，
 * 本类只持有任务引用并提供状态切换与 tick 接口。
 */
public class ActiveBomb {

    private final BombConfig config;
    private BombState state;
    private int fuseRemaining;
    private ScheduledTask fuseTask;

    public ActiveBomb(BombConfig config) {
        this.config = config;
        this.state = BombState.UNPLANTED;
        this.fuseRemaining = 0;
    }

    // ==================== 查询 ====================

    public BombConfig getConfig() {
        return config;
    }

    public String getId() {
        return config.getId();
    }

    public BombState getState() {
        return state;
    }

    /** 引信剩余时间（tick），未安放时为 0 */
    public int getFuseRemaining() {
        return fuseRemaining;
    }

    public boolean isPlanted() {
        return state == BombState.PLANTED;
    }

    public boolean isExploded() {
        return state == BombState.EXPLODED;
    }

    // ==================== 状态切换 ====================

    /** 安放成功：UNPLANTED -> PLANTED，重置引信 */
    public void plant() {
        state = BombState.PLANTED;
        fuseRemaining = config.getFuseTime();
    }

    /** 拆弹成功：PLANTED -> UNPLANTED，取消引信 */
    public void defuse() {
        stopFuse();
        state = BombState.UNPLANTED;
        fuseRemaining = 0;
    }

    /** 引信归零：PLANTED -> EXPLODED */
    public void explode() {
        stopFuse();
        state = BombState.EXPLODED;
        fuseRemaining = 0;
    }

    // ==================== 引信 ====================

    public ScheduledTask getFuseTask() {
        return fuseTask;
    }

    public void setFuseTask(ScheduledTask fuseTask) {
        this.fuseTask = fuseTask;
    }

    public void stopFuse() {
        if (fuseTask != null) {
            fuseTask.cancel();
            fuseTask = null;
        }
    }

    /** 每 tick 调用一次，引信递减 */
    public void tickFuse() {
        if (fuseRemaining > 0) {
            fuseRemaining -= 1;
        }
    }
}
