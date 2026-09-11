package com.sHDFGamePlugin.phase.playing;

import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.domain.spawn.SpawnManager;
import com.sHDFGamePlugin.domain.team.PlayerState;
import com.sHDFGamePlugin.domain.team.PlayerStatus;
import com.sHDFGamePlugin.domain.team.ShdfTeam;
import com.sHDFGamePlugin.domain.team.TeamManager;
import com.sHDFGamePlugin.domain.ticket.TicketManager;
import com.sHDFGamePlugin.infrastructure.RoleBridge;
import com.sHDFGamePlugin.util.MessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.time.Duration;
import java.util.UUID;

/**
 * 死亡接管模块：仅处理 IN_BATTLE 参战玩家的真实死亡。
 * <p>
 * 流程（与拆分前一致）：死亡中断正在进行的装弹/拆弹 → 取消原版死亡（不掉落、不出现死亡界面、
 * 停留原地不传送）→ 死亡瞬间广播击杀信息（{@link #buildKillMessage(Player)}，经
 * {@code RoleBridge.getLastDamagerUuid} 定位击杀者，勿用原版 getKiller）→ 受害者"你死了！"标题
 * → 进攻方扣 1 票（{@code TicketDepletedEvent} 由 t20 的 SectorProgressController 接听）
 * → 释放角色占用（重新部署时再应用）→ 原地转等待重生（创造隐身，不传送）+ DEPLOYING + 进重生队列
 * → 标记"死后等待"以开始每秒播报（见 {@link DeploymentController#sendDeathCountdownMessages()}）。
 * <p>
 * 监听器随阶段注册/注销（{@code PlayingPhase.onEnter/onExit}），句柄存于 {@link MatchSessionState}。
 */
public final class DeathHandler {

    private static final DeathHandler INSTANCE = new DeathHandler();

    private DeathHandler() {}

    public static DeathHandler getInstance() {
        return INSTANCE;
    }

    /**
     * 记录本局战绩：击杀归属 + 本方死亡。
     * <p>
     * 击杀者必须经 {@link RoleBridge#getLastDamagerUuid(org.bukkit.entity.LivingEntity)} 获取
     * （角色插件伤害做过特殊处理，勿用原版 {@code getKiller}）；且仅当击杀者是在线参战玩家、
     * 且与受害者不同队时计入击杀（避免误伤/自杀刷击杀）。受害者死亡数无条件 +1。
     * <p>
     * 战绩为内存态（{@link PlayerStatus}），随本局结束与 {@code TeamManager.reset()} 一起清空。
     */
    private void recordKillAndDeath(Player victim, PlayerStatus victimStatus){
        victimStatus.addDeath();

        UUID killerUuid = RoleBridge.getInstance().getLastDamagerUuid(victim);
        if(killerUuid == null || killerUuid.equals(victim.getUniqueId())) return;

        Player killer = Bukkit.getPlayer(killerUuid);
        if(killer == null) return;

        PlayerStatus killerStatus = TeamManager.getInstance().getPlayerStatus(killerUuid);
        if(killerStatus == null || killerStatus.getTeam() == null) return;
        if(!killerStatus.getTeam().isCombatant()) return;
        //同队击杀（误伤）不计入击杀数
        if(killerStatus.getTeam() == victimStatus.getTeam()) return;

        killerStatus.addKill();
    }

    /** 构建击杀信息：经 RoleAPI.getLastDamagerUuid 定位击杀者（勿用原版 getKiller）；无击杀者则报"阵亡" */
    public Component buildKillMessage(Player victim){
        UUID killerUuid = RoleBridge.getInstance().getLastDamagerUuid(victim);
        Player killer = killerUuid == null ? null : Bukkit.getPlayer(killerUuid);
        if(killer != null && killer != victim){
            PlayerStatus killerStatus = TeamManager.getInstance().getPlayerStatus(killerUuid);
            if(killerStatus != null && killerStatus.getTeam() != null && killerStatus.getTeam().isCombatant()){
                return Component.text(victim.getName() + " 被 " + killer.getName() + " 击杀了", NamedTextColor.GRAY);
            }
        }
        return Component.text(victim.getName() + " 阵亡了", NamedTextColor.GRAY);
    }

    /** 死亡处理：仅处理 IN_BATTLE 参战玩家；取消原版死亡以保持原地（不传送），转入等待重生流程 */
    public void handlePlayerDeath(PlayerDeathEvent event){
        Player victim = event.getEntity();
        MatchSessionState state = MatchSessionState.getInstance();
        if(state.isMatchEnded()) return;

        PlayerStatus status = TeamManager.getInstance().getPlayerStatus(victim.getUniqueId());
        if(status == null || status.getTeam() == null || !status.getTeam().isCombatant()) return;
        if(status.getState() != PlayerState.IN_BATTLE) return;

        //本局战绩：击杀归属（RoleBridge.getLastDamagerUuid，勿用原版 getKiller）与本方死亡
        recordKillAndDeath(victim, status);

        //死亡中断正在进行的装弹/拆弹
        state.getActiveProgresses().remove(victim.getUniqueId());

        //取消原版死亡：不掉落物品、不出现死亡界面，玩家停留在死亡位置（不传送）
        event.setCancelled(true);
        victim.setHealth(victim.getMaxHealth());
        victim.setFireTicks(0);

        //死亡瞬间广播击杀信息
        MessageUtil.broadcastPrefixedMessage(buildKillMessage(victim));

        //受害者死亡标题
        victim.showTitle(Title.title(
                Component.text("你死了！", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("等待重新部署", NamedTextColor.RED),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ofMillis(500))));

        //进攻方死亡扣 1 票（耗尽时发布 TicketDepletedEvent，对局结束判定由 SectorProgressController 接听）
        if(status.getTeam() == ShdfTeam.ATTACKER){
            TicketManager.getInstance().decreaseTicket(1);
        }

        //死亡释放角色占用，重新部署时再重新应用（避免角色插件已清空角色但本地占用表残留导致重部署失败）
        RoleBridge.getInstance().clearPlayerRole(victim.getUniqueId());

        //原地转为等待重生（创造隐身，不传送）+ DEPLOYING + 进重生队列
        DeploymentController.getInstance().setAwaitingLook(victim);
        status.setState(PlayerState.DEPLOYING);
        SpawnManager.getInstance().addPlayer(victim.getUniqueId(), status.getTeam());
        //标记为"死后等待"，开始每秒播报重新部署倒计时
        state.putDeathCountdownSeconds(victim.getUniqueId(), -1);
    }

    /** 战斗死亡监听器（随阶段注册/注销） */
    private static class CombatDeathListener implements Listener {
        @EventHandler
        public void onPlayerDeath(PlayerDeathEvent event){
            DeathHandler.getInstance().handlePlayerDeath(event);
        }
    }

    public void register(){
        MatchSessionState state = MatchSessionState.getInstance();
        Listener deathListener = new CombatDeathListener();
        Bukkit.getPluginManager().registerEvents(deathListener, GameContext.getInstance().getPlugin());
        state.setDeathListener(deathListener);
    }

    public void unregister(){
        MatchSessionState state = MatchSessionState.getInstance();
        Listener deathListener = state.getDeathListener();
        if(deathListener != null){
            for(HandlerList handlerList : HandlerList.getHandlerLists()){
                handlerList.unregister(deathListener);
            }
            state.setDeathListener(null);
        }
    }
}
