package com.sHDFGamePlugin.phase;

import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.core.GameState;
import com.sHDFGamePlugin.core.GameStateMachine;
import com.sHDFGamePlugin.domain.sector.SectorManager;
import com.sHDFGamePlugin.domain.spawn.SpawnManager;
import com.sHDFGamePlugin.domain.team.PlayerStatus;
import com.sHDFGamePlugin.domain.team.ShdfTeam;
import com.sHDFGamePlugin.domain.team.TeamManager;
import com.sHDFGamePlugin.domain.ticket.TicketManager;
import com.sHDFGamePlugin.infrastructure.DisconnectProtection;
import com.sHDFGamePlugin.infrastructure.RoleBridge;
import com.sHDFGamePlugin.infrastructure.config.ConfigManager;
import com.sHDFGamePlugin.infrastructure.gui.ChestGui;
import com.sHDFGamePlugin.phase.playing.MatchSessionState;
import com.sHDFGamePlugin.util.MessageUtil;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 结算阶段：对局结束后先在聊天栏展示本局战绩并停留可配时长，再集中清理并踢人回 IDLE。
 * <p>
 * 流程（顺序不可调换）：
 * <ol>
 *     <li><b>先读战绩</b>：{@link #captureStats()} 在 {@code TeamManager.reset()} <b>之前</b>把每名参战玩家的
 *     {@link PlayerStatus} 战绩快照到内存列表——{@link #resetSystemState()} 会清空全部 PlayerStatus，
 *     顺序反了就读不到任何数据；</li>
 *     <li>聊天栏展示战绩（Adventure Component，含胜方与每名参战玩家）；</li>
 *     <li>按 {@code playing.finish_display_time}（缺键回退 {@link ConfigManager#DEFAULT_FINISH_DISPLAY_TIME} tick）
 *     停留；停留期间玩家仍在服务器上，能读完战绩；</li>
 *     <li>停留结束 → 清理系统状态 → 重置在线玩家运行时状态 → 踢出全部玩家 → 回 IDLE 等待下一局。</li>
 * </ol>
 * <p>
 * 延时任务句柄存于 {@link #cleanupTask}，{@link #onExit()} 必须取消它：本阶段可能在停留期内被异常切走
 * （例如服务器关服/外部 diagnostics 切状态），不取消会留下跨局双跑的任务——本工程反复出现的残留类问题。
 */
public class FinishedPhase implements GamePhase {

    private static final FinishedPhase INSTANCE = new FinishedPhase();

    private FinishedPhase() {}

    public static FinishedPhase getInstance() {
        return INSTANCE;
    }

    /** 停留结束后的清理任务句柄；onExit 必须取消，防止跨局双跑/残留 */
    private ScheduledTask cleanupTask;

    @Override
    public void onEnter() {
        GameContext.getInstance().getPlugin().getLogger().info("进入 FINISHED 状态, 开始结算展示...");

        //1. 先在 TeamManager.reset() 之前取走本局战绩快照（reset 会清空 PlayerStatus，顺序不能反）
        List<MatchStat> stats = captureStats();
        //1.1 胜方与结束原因同样必须在 reset 之前捕获（胜方随 MatchSessionState 的 setMatchEnded(false) 跨局复位）
        ShdfTeam winner = MatchSessionState.getInstance().getWinner();
        String endReason = MatchSessionState.getInstance().getEndReason();

        //2. 聊天栏展示战绩（踢人前完成，玩家在停留期内能看到）
        broadcastStats(winner, endReason, stats);

        //3. 按配置停留后再清理+踢人（默认 200 tick = 10 秒）
        int displayTicks = ConfigManager.getInstance().getFinishDisplayTime();
        cleanupTask = GameContext.getInstance().getPlugin().getServer().getGlobalRegionScheduler()
                .runDelayed(GameContext.getInstance().getPlugin(),
                        scheduledTask -> {
                            //守卫：停留期内状态可能已被别的路径切走，此时不再清理/踢人
                            if(GameStateMachine.getInstance().getCurrentState() != GameState.FINISHED){
                                return;
                            }
                            finishAndReset();
                        },
                        Math.max(1, displayTicks));
    }

    /**
     * 阶段退出：取消停留期的清理任务。
     * <p>
     * 这是本阶段唯一需要清理的资源（本阶段不订阅事件、不注册物品/监听器）。取消后即使玩家在停留期内
     * 被别的路径带离 FINISHED，也不会再有一次跨局的"清理 + 踢人 + 回 IDLE"执行。
     */
    @Override
    public void onExit() {
        if(cleanupTask != null){
            cleanupTask.cancel();
            cleanupTask = null;
        }
    }

    // ==================== 战绩快照与展示 ====================

    /**
     * 在 {@code TeamManager.reset()} 之前取走本局战绩快照。
     * <p>
     * 观战者/非参战方：其 PlayerStatus 的四个统计恒为 0，仍会被展示（保持"每名参战玩家都有统计"的口径，
     * 也便于核对"谁没参战"）。中途离开（离线）的玩家：仍保留在快照中并按内存记录展示——战绩本就只存内存，
     * 且离线玩家无法收消息，不影响在线玩家看到完整战绩。
     */
    private List<MatchStat> captureStats(){
        List<MatchStat> stats = new ArrayList<>();
        for(PlayerStatus status : TeamManager.getInstance().getAllPlayerStatuses()){
            stats.add(new MatchStat(status.getUuid(), status.getTeam(),
                    status.getKills(), status.getDeaths(), status.getBombsPlanted(), status.getBombsDefused()));
        }
        return stats;
    }

    /**
     * 聊天栏展示结算战绩：明确报出胜方与结束原因 + 逐名玩家一行统计。
     * <p>
     * 胜方与原因取自在 {@code TeamManager.reset()} 之前捕获的快照（来自
     * {@code SectorProgressController.endMatch} 写入的 {@link MatchSessionState} 结果）；
     * 若对局未经正常胜负判定结束（胜方未被记录），退化为中性表述并说明原因，不误报胜方。
     */
    private void broadcastStats(ShdfTeam winner, String endReason, List<MatchStat> stats){
        if(winner != null){
            Component header = Component.text("对局结束! ", NamedTextColor.GOLD, TextDecoration.BOLD)
                    .append(Component.text(teamDisplayName(winner) + " 获胜", NamedTextColor.GOLD, TextDecoration.BOLD));
            if(endReason != null && !endReason.isEmpty()){
                header = header.append(Component.text(" (" + endReason + ")", NamedTextColor.YELLOW));
            }
            MessageUtil.broadcastPrefixedMessage(header);
        }
        else{
            MessageUtil.broadcastPrefixedMessage(Component.text(
                    "对局结束! 胜方未记录(对局未经正常胜负判定结束)",
                    NamedTextColor.GOLD, TextDecoration.BOLD));
        }

        if(stats.isEmpty()){
            MessageUtil.broadcastPrefixedMessage(Component.text("本场没有参战玩家的战绩记录", NamedTextColor.GRAY));
            return;
        }

        MessageUtil.broadcastPrefixedMessage(Component.text("—— 本场战绩 ——", NamedTextColor.YELLOW, TextDecoration.BOLD));
        for(MatchStat stat : stats){
            MessageUtil.broadcastPrefixedMessage(
                    Component.text(playerName(stat.uuid()), NamedTextColor.AQUA)
                            .append(Component.text(" [" + teamDisplayName(stat.team()) + "]", NamedTextColor.GRAY))
                            .append(Component.text(" 击杀 " + stat.kills(), NamedTextColor.GREEN))
                            .append(Component.text(" 死亡 " + stat.deaths(), NamedTextColor.RED))
                            .append(Component.text(" 安放 " + stat.bombsPlanted(), NamedTextColor.GOLD))
                            .append(Component.text(" 拆除 " + stat.bombsDefused(), NamedTextColor.BLUE)));
        }
    }

    /** 玩家名：在线用当前名，离线（中途离开）回退 uuid 前 8 位，保证展示不抛异常 */
    private static String playerName(UUID uuid){
        Player player = Bukkit.getPlayer(uuid);
        if(player != null){
            return player.getName();
        }
        String raw = uuid.toString();
        return raw.substring(0, Math.min(8, raw.length())) + "(已离线)";
    }

    private static String teamDisplayName(ShdfTeam team){
        if(team == null) return "未知阵营";
        if(team == ShdfTeam.ATTACKER) return "进攻方";
        if(team == ShdfTeam.DEFENDER) return "防守方";
        if(team == ShdfTeam.SPECTATOR) return "观战者";
        return "随机阵营";
    }

    // ==================== 停留结束：清理 + 踢人 + 回 IDLE ====================

    /** 停留结束后的收尾：清理系统状态 → 重置在线玩家 → 踢人 → 回 IDLE */
    private void finishAndReset(){
        //1. 重置对局机制与管理器状态（防止跨局残留）
        resetSystemState();
        //2. 重置在线玩家的运行时状态（清背包/状态等，避免旧局物品带入下一局）
        resetOnlinePlayers();
        //3. 重置完成后，将所有玩家踢出游戏
        kickAllPlayers();
        //4. 回到 IDLE，等待玩家重新加入开新局
        GameStateMachine.getInstance().transitionTo(GameState.IDLE);
    }

    /** 集中清理本局产生的所有系统状态 */
    private void resetSystemState(){
        //据点/炸弹：停止引信与据点时限任务
        SectorManager.getInstance().cleanup();
        //队伍/玩家状态：清空全部 PlayerStatus（含 selectedRoleId / state / 本局战绩）
        TeamManager.getInstance().reset();
        //重生队列
        SpawnManager.getInstance().clearAll();
        //票数
        TicketManager.getInstance().reset();
        //角色占用记录清空，重复规则还原为配置默认值
        RoleBridge.getInstance().clearAllOccupiedRoles();
        RoleBridge.getInstance().setAllowDuplicateRoles(ConfigManager.getInstance().isAllowDuplicateRoles());
        //取消所有挂起的断线保护任务（防止残留回调跨局触发）
        DisconnectProtection.getInstance().cancelAll();
        //关闭所有打开的游戏 GUI
        ChestGui.closeAllGuis();
    }

    /** 重置每个在线玩家的运行时状态（背包/经验/状态效果/游戏模式等） */
    private void resetOnlinePlayers(){
        for(Player player : Bukkit.getOnlinePlayers()){
            player.getInventory().clear();
            player.getEnderChest().clear();
            player.setHealth(player.getMaxHealth());
            player.setFoodLevel(20);
            player.setSaturation(5f);
            player.setLevel(0);
            player.setExp(0f);
            player.setFireTicks(0);
            player.setFallDistance(0);
            player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
            player.setGameMode(GameMode.ADVENTURE);
            //复位等待重生/死亡可能遗留的碰撞状态（隐身效果随上方"清除全部药水效果"一并移除）
            player.setCollidable(true);
            player.closeInventory();
        }
    }

    /** 将所有在线玩家踢出服务器（本阶段未订阅 quit 事件，踢出不触发任何保留逻辑） */
    private void kickAllPlayers(){
        for(Player player : new ArrayList<>(Bukkit.getOnlinePlayers())){
            player.kick(Component.text("✔\n", NamedTextColor.GREEN, TextDecoration.BOLD)
                    .append(Component.text("本场对局已经结束, 插件正在执行清理工作\n", NamedTextColor.GREEN, TextDecoration.BOLD))
                    .append(Component.text("请重新加入服务器, 来加入新的对局!", NamedTextColor.GREEN, TextDecoration.BOLD)));
        }
    }

    /** 单名玩家的战绩快照（在 TeamManager.reset() 之前取，之后只读这份内存数据） */
    private record MatchStat(UUID uuid, ShdfTeam team, int kills, int deaths, int bombsPlanted, int bombsDefused) {}
}
