package com.sHDFGamePlugin.domain.sector;

import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.infrastructure.GameEventBus;
import com.sHDFGamePlugin.infrastructure.config.BombConfig;
import com.sHDFGamePlugin.infrastructure.event.BombDefusedEvent;
import com.sHDFGamePlugin.infrastructure.event.BombExplodedEvent;
import com.sHDFGamePlugin.infrastructure.event.BombPlantedEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 据点与炸弹管理。
 * <p>
 * 职责：
 * - 管理当前据点的多个炸弹（每个炸弹独立状态 UNPLANTED -> PLANTED -> EXPLODED，拆弹成功回 UNPLANTED）；
 * - 每个炸弹各自维护引信倒计时，归零时发布 {@link BombExplodedEvent}；
 * - 维护据点时限（进攻方时间限制），由 {@link SectorTimeLimit} 驱动；
 * - 提供据点推进接口 {@link #advanceToNextSector()}，由 PlayingPhase 在全部炸弹爆炸后调用。
 * <p>
 * 不处理玩家互动细节（站位、进度累积、打断检测），这些属于 PlayingPhase。
 */
public class SectorManager {

    private static final SectorManager INSTANCE = new SectorManager();

    private List<Sector> sectors;
    private int currentIndex;
    private boolean allCaptured;

    //当前据点的运行时炸弹：bombId -> ActiveBomb（LinkedHashMap 保持配置顺序）
    private final Map<String, ActiveBomb> activeBombs = new LinkedHashMap<>();

    //据点时限（进攻方时间限制）
    private SectorTimeLimit currentTimeLimit;

    private SectorManager(){}

    public static SectorManager getInstance(){
        return INSTANCE;
    }

    //加载地图列表，激活第一个据点
    public void loadMap(List<Sector> sectors){
        this.sectors = sectors;
        this.currentIndex = 0;
        this.allCaptured = false;

        if(sectors != null && !sectors.isEmpty()){
            activateCurrentSector();
        }
        else {
            this.allCaptured = true;
        }
    }

    // ==================== 炸弹查询 ====================

    /** 当前据点的炸弹配置列表（用于 GUI 展示，保持配置顺序） */
    public List<BombConfig> getCurrentSectorBombs(){
        Sector current = getCurrentSector();
        if(current == null){
            return List.of();
        }
        return current.getBombs();
    }

    /** 当前据点的全部运行时炸弹（不可变视图，保持配置顺序） */
    public List<ActiveBomb> getActiveBombs(){
        return Collections.unmodifiableList(new ArrayList<>(activeBombs.values()));
    }

    /** 按 bombId 获取运行时炸弹；未知 id 返回 null */
    public ActiveBomb getActiveBomb(String bombId){
        return activeBombs.get(bombId);
    }

    /** 按 bombId 获取炸弹状态；未知 id 返回 null */
    public BombState getBombState(String bombId){
        ActiveBomb bomb = activeBombs.get(bombId);
        if(bomb == null){
            return null;
        }
        return bomb.getState();
    }

    /** 按 bombId 获取引信剩余时间（tick）；未知 id 或未安放时为 0 */
    public int getBombFuseRemaining(String bombId){
        ActiveBomb bomb = activeBombs.get(bombId);
        if(bomb == null){
            return 0;
        }
        return bomb.getFuseRemaining();
    }

    /** 当前据点是否全部炸弹已爆炸（空列表视为 false） */
    public boolean isAllBombsExploded(){
        if(activeBombs.isEmpty()) return false;
        for(ActiveBomb bomb : activeBombs.values()){
            if(bomb.getState() != BombState.EXPLODED) return false;
        }
        return true;
    }

    // ==================== 炸弹状态切换 ====================

    /** 进攻方安放进度完成后调用：指定炸弹 UNPLANTED -> PLANTED，并启动其引信 */
    public boolean onBombPlantSuccess(String bombId){
        ActiveBomb bomb = activeBombs.get(bombId);
        if(bomb == null) return false;
        if(bomb.getState() != BombState.UNPLANTED) return false;

        bomb.plant();
        startBombFuse(bomb);
        GameEventBus.publish(new BombPlantedEvent(getCurrentSector(), bomb.getConfig()));
        return true;
    }

    /** 防守方拆弹进度完成后调用：指定炸弹 PLANTED -> UNPLANTED，取消其引信 */
    public boolean onBombDefuseSuccess(String bombId){
        ActiveBomb bomb = activeBombs.get(bombId);
        if(bomb == null) return false;
        if(bomb.getState() != BombState.PLANTED) return false;

        bomb.defuse();
        GameEventBus.publish(new BombDefusedEvent(getCurrentSector(), bomb.getConfig()));
        return true;
    }

    // ==================== 据点推进 ====================

    /** 由 PlayingPhase 在确认全部炸弹爆炸后调用：推进到下一个据点 */
    public void advanceToNextSector(){
        if(allCaptured) return;

        //停止当前据点时限
        if(currentTimeLimit != null){
            currentTimeLimit.stop();
            currentTimeLimit = null;
        }

        clearActiveBombs();

        if(currentIndex + 1 < sectors.size()){
            currentIndex += 1;
            activateCurrentSector();
        }
        else{
            allCaptured = true;
        }
    }

    // ==================== 内部 ====================

    private void activateCurrentSector(){
        Sector current = getCurrentSector();
        if(current == null) return;

        //重建运行时炸弹
        clearActiveBombs();
        for(BombConfig bombConfig : current.getBombs()){
            activeBombs.put(bombConfig.getId(), new ActiveBomb(bombConfig));
        }

        //启动据点时限
        currentTimeLimit = new SectorTimeLimit(current);
        currentTimeLimit.start();
    }

    private void clearActiveBombs(){
        for(ActiveBomb bomb : activeBombs.values()){
            bomb.stopFuse();
        }
        activeBombs.clear();
    }

    private void startBombFuse(ActiveBomb bomb){
        bomb.stopFuse();
        ScheduledTask task = GameContext.getInstance().getPlugin().getServer().getGlobalRegionScheduler()
                .runAtFixedRate(GameContext.getInstance().getPlugin(),
                        new Consumer<ScheduledTask>() {
                            @Override
                            public void accept(ScheduledTask scheduledTask) {
                                if(bomb.getState() != BombState.PLANTED){
                                    scheduledTask.cancel();
                                    return;
                                }
                                bomb.tickFuse();
                                if(bomb.getFuseRemaining() <= 0){
                                    bomb.explode();
                                    GameEventBus.publish(new BombExplodedEvent(getCurrentSector(), bomb.getConfig()));
                                }
                            }
                        },
                        0L, 1L);
        bomb.setFuseTask(task);
    }

    // ==================== 查询与清理 ====================

    public Sector getCurrentSector(){
        if(sectors == null || currentIndex < 0 || currentIndex >= sectors.size()) return null;
        return sectors.get(currentIndex);
    }

    public boolean isAllCaptured(){
        return allCaptured;
    }

    public int getCurrentTimeLimitRemaining(){
        if(currentTimeLimit == null){
            return 0;
        }
        return currentTimeLimit.getRemainingTicks();
    }

    public void cleanup(){
        clearActiveBombs();
        if(currentTimeLimit != null){
            currentTimeLimit.stop();
            currentTimeLimit = null;
        }
        sectors = null;
        allCaptured = false;
        currentIndex = 0;
    }
}
