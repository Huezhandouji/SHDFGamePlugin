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
 * - 提供据点推进接口 {@link #advanceToNextSector()}，由 PlayingPhase / SectorProgressController
 *   在全部炸弹爆炸后调用；推进拆成"激活（{@link #activateCurrentSector()}，只建炸弹）"与
 *   "开启（{@link #openCurrentSector()}，启动时限）"两步，配合地图级 {@code sector_advance_interval}
 *   实现区域推进间隔——间歇期内新据点尚未开启，进攻方时限不计时。
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

    //区域推进间隔（tick）：当前据点被攻占 → 下一个据点正式开启的间歇期；<= 0 表示无间歇期（推进后立即开启）
    private int sectorAdvanceInterval;

    private SectorManager(){}

    public static SectorManager getInstance(){
        return INSTANCE;
    }

    /** 设置区域推进间隔（tick，负值按 0 处理）；由阶段初始化时从 MapConfig 注入 */
    public void setSectorAdvanceInterval(int ticks){
        this.sectorAdvanceInterval = Math.max(0, ticks);
    }

    /** 区域推进间隔（tick）；0 = 无间歇期（推进后立即开启新据点，等同旧行为） */
    public int getSectorAdvanceInterval(){
        return sectorAdvanceInterval;
    }

    //加载地图列表，激活并开启第一个据点
    public void loadMap(List<Sector> sectors){
        this.sectors = sectors;
        this.currentIndex = 0;
        this.allCaptured = false;

        if(sectors != null && !sectors.isEmpty()){
            //第一个据点在开局即开启：间歇期只出现在"攻占当前据点 → 推进到下一个据点"这一步
            activateCurrentSector();
            openCurrentSector();
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

    /**
     * 由 PlayingPhase / SectorProgressController 在确认"当前据点全部炸弹爆炸"后调用：推进到下一个据点。
     * <p>
     * 推进分两步（为"区域推进间隔"服务）：{@link #activateCurrentSector()} 只建炸弹<b>不起表</b>；
     * 据点时限由 {@link #openCurrentSector()} 单独启动。
     * <ul>
     *     <li>{@code sectorAdvanceInterval <= 0}：推进后立即开启（等同旧行为，缺键回退默认 0）；</li>
     *     <li>{@code sectorAdvanceInterval > 0}：新据点保持"已激活未开启"，由
     *     {@code IntermissionController} 在间歇期倒计时结束后调用 {@link #openCurrentSector()}——
     *     间歇期不消耗进攻方时限。</li>
     * </ul>
     * 已无后续据点时置 {@code allCaptured}（全据点攻占）。
     */
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
            //只激活（建炸弹）；是否立即开启取决于区域推进间隔
            activateCurrentSector();
            if(sectorAdvanceInterval <= 0){
                openCurrentSector();
            }
        }
        else{
            allCaptured = true;
        }
    }

    // ==================== 内部 ====================

    /** 是否还有下一个据点（false = 当前是最后一个据点，推进即"全据点攻占"） */
    public boolean hasNextSector(){
        return sectors != null && currentIndex + 1 < sectors.size();
    }

    /**
     * 正式开启当前据点：启动据点时限（进攻方时间从此开始计）。
     * <p>
     * 调用时机：开局第一个据点（{@link #loadMap}）、无间歇期时推进后的新据点，以及
     * 有间歇期时 {@code IntermissionController} 倒计时结束。已开启则不重复启动。
     */
    public void openCurrentSector(){
        Sector current = getCurrentSector();
        if(current == null) return;
        if(currentTimeLimit != null && currentTimeLimit.isRunning()) return;

        currentTimeLimit = new SectorTimeLimit(current);
        currentTimeLimit.start();
    }

    /** 当前据点是否已开启（时限已在计时） */
    public boolean isCurrentSectorOpen(){
        return currentTimeLimit != null && currentTimeLimit.isRunning();
    }

    /** 激活当前据点：只重建运行时炸弹，<b>不启动</b>据点时限（开启由 {@link #openCurrentSector()} 负责） */
    private void activateCurrentSector(){
        Sector current = getCurrentSector();
        if(current == null) return;

        //重建运行时炸弹
        clearActiveBombs();
        for(BombConfig bombConfig : current.getBombs()){
            activeBombs.put(bombConfig.getId(), new ActiveBomb(bombConfig));
        }
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
                        1L, 1L);
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
        //区域推进间隔随本局配置重置，避免跨局残留（每局由阶段初始化重新注入）
        sectorAdvanceInterval = 0;
    }
}
