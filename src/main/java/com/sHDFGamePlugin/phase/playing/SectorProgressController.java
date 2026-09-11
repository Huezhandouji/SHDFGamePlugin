package com.sHDFGamePlugin.phase.playing;

import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.core.GameState;
import com.sHDFGamePlugin.core.GameStateMachine;
import com.sHDFGamePlugin.domain.sector.Sector;
import com.sHDFGamePlugin.domain.sector.SectorManager;
import com.sHDFGamePlugin.domain.team.ShdfTeam;
import com.sHDFGamePlugin.domain.ticket.TicketManager;
import com.sHDFGamePlugin.infrastructure.GameEventBus;
import com.sHDFGamePlugin.infrastructure.event.BombExplodedEvent;
import com.sHDFGamePlugin.infrastructure.event.SectorTimeLimitExpiredEvent;
import com.sHDFGamePlugin.infrastructure.event.TicketDepletedEvent;
import com.sHDFGamePlugin.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * 对局闭环模块：据点推进与胜负判定。
 * <p>
 * 订阅三个此前"只发布、零订阅"的事件（订阅/退订由门面 {@code PlayingPhase.onEnter/onExit} 成对调用）：
 * <ul>
 *     <li>{@link BombExplodedEvent} —— 当前据点全部炸弹爆炸 → 广播攻占 + {@code increaseTicket(该据点 ticket_reward)}
 *     + {@link SectorManager#advanceToNextSector()}；**推进后不做全员重部署**（已拍板），只有之后新死亡的玩家
 *     才按新据点出生区部署；若推进后已无后续据点（全据点攻占）→ 进攻方胜。</li>
 *     <li>{@link TicketDepletedEvent} —— 票数耗尽 → 防守方胜。</li>
 *     <li>{@link SectorTimeLimitExpiredEvent} —— 据点时限到期 → 防守方胜。</li>
 * </ul>
 * 三条路径统一走 {@link #endMatch(ShdfTeam, String)}：置 {@code MatchSessionState.matchEnded=true}（幂等，
 * 同一局最多生效一次）并在<b>下一 tick</b> {@code transitionTo(GameState.FINISHED)}（延迟原因见该方法注释）。
 * 结算展示由后续里程碑负责；
 * {@code FinishedPhase} 现有"立即清理 + 踢人 + 回 IDLE"行为保持不变，从而对局可稳定重开。
 * <p>
 * 票数钳制（{@code increaseTicket} 上限 {@code maxTickets}、{@code decreaseTicket} 钳到 0）由
 * {@link TicketManager} 实现，本类不重复实现。
 */
public final class SectorProgressController {

    private static final SectorProgressController INSTANCE = new SectorProgressController();

    private SectorProgressController() {}

    public static SectorProgressController getInstance() {
        return INSTANCE;
    }

    //事件订阅
    private GameEventBus.Subscription bombExplodedSubscription;
    private GameEventBus.Subscription ticketDepletedSubscription;
    private GameEventBus.Subscription sectorTimeLimitExpiredSubscription;

    /** 订阅对局闭环事件（由门面 onEnter 调用） */
    public void subscribe(){
        bombExplodedSubscription = GameEventBus.subscribe(BombExplodedEvent.class, this::handleBombExploded);
        ticketDepletedSubscription = GameEventBus.subscribe(TicketDepletedEvent.class, this::handleTicketDepleted);
        sectorTimeLimitExpiredSubscription = GameEventBus.subscribe(SectorTimeLimitExpiredEvent.class, this::handleSectorTimeLimitExpired);
    }

    /** 退订全部订阅（由门面 onExit 调用；沿用既有 Subscription 风格、有空判） */
    public void unsubscribe(){
        if(bombExplodedSubscription != null){
            bombExplodedSubscription.unsubscribe();
            bombExplodedSubscription = null;
        }
        if(ticketDepletedSubscription != null){
            ticketDepletedSubscription.unsubscribe();
            ticketDepletedSubscription = null;
        }
        if(sectorTimeLimitExpiredSubscription != null){
            sectorTimeLimitExpiredSubscription.unsubscribe();
            sectorTimeLimitExpiredSubscription = null;
        }
    }

    // ==================== 据点推进 ====================

    /**
     * 炸弹爆炸：当前据点全部炸弹爆炸 → 广播 + 加票 + 推进据点；推进后已无后续据点则进攻方胜。
     * <p>
     * 取值时机：{@code advanceToNextSector()} 之后 {@code getCurrentSector()} 已变为下一个据点，
     * 因此据点对象与 {@code ticket_reward} 必须在推进前取。
     * <p>
     * 推进后不做全员重部署（已拍板）：只有之后新死亡的玩家才按新据点出生区部署。
     */
    private void handleBombExploded(BombExplodedEvent event){
        if(MatchSessionState.getInstance().isMatchEnded()) return;

        SectorManager sectorManager = SectorManager.getInstance();
        //陈旧事件防御：只有属于当前据点的炸弹爆炸才参与推进判定
        if(event.getSector() != sectorManager.getCurrentSector()) return;
        if(!sectorManager.isAllBombsExploded()) return;

        //推进前取据点与奖励（推进后 getCurrentSector() 已变）
        Sector capturedSector = sectorManager.getCurrentSector();
        if(capturedSector == null) return;
        int ticketReward = capturedSector.getTicketReward();

        MessageUtil.broadcastPrefixedMessage(
                Component.text("据点 ", NamedTextColor.GOLD)
                        .append(capturedSector.getName())
                        .append(Component.text(" 已被攻占, 进攻方获得 " + ticketReward + " 票!", NamedTextColor.GOLD)));

        //加票（上限钳制由 TicketManager 实现）
        TicketManager.getInstance().increaseTicket(ticketReward);

        //推进到下一个据点（不做全员重部署）
        sectorManager.advanceToNextSector();

        //已无后续据点 → 全据点攻占，进攻方胜
        if(sectorManager.isAllCaptured()){
            endMatch(ShdfTeam.ATTACKER, "全部据点已被攻占");
        }
    }

    // ==================== 胜负判定 ====================

    /**
     * 票数耗尽 → 防守方胜——但**延后一 tick 判定**。
     * <p>
     * 事实窗口（含 1 tick 宽限）：票尽事件与炸弹爆炸事件在同一 tick 内的<b>处理顺序不确定</b>，因此
     * "同 tick 双条件"的判定实际发生在 <b>tick N 与 N+1 之间</b>——防守方在 tick N 内只登记"本次需要判定"
     * （挂一个下一 tick 的任务），真正的 endMatch 发生在 N+1。宽限 1 tick 的语义是：<b>只要进攻方在 N+1 开始
     * 之前（即 tick N 结束前）完成全据点攻占</b>，其 endMatch 已把 matchEnded 置为 true，防守方的延后判定
     * 就会因 {@code !isMatchEnded()} 不成立而空转 → 进攻方胜；否则延后判定生效 → 防守方胜（仅晚约 50ms，
     * 玩家不可感知）。注意顺序不确定并不意味着结果不确定：两条顺序都会收敛到"进攻方优先"（见 deferDefenderVictory）。
     * <p>
     * {@code decreaseTicket} 在已归零后可能再次发布本事件，判定幂等可兜住。
     */
    private void handleTicketDepleted(TicketDepletedEvent event){
        deferDefenderVictory(ShdfTeam.DEFENDER, "进攻方票数耗尽");
    }

    /**
     * 据点时限到期 → 防守方胜——同样**延后一 tick 判定**（与票尽同因：给进攻方在 tick N 结束前完成
     * 全据点攻占留出机会；真实判定窗口见 {@link #deferDefenderVictory}）。
     * 陈旧事件（不属于当前据点）在本 tick 内立即忽略，不占用延后判定。
     */
    private void handleSectorTimeLimitExpired(SectorTimeLimitExpiredEvent event){
        if(MatchSessionState.getInstance().isMatchEnded()) return;
        if(event.getSector() != SectorManager.getInstance().getCurrentSector()) return;
        deferDefenderVictory(ShdfTeam.DEFENDER, "据点时限到期, 进攻方未能攻占");
    }

    /**
     * 防守方胜利的延后判定：下一 tick 再看当前状态是否仍可结算。
     * <p>
     * 双重校验：{@code getCurrentState() == PLAYING}（防止状态已被空服清理等路径切走，与 t35 的守卫同一口径）
     * 且 {@code !isMatchEnded()}（若进攻方在 tick N 结束前已完成全据点攻占，其 endMatch 已置位 matchEnded → 这里空转）。
     * 这样"同 tick 双条件"最终判定为进攻方胜。
     * <p>
     * <b>真实窗口与未覆盖的边角（事实描述，非承诺）</b>：
     * <ol>
     *     <li>tick N 内 {@code matchEnded} 仍为 false（防守方只是挂了本任务，没有置位），因此该 tick 内
     *     各处理器<b>不会短路</b>——"短路"只发生在 endMatch 置位之后到 transition 之前的那段窗口；</li>
     *     <li>若 tick N 内还有玩家死亡，死亡流程会照常再扣票，并可能再挂一个延后判定任务；</li>
     *     <li>这些写入与任务最终都会被"transition 之后的 PlayingPhase.onExit"与 FinishedPhase.resetSystemState
     *     清理，且因 endMatch 幂等（matchEnded 早退）不会重复结算——即 ① 与 ② 不会造成跨局残留或双结算。</li>
     * </ol>
     * 本方法沿用本工程既有"延迟一 tick 让触发方先收尾"模式的最小增量，未引入新机制、未新增字段；
     * 把窗口收紧为"严格同 tick"的方案（在 MatchSessionState 记录防守事件触发 tick）已被否决：
     * 机制显著复杂化、收益仅限那 50ms 窗口。
     */
    private void deferDefenderVictory(ShdfTeam winner, String reason){
        GameContext.getInstance().getPlugin().getServer().getGlobalRegionScheduler()
                .run(GameContext.getInstance().getPlugin(),
                        scheduledTask -> {
                            if(GameStateMachine.getInstance().getCurrentState() == GameState.PLAYING
                                    && !MatchSessionState.getInstance().isMatchEnded()){
                                endMatch(winner, reason);
                            }
                        });
    }

    /**
     * 结束对局（幂等：同一局内最多生效一次）：置 {@code matchEnded} → 广播胜方 → 下一 tick 切到 FINISHED。
     * <p>
     * <b>为什么延迟一 tick 再切状态</b>：本方法可能从其它模块的事件回调中被调用——票尽路径的
     * {@code TicketDepletedEvent} 是在 {@code DeathHandler} 扣票时同步发布的，而死亡流程在扣票之后
     * 还有多步写操作（清角色占用、转等待重生、进重生队列、标记死亡倒计时）。若在此<b>同步</b>
     * {@code transitionTo(FINISHED)}，会先跑完 PlayingPhase.onExit 与 FinishedPhase 的"清理 + 踢人 + 回 IDLE"，
     * 随后死亡流程后半段又在已结束的对局上写入隐身/创造/重生队列/倒计时，造成跨局残留（本工程历史事故类型）。
     * 延迟后由死亡流程先自然结束，再统一清理；{@code matchEnded} 在置位之后到 {@code transitionTo(FINISHED)} 执行之前
     * 的窗口内让各处理器（死亡/装弹拆弹/右键/据点推进）短路，因此延后一个 tick 是安全的。
     * <p>
     * 另外，{@code endMatch} 出参（胜方与原因）只以第一次生效的为准：防守方延后任务若在进攻方已 endMatch
     * 之后才跑，会因 {@code isMatchEnded()} 早退而不会覆盖胜方（见 {@link MatchSessionState#setMatchOutcome}）。
     * <p>
     * 切状态时先触发门面 {@code PlayingPhase.onExit}（退订本模块事件、停任务、清理），
     * 再进入 {@code FinishedPhase}（清理 + 踢人 + 回 IDLE），从而支持连续开局。
     */
    public void endMatch(ShdfTeam winner, String reason){
        MatchSessionState state = MatchSessionState.getInstance();
        if(state.isMatchEnded()) return;
        state.setMatchEnded(true);
        //同一处把胜方与结束原因落进共享状态：供结算阶段（FinishedPhase）明确报出胜方；
        //本行位于 matchEnded 早退守卫之后，因此同一局只写一次、重复触发不会覆盖。
        state.setMatchOutcome(winner, reason);

        GameContext.getInstance().getPlugin().getLogger().info(
                "[SectorProgressController] 对局结束, 胜方=" + teamDisplayName(winner)
                        + "(" + winner.name() + "), 原因=" + reason);
        MessageUtil.broadcastPrefixedMessage(Component.text(
                "对局结束! " + teamDisplayName(winner) + "获胜 (" + reason + ")",
                NamedTextColor.GOLD, TextDecoration.BOLD));

        deferMatchFinish();
    }

    /**
     * 下一 tick 再切 FINISHED（含状态守卫）。
     * <p>
     * <b>为什么延迟一 tick 再切状态</b>：endMatch 可能从其它模块的事件回调中被调用——票尽路径的
     * {@code TicketDepletedEvent} 是在 {@code DeathHandler} 扣票时同步发布的，而死亡流程在扣票之后
     * 还有多步写操作（清角色占用、转等待重生、进重生队列、标记死亡倒计时）。若<b>同步</b>
     * {@code transitionTo(FINISHED)}，会先跑完 PlayingPhase.onExit 与 FinishedPhase 的"清理 + 踢人 + 回 IDLE"，
     * 随后死亡流程后半段又在已结束的对局上写入隐身/创造/重生队列/倒计时，造成跨局残留（本工程历史事故类型）。
     * 延迟后由死亡流程先自然结束，再统一清理；{@code matchEnded} 在置位之后到 {@code transitionTo(FINISHED)} 执行之前
     * 的窗口内让各处理器（死亡/装弹拆弹/右键/据点推进）短路，因此延后一个 tick 是安全的。
     * 该窗口<b>不</b>包含"防守方只挂了延后判定、尚未 endMatch"的 tick N——那时 matchEnded 仍为 false。
     * <p>
     * 切状态时先触发门面 {@code PlayingPhase.onExit}（退订本模块事件、停任务、清理），
     * 再进入 {@code FinishedPhase}（清理 + 踢人 + 回 IDLE），从而支持连续开局。
     */
    private void deferMatchFinish(){
        //下一 tick 再切状态：保证触发本方法的调用方（尤其是死亡流程）先完整收尾，避免清理后写入造成跨局残留
        //状态守卫：延后的一 tick 内状态可能已被别的路径切走（如最后一名玩家退出走空服清理回 IDLE，
        //或本局已被其它触发源结束）；此时不得再切 FINISHED，否则会把已离开 PLAYING 的状态机再拉回结算。
        GameContext.getInstance().getPlugin().getServer().getGlobalRegionScheduler()
                .run(GameContext.getInstance().getPlugin(),
                        scheduledTask -> {
                            if(GameStateMachine.getInstance().getCurrentState() == GameState.PLAYING
                                    && MatchSessionState.getInstance().isMatchEnded()){
                                GameStateMachine.getInstance().transitionTo(GameState.FINISHED);
                            }
                        });
    }

    private static String teamDisplayName(ShdfTeam team){
        if(team == ShdfTeam.ATTACKER) return "进攻方";
        if(team == ShdfTeam.DEFENDER) return "防守方";
        return team.name();
    }
}
