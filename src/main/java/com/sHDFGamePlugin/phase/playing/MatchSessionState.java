package com.sHDFGamePlugin.phase.playing;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import com.sHDFGamePlugin.domain.team.ShdfTeam;
import org.bukkit.event.Listener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 本局（PLAYING 阶段）共享运行时状态：原 PlayingPhase 的集合与标志位集中在此，任何模块都只读/写这一份，不再各自持有副本。
 * <p>
 * 持有内容（拆分前均为 PlayingPhase 的实例字段）：
 * <ul>
 *     <li>{@link #getActiveProgresses()} —— 进行中的装弹/拆弹进度（玩家 uuid -> 进度），归属
 *     {@link BombInteractionController}；</li>
 *     <li>{@link #addDeployFailureLogged(UUID)} —— 自动部署失败日志去重集合，归属
 *     {@link DeploymentController}；</li>
 *     <li>{@link #hasDeathCountdown(UUID)} —— 死后等待重生的每秒播报去重表，归属
 *     {@link DeploymentController}/{@link DeathHandler}；</li>
 *     <li>{@link #isMatchEnded()} —— 本局是否已结束（防多个结束条件重复触发结算），由后续的
 *     SectorProgressController（t20）置 true；</li>
 *     <li>各 {@link ScheduledTask}（重生驱动、装弹/拆弹驱动、已安放炸弹粒子）与各
 *     {@link Listener}（等待期守卫、战斗死亡、移动冻结）句柄，保证注册/注销成对且随阶段生命周期。</li>
 * </ul>
 * <p>
 * 生命周期：由 {@code PlayingPhase.onEnter} 置 {@code matchEnded=false}，{@code PlayingPhase.onExit}
 * 调用各 clear 方法清空——与拆分前 PlayingPhase 的清理语义逐条一致。
 * <p>
 * 依赖方向：本类只做状态容器，不调用任何控制器方法；仅类型上引用
 * {@link BombInteractionController.BombProgress}（同包内部类），控制器 -> 本类的单向读写依赖。
 */
public final class MatchSessionState {

    private static final MatchSessionState INSTANCE = new MatchSessionState();

    private MatchSessionState() {}

    public static MatchSessionState getInstance() {
        return INSTANCE;
    }

    //进行中的装弹/拆弹进度：玩家 uuid -> 进度
    private final Map<UUID, BombInteractionController.BombProgress> activeProgresses = new HashMap<>();

    //自动部署失败的玩家（用于失败日志去重，成功后移除）
    private final Set<UUID> deployFailureLogged = new HashSet<>();

    //死后等待重生的玩家 -> 最近一次播报的秒数（每秒播报去重；开局等待的玩家不在此表）
    private final Map<UUID, Integer> deathCountdownLastSecond = new HashMap<>();

    //本局是否已结束（防多个结束条件重复触发结算，战斗玩法里程碑使用）
    private boolean matchEnded;

    //本局胜方与结束原因（结算展示用；由 SectorProgressController.endMatch 记录，见 setMatchEnded 的复位语义）
    private ShdfTeam winner;
    private String endReason;

    //重生倒计时 tick 驱动任务
    private ScheduledTask respawnTickTask;

    //装弹/拆弹进度驱动任务
    private ScheduledTask bombProgressTickTask;

    //已激活炸弹粒子任务
    private ScheduledTask bombParticleTask;

    //等待重生玩家的行为守卫（禁破坏/放置、禁攻击）
    private Listener guardListener;

    //战斗死亡监听器（仅 PLAYING 期间生效）
    private Listener deathListener;

    //装弹/拆弹期间的移动冻结守卫
    private Listener freezeListener;

    // ==================== 装弹/拆弹进度 ====================

    /** 进行中的装弹/拆弹进度表（可变视图，直接增删） */
    public Map<UUID, BombInteractionController.BombProgress> getActiveProgresses() {
        return activeProgresses;
    }

    public void clearActiveProgresses() {
        activeProgresses.clear();
    }

    // ==================== 部署失败日志去重 ====================

    /** 记录一次部署失败；返回 true 表示该玩家首次失败（应当打日志），false 表示已记录过（去重） */
    public boolean addDeployFailureLogged(UUID uuid) {
        return deployFailureLogged.add(uuid);
    }

    public void removeDeployFailureLogged(UUID uuid) {
        deployFailureLogged.remove(uuid);
    }

    public void clearDeployFailureLogged() {
        deployFailureLogged.clear();
    }

    // ==================== 死后重生倒计时播报去重 ====================

    public boolean hasDeathCountdown(UUID uuid) {
        return deathCountdownLastSecond.containsKey(uuid);
    }

    public Integer getDeathCountdownSeconds(UUID uuid) {
        return deathCountdownLastSecond.get(uuid);
    }

    public void putDeathCountdownSeconds(UUID uuid, int seconds) {
        deathCountdownLastSecond.put(uuid, seconds);
    }

    public void removeDeathCountdown(UUID uuid) {
        deathCountdownLastSecond.remove(uuid);
    }

    public void clearDeathCountdown() {
        deathCountdownLastSecond.clear();
    }

    // ==================== 对局结束标志 ====================

    public boolean isMatchEnded() {
        return matchEnded;
    }

    public void setMatchEnded(boolean matchEnded) {
        this.matchEnded = matchEnded;
        //跨局复位：每局开局门面都会调用 setMatchEnded(false)，此处顺带清空上一局的胜方与结束原因，
        //因此无需在门面（PlayingPhase）里额外加复位调用，也不会把上一局胜方带进下一局的结算展示。
        if(!matchEnded){
            this.winner = null;
            this.endReason = null;
        }
    }

    /**
     * 记录本局胜方与结束原因（由 {@code SectorProgressController.endMatch} 在置 {@code matchEnded=true} 的同一处写入）。
     * <p>
     * 幂等由调用方保证（endMatch 的 matchEnded 早退守卫），本方法只负责落值；结算阶段（FinishedPhase）
     * 读取它来明确报出胜方，读取必须早于 {@code TeamManager.reset()}（战绩同理）。
     */
    public void setMatchOutcome(ShdfTeam winner, String endReason) {
        this.winner = winner;
        this.endReason = endReason;
    }

    /** 本局胜方；未结束或已跨局复位时为 null */
    public ShdfTeam getWinner() {
        return winner;
    }

    /** 本局结束原因；未结束或已跨局复位时为 null */
    public String getEndReason() {
        return endReason;
    }

    // ==================== 调度任务句柄 ====================

    public ScheduledTask getRespawnTickTask() {
        return respawnTickTask;
    }

    public void setRespawnTickTask(ScheduledTask respawnTickTask) {
        this.respawnTickTask = respawnTickTask;
    }

    public ScheduledTask getBombProgressTickTask() {
        return bombProgressTickTask;
    }

    public void setBombProgressTickTask(ScheduledTask bombProgressTickTask) {
        this.bombProgressTickTask = bombProgressTickTask;
    }

    public ScheduledTask getBombParticleTask() {
        return bombParticleTask;
    }

    public void setBombParticleTask(ScheduledTask bombParticleTask) {
        this.bombParticleTask = bombParticleTask;
    }

    // ==================== 监听器句柄 ====================

    public Listener getGuardListener() {
        return guardListener;
    }

    public void setGuardListener(Listener guardListener) {
        this.guardListener = guardListener;
    }

    public Listener getDeathListener() {
        return deathListener;
    }

    public void setDeathListener(Listener deathListener) {
        this.deathListener = deathListener;
    }

    public Listener getFreezeListener() {
        return freezeListener;
    }

    public void setFreezeListener(Listener freezeListener) {
        this.freezeListener = freezeListener;
    }
}
