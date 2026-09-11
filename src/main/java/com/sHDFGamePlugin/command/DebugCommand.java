package com.sHDFGamePlugin.command;

import com.sHDFGamePlugin.core.GameState;
import com.sHDFGamePlugin.core.GameStateMachine;
import com.sHDFGamePlugin.domain.sector.ActiveBomb;
import com.sHDFGamePlugin.domain.sector.BombState;
import com.sHDFGamePlugin.domain.sector.Sector;
import com.sHDFGamePlugin.domain.sector.SectorManager;
import com.sHDFGamePlugin.domain.team.ShdfTeam;
import com.sHDFGamePlugin.domain.ticket.TicketManager;
import com.sHDFGamePlugin.infrastructure.GameEventBus;
import com.sHDFGamePlugin.infrastructure.event.BombExplodedEvent;
import com.sHDFGamePlugin.phase.playing.IntermissionController;
import com.sHDFGamePlugin.phase.playing.MatchSessionState;
import com.sHDFGamePlugin.phase.playing.SectorProgressController;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 子指令 debug：对局调试工具（验证据点推进/票尽/据点超时/间歇期用）。
 * <p>
 * 用法（{@code <topic> <action> [args]}）：
 * <ul>
 *     <li>{@code sg debug sector info} —— 查看当前据点、炸弹状态、时限与区域推进间隔；</li>
 *     <li>{@code sg debug sector next} —— 跳到下一个据点（推进后<b>立即开启</b>该据点时限，不进入间歇期）；</li>
 *     <li>{@code sg debug sector capture} —— 把当前据点所有炸弹按<b>真实事件路径</b>引爆（覆盖真实闭环 + 间歇期）；</li>
 *     <li>{@code sg debug ticket info | set &lt;n&gt; | add &lt;n&gt;} —— 查看/修改进攻方票数；</li>
 *     <li>{@code sg debug bomb list | set &lt;id&gt; &lt;UNPLANTED|PLANTED|EXPLODED&gt;} —— 查看/设置炸弹状态；</li>
 *     <li>{@code sg debug intermission info | skip} —— 查看剩余/提前结束间歇期；</li>
 *     <li>{@code sg debug match end &lt;attacker|defender&gt; [原因]} —— 强制结束对局（走 public endMatch）。</li>
 * </ul>
 * <p>
 * <b>权限</b>：查看类需要 {@code shadowhunter.game.debug}；修改类需要 {@code shadowhunter.game.debug.admin}。
 * 两个权限都未在 plugin.yml 声明（resources 不在本任务 inScope），因此按 Bukkit 对未声明权限的默认值 = 仅 OP；
 * 需要授予非 OP 时由服务器权限插件显式添加即可。
 * <p>
 * <b>状态一致性</b>：票数只走 {@link TicketManager} 既有接口（保留 maxTickets 上限与"扣到 0 才发布票尽事件"的语义）；
 * 据点推进只走 {@link SectorManager} 既有接口；炸弹状态切换不绕过事件发布——PLANTED/UNPLANTED 走
 * {@code onBombPlantSuccess/onBombDefuseSuccess}，EXPLODED 走"标记状态 + 发布真实 {@link BombExplodedEvent}"，
 * 与引信任务归零时发布的事件完全同源，避免出现"状态改了但事件没发"的假象。
 * <p>
 * <b>两条判读前提（重要，验证时请一并参考）</b>：
 * <ol>
 *     <li>手工跳据点（{@code sector next}）<b>不会进入间歇期</b>——间歇期只由真实炸弹爆炸事件触发；
 *     跳完必须自行开启时限（本指令已代为调用 {@code openCurrentSector()}），否则该据点会停在"已激活未开启"、
 *     {@code currentTimeLimit == null}，导致"据点超时=防守方胜"这条在该段无法被验证。</li>
 *     <li>跳到<b>最后一个</b>据点不会置 {@code allCaptured}（只有继续往后推进才会），因此手工跳据点
 *     <b>不会误触发"全据点攻占=进攻方胜"</b>；要验证该胜负路径请用 {@code sector capture}（真实路径）或
 *     {@code match end attacker}。</li>
 * </ol>
 */
public class DebugCommand implements SubCommand {

    /** 查看类权限（未声明 → 默认仅 OP） */
    private static final String PERMISSION_VIEW = "shadowhunter.game.debug";
    /** 修改类权限（未声明 → 默认仅 OP） */
    private static final String PERMISSION_ADMIN = "shadowhunter.game.debug.admin";

    private static final List<String> TOPICS = List.of("sector", "ticket", "bomb", "intermission", "match");
    private static final List<String> BOMB_STATES = List.of("UNPLANTED", "PLANTED", "EXPLODED");
    private static final List<String> WINNERS = List.of("attacker", "defender");

    @Override
    public String getName() {
        return "debug";
    }

    @Override
    public String getUsage() {
        return "debug <sector|ticket|bomb|intermission|match> ...";
    }

    // ==================== 入口 ====================

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            printUsage(sender);
            return true;
        }

        String topic = args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (topic) {
            case "sector" -> handleSector(sender, rest);
            case "ticket" -> handleTicket(sender, rest);
            case "bomb" -> handleBomb(sender, rest);
            case "intermission" -> handleIntermission(sender, rest);
            case "match" -> handleMatch(sender, rest);
            default -> {
                replyError(sender, "未知的 debug 子话题: " + args[0] + " (可用: " + String.join(", ", TOPICS) + ")");
                printUsage(sender);
            }
        }
        return true;
    }

    // ==================== sector ====================

    private void handleSector(CommandSender sender, String[] args) {
        String action = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "info" -> sectorInfo(sender);
            case "next" -> sectorNext(sender);
            case "capture" -> sectorCapture(sender);
            default -> replyError(sender, "未知的 sector 子指令: " + args[0] + " (可用: info, next, capture)");
        }
    }

    private void sectorInfo(CommandSender sender) {
        if (!requirePermission(sender, PERMISSION_VIEW) || !requirePlaying(sender)) return;

        SectorManager sectorManager = SectorManager.getInstance();
        Sector sector = sectorManager.getCurrentSector();
        if (sector == null) {
            replyError(sender, "当前没有已加载的据点 (maps.yml objective 未加载)。");
            return;
        }

        sender.sendMessage(Component.text("[sg] 当前据点: ", NamedTextColor.GREEN).append(sector.getName())
                .append(Component.text(" (id=" + sector.getId() + ")", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("[sg] 时限剩余: " + sectorManager.getCurrentTimeLimitRemaining()
                + " tick / 本据点 time_limit=" + sector.getTimeLimit()
                + (sectorManager.isCurrentSectorOpen() ? " (已开启)" : " (未开启: 间歇期或异常)"), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("[sg] 区域推进间隔: " + sectorManager.getSectorAdvanceInterval()
                + " tick; 是否已全据点攻占: " + sectorManager.isAllCaptured()
                + "; 是否还有下一个据点: " + sectorManager.hasNextSector()
                + "; 当前据点全部炸弹已爆炸: " + sectorManager.isAllBombsExploded(), NamedTextColor.GRAY));

        List<ActiveBomb> bombs = sectorManager.getActiveBombs();
        if (bombs.isEmpty()) {
            sender.sendMessage(Component.text("[sg] 本据点无运行时炸弹。", NamedTextColor.GRAY));
            return;
        }
        for (ActiveBomb bomb : bombs) {
            sender.sendMessage(Component.text("[sg]  炸弹 " + bomb.getId(), NamedTextColor.GRAY)
                    .append(Component.text(" 状态=" + bomb.getState(), NamedTextColor.YELLOW))
                    .append(Component.text(" 引信=" + bomb.getFuseRemaining() + " tick", NamedTextColor.GRAY)));
        }
    }

    /**
     * 跳到下一个据点：走 {@link SectorManager} 既有接口，推进后<b>立即开启</b>该据点时限。
     * <p>
     * 手工跳不会进入间歇期（间歇期只由真实炸弹爆炸事件触发），若不补 {@code openCurrentSector()}，
     * 新据点会停在"已激活未开启"、{@code currentTimeLimit == null}，"据点超时=防守方胜"在该段将无法被验证。
     */
    private void sectorNext(CommandSender sender) {
        if (!requirePermission(sender, PERMISSION_ADMIN) || !requirePlaying(sender)) return;

        SectorManager sectorManager = SectorManager.getInstance();
        if (sectorManager.getCurrentSector() == null) {
            replyError(sender, "当前没有已加载的据点，无法推进。");
            return;
        }
        if (sectorManager.isAllCaptured()) {
            replyError(sender, "已无后续据点（全据点攻占），请用 sg debug match end attacker 结束对局。");
            return;
        }
        if (!sectorManager.hasNextSector()) {
            replyError(sender, "当前已是最后一个据点；继续推进会被记为\"全据点攻占\"，故本指令拒绝执行。"
                    + "要验证进攻方胜请用 sg debug sector capture（真实路径）或 sg debug match end attacker。");
            return;
        }

        String previous = sectorManager.getCurrentSector().getId();
        sectorManager.advanceToNextSector();
        sectorManager.openCurrentSector();

        Sector current = sectorManager.getCurrentSector();
        replyOk(sender, "已从据点 " + previous + " 推进到 " + (current == null ? "?" : current.getId())
                + "，并已立即开启该据点时限（" + sectorManager.getCurrentTimeLimitRemaining() + " tick）。");
        replyInfo(sender, "注意: 手工跳据点不会进入间歇期（间歇期只由真实炸弹爆炸触发）；"
                + "跳到最后一个据点也不会置 allCaptured，因此不会误触发\"全据点攻占=进攻方胜\"。");
    }

    /**
     * 把当前据点所有炸弹按真实事件路径引爆：标记为 EXPLODED 并发布真实 {@link BombExplodedEvent}
     * （与引信任务归零时发布的事件同源），因此对局闭环与间歇期都会按正常流程触发。
     */
    private void sectorCapture(CommandSender sender) {
        if (!requirePermission(sender, PERMISSION_ADMIN) || !requirePlaying(sender)) return;

        SectorManager sectorManager = SectorManager.getInstance();
        Sector sector = sectorManager.getCurrentSector();
        if (sector == null) {
            replyError(sender, "当前没有已加载的据点，无法引爆。");
            return;
        }

        int exploded = 0;
        for (ActiveBomb bomb : sectorManager.getActiveBombs()) {
            if (bomb.getState() == BombState.EXPLODED) continue;
            if (explodeBomb(sectorManager, sector, bomb)) exploded++;
        }
        if (exploded == 0) {
            replyInfo(sender, "当前据点没有可引爆的炸弹（全部已是 EXPLODED）。");
            return;
        }
        replyOk(sender, "已按真实事件路径引爆 " + exploded + " 颗炸弹（发布 BombExplodedEvent）；"
                + "对局闭环与间歇期会按正常流程处理。");
    }

    // ==================== ticket ====================

    private void handleTicket(CommandSender sender, String[] args) {
        String action = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "info" -> ticketInfo(sender);
            case "set" -> ticketSet(sender, args);
            case "add" -> ticketAdd(sender, args);
            default -> replyError(sender, "未知的 ticket 子指令: " + args[0] + " (可用: info, set <n>, add <n>)");
        }
    }

    private void ticketInfo(CommandSender sender) {
        if (!requirePermission(sender, PERMISSION_VIEW) || !requirePlaying(sender)) return;

        TicketManager ticketManager = TicketManager.getInstance();
        sender.sendMessage(Component.text("[sg] 进攻方票数: " + ticketManager.getCurrentTickets()
                + " / 上限 " + ticketManager.getMaxTickets()
                + " (已初始化: " + ticketManager.isInitialized()
                + ", 已耗尽: " + ticketManager.isDepleted() + ")", NamedTextColor.GRAY));
    }

    /** 修改票数：只经 TicketManager 既有接口（increase 受 maxTickets 钳制、decrease 扣到 0 时发布票尽事件）。 */
    private void ticketSet(CommandSender sender, String[] args) {
        if (!requirePermission(sender, PERMISSION_ADMIN) || !requirePlaying(sender)) return;
        if (args.length < 2) {
            replyError(sender, "用法: /sg debug ticket set <数量>");
            return;
        }
        Integer target = parseAmount(sender, args[1]);
        if (target == null) return;

        TicketManager ticketManager = TicketManager.getInstance();
        if (!ticketManager.isInitialized()) {
            replyError(sender, "票数系统未初始化（当前不在有效对局中）。");
            return;
        }

        int max = ticketManager.getMaxTickets();
        int clamped = Math.max(0, Math.min(target, max));
        if (clamped != target) {
            replyInfo(sender, "目标票数 " + target + " 超出范围 [0, " + max + "]，按 " + clamped + " 处理（钳制语义由 TicketManager 决定）。");
        }

        int current = ticketManager.getCurrentTickets();
        int delta = clamped - current;
        if (delta > 0) {
            ticketManager.increaseTicket(delta);
        } else if (delta < 0) {
            ticketManager.decreaseTicket(-delta);
        }

        replyOk(sender, "票数已由 " + current + " 调整为 " + ticketManager.getCurrentTickets() + "。");
        if (ticketManager.getCurrentTickets() <= 0) {
            replyInfo(sender, "票数已归零：TicketDepletedEvent 已发布，防守方胜判定将在下一 tick 生效（同 tick 进攻方优先）。");
        }
    }

    private void ticketAdd(CommandSender sender, String[] args) {
        if (!requirePermission(sender, PERMISSION_ADMIN) || !requirePlaying(sender)) return;
        if (args.length < 2) {
            replyError(sender, "用法: /sg debug ticket add <数量>");
            return;
        }
        Integer amount = parseAmount(sender, args[1]);
        if (amount == null) return;

        TicketManager ticketManager = TicketManager.getInstance();
        if (!ticketManager.isInitialized()) {
            replyError(sender, "票数系统未初始化（当前不在有效对局中）。");
            return;
        }
        int before = ticketManager.getCurrentTickets();
        ticketManager.increaseTicket(amount);
        replyOk(sender, "票数 " + before + " + " + amount + " = " + ticketManager.getCurrentTickets()
                + "（上限 " + ticketManager.getMaxTickets() + " 由 TicketManager 钳制）。");
    }

    // ==================== bomb ====================

    private void handleBomb(CommandSender sender, String[] args) {
        String action = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> bombList(sender);
            case "set" -> bombSet(sender, args);
            default -> replyError(sender, "未知的 bomb 子指令: " + args[0] + " (可用: list, set <id> <状态>)");
        }
    }

    private void bombList(CommandSender sender) {
        if (!requirePermission(sender, PERMISSION_VIEW) || !requirePlaying(sender)) return;

        List<ActiveBomb> bombs = SectorManager.getInstance().getActiveBombs();
        if (bombs.isEmpty()) {
            replyInfo(sender, "当前据点没有运行时炸弹。");
            return;
        }
        for (ActiveBomb bomb : bombs) {
            sender.sendMessage(Component.text("[sg] " + bomb.getId() + " [" + bomb.getState() + "] 引信="
                    + bomb.getFuseRemaining() + " tick  (名字: ", NamedTextColor.GRAY)
                    .append(bomb.getConfig().getName())
                    .append(Component.text(")", NamedTextColor.GRAY)));
        }
        replyInfo(sender, "设置: /sg debug bomb set <id> <UNPLANTED|PLANTED|EXPLODED>");
    }

    /** 设置炸弹状态：PLANTED/UNPLANTED 走 SectorManager 的成功回调（含真实事件），EXPLODED 走真实 BombExplodedEvent。 */
    private void bombSet(CommandSender sender, String[] args) {
        if (!requirePermission(sender, PERMISSION_ADMIN) || !requirePlaying(sender)) return;
        if (args.length < 3) {
            replyError(sender, "用法: /sg debug bomb set <id> <UNPLANTED|PLANTED|EXPLODED>");
            return;
        }

        SectorManager sectorManager = SectorManager.getInstance();
        Sector sector = sectorManager.getCurrentSector();
        if (sector == null) {
            replyError(sender, "当前没有已加载的据点。");
            return;
        }

        String bombId = args[1];
        ActiveBomb bomb = sectorManager.getActiveBomb(bombId);
        if (bomb == null) {
            replyError(sender, "当前据点没有炸弹: " + bombId + " (可用: " + activeBombIds() + ")");
            return;
        }

        BombState target;
        try {
            target = BombState.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            replyError(sender, "未知炸弹状态: " + args[2] + " (可用: " + String.join(", ", BOMB_STATES) + ")");
            return;
        }

        BombState before = bomb.getState();
        if (before == target) {
            replyInfo(sender, "炸弹 " + bombId + " 已是 " + target + "，无需变更。");
            return;
        }

        switch (target) {
            case PLANTED -> {
                if (!sectorManager.onBombPlantSuccess(bombId)) {
                    replyError(sender, "安放失败：炸弹 " + bombId + " 当前状态 " + before + " 无法直接安放（需为 UNPLANTED）。");
                    return;
                }
                replyOk(sender, "炸弹 " + bombId + " " + before + " -> PLANTED（已发布 BombPlantedEvent 并启动引信 "
                        + bomb.getFuseRemaining() + " tick）。");
            }
            case UNPLANTED -> {
                if (!sectorManager.onBombDefuseSuccess(bombId)) {
                    replyError(sender, "拆除失败：炸弹 " + bombId + " 当前状态 " + before + " 无法拆除（需为 PLANTED）。");
                    return;
                }
                replyOk(sender, "炸弹 " + bombId + " " + before + " -> UNPLANTED（已发布 BombDefusedEvent 并取消引信）。");
            }
            case EXPLODED -> {
                if (!explodeBomb(sectorManager, sector, bomb)) {
                    replyError(sender, "引爆失败：炸弹 " + bombId + " 状态异常。");
                    return;
                }
                replyOk(sender, "炸弹 " + bombId + " " + before + " -> EXPLODED（已发布真实 BombExplodedEvent，"
                        + "对局闭环/间歇期按正常流程处理）。");
            }
        }
    }

    /**
     * 把一颗炸弹标记为 EXPLODED 并发布真实 {@link BombExplodedEvent}。
     * <p>
     * {@code ActiveBomb#explode()} 会同时取消引信任务，因此不会与该任务自身的事件重复发布；
     * 发布的事件与被引信任务归零时发布的事件完全同源，避免"状态改了但事件没发"的假象。
     */
    private boolean explodeBomb(SectorManager sectorManager, Sector sector, ActiveBomb bomb) {
        if (bomb.getState() == BombState.EXPLODED) return false;
        bomb.explode();
        GameEventBus.publish(new BombExplodedEvent(sector, bomb.getConfig()));
        return true;
    }

    // ==================== intermission ====================

    private void handleIntermission(CommandSender sender, String[] args) {
        String action = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "info" -> intermissionInfo(sender);
            case "skip" -> intermissionSkip(sender);
            default -> replyError(sender, "未知的 intermission 子指令: " + args[0] + " (可用: info, skip)");
        }
    }

    private void intermissionInfo(CommandSender sender) {
        if (!requirePermission(sender, PERMISSION_VIEW) || !requirePlaying(sender)) return;

        IntermissionController intermission = IntermissionController.getInstance();
        if (!intermission.isIntermissionActive()) {
            replyInfo(sender, "当前不在间歇期（区域推进间隔 "
                    + SectorManager.getInstance().getSectorAdvanceInterval() + " tick）。");
            return;
        }
        sender.sendMessage(Component.text("[sg] 间歇期中: 剩余 " + intermission.getRemainingTicks() + " tick (约 "
                + toSeconds(intermission.getRemainingTicks()) + " 秒) / 总时长 "
                + intermission.getTotalTicks() + " tick", NamedTextColor.GRAY));
    }

    private void intermissionSkip(CommandSender sender) {
        if (!requirePermission(sender, PERMISSION_ADMIN) || !requirePlaying(sender)) return;

        IntermissionController intermission = IntermissionController.getInstance();
        if (!intermission.isIntermissionActive()) {
            replyError(sender, "当前不在间歇期，无需跳过。");
            return;
        }
        int remaining = intermission.getRemainingTicks();
        intermission.endIntermissionNow();
        replyOk(sender, "已提前结束间歇期（原剩余 " + remaining + " tick）：新据点已正式开启，进攻方时限开始计时。");
    }

    // ==================== match ====================

    private void handleMatch(CommandSender sender, String[] args) {
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (action.equals("end")) {
            matchEnd(sender, args);
            return;
        }
        replyError(sender, "未知的 match 子指令: " + (args.length == 0 ? "(空)" : args[0])
                + " (可用: end <attacker|defender> [原因])");
    }

    /**
     * 强制结束对局：先做 PLAYING 与"本局未结束"预检查，再调用 public 的
     * {@link SectorProgressController#endMatch(ShdfTeam, String)}。
     * <p>
     * 为什么必须预检查：{@code endMatch} 会置 {@code matchEnded} 并广播胜方，而真正切到 FINISHED 的动作
     * 由它的下一 tick 守卫（{@code getCurrentState()==PLAYING && matchEnded}）决定。若在非 PLAYING 阶段调用，
     * 守卫会拦下状态切换，但 {@code matchEnded} 与广播已经发生——那正是我们要避免的"静默残留"。
     * 因此这里先检查，非 PLAYING 时给出明确提示、**不调用 endMatch**，不绕过守卫。
     */
    private void matchEnd(CommandSender sender, String[] args) {
        if (!requirePermission(sender, PERMISSION_ADMIN)) return;
        if (args.length < 2) {
            replyError(sender, "用法: /sg debug match end <attacker|defender> [原因]");
            return;
        }

        ShdfTeam winner = switch (args[1].toLowerCase(Locale.ROOT)) {
            case "attacker" -> ShdfTeam.ATTACKER;
            case "defender" -> ShdfTeam.DEFENDER;
            default -> null;
        };
        if (winner == null) {
            replyError(sender, "未知阵营: " + args[1] + " (可用: " + String.join(", ", WINNERS) + ")");
            return;
        }

        if (!requirePlaying(sender)) return;
        if (MatchSessionState.getInstance().isMatchEnded()) {
            replyError(sender, "本局已结算 (matchEnded=true)，不再重复结束。");
            return;
        }

        String reason = args.length > 2
                ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : "调试指令强制结束";
        SectorProgressController.getInstance().endMatch(winner, reason);

        replyOk(sender, "已调用 endMatch(" + winner.name() + ", \"" + reason
                + "\")：matchEnded 已置位，下一 tick 切到 FINISHED（随后清理并踢人回 IDLE）。");
    }

    // ==================== Tab 补全 ====================

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return filter(TOPICS, args[0]);
        }

        String topic = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return filter(actionsOf(topic), args[1]);
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        if (topic.equals("bomb") && action.equals("set")) {
            if (args.length == 3) {
                return filter(activeBombIds(), args[2]);
            }
            if (args.length == 4) {
                return filter(BOMB_STATES, args[3]);
            }
        }
        if (topic.equals("match") && action.equals("end") && args.length == 3) {
            return filter(WINNERS, args[2]);
        }
        return List.of();
    }

    private List<String> actionsOf(String topic) {
        return switch (topic) {
            case "sector" -> List.of("info", "next", "capture");
            case "ticket" -> List.of("info", "set", "add");
            case "bomb" -> List.of("list", "set");
            case "intermission" -> List.of("info", "skip");
            case "match" -> List.of("end");
            default -> List.of();
        };
    }

    private List<String> activeBombIds() {
        List<String> ids = new ArrayList<>();
        for (ActiveBomb bomb : SectorManager.getInstance().getActiveBombs()) {
            ids.add(bomb.getId());
        }
        return ids;
    }

    private List<String> filter(List<String> candidates, String prefix) {
        List<String> result = new ArrayList<>();
        String lower = prefix.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(candidate);
            }
        }
        return result;
    }

    // ==================== 通用工具 ====================

    /** 非 PLAYING 阶段一律拒绝并给出明确提示（不抛异常、不静默无效） */
    private boolean requirePlaying(CommandSender sender) {
        GameState state = GameStateMachine.getInstance().getCurrentState();
        if (state == GameState.PLAYING) return true;
        replyError(sender, "当前不在对局阶段（PLAYING），无法执行调试指令。当前状态: "
                + (state == null ? "未初始化" : state.name()));
        return false;
    }

    private boolean requirePermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        replyError(sender, "缺少权限: " + permission);
        return false;
    }

    /** 解析非负整数；非法输入给出明确提示并返回 null */
    private Integer parseAmount(CommandSender sender, String raw) {
        try {
            int value = Integer.parseInt(raw);
            if (value < 0) {
                replyError(sender, "数量不能为负: " + raw);
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            replyError(sender, "不是合法的整数: " + raw);
            return null;
        }
    }

    private static int toSeconds(int ticks) {
        return (int) Math.ceil(ticks / 20.0);
    }

    private void printUsage(CommandSender sender) {
        replyInfo(sender, "用法: /sg debug <sector|ticket|bomb|intermission|match> ...");
        replyInfo(sender, "  sector info|next|capture    ticket info|set <n>|add <n>");
        replyInfo(sender, "  bomb list|set <id> <UNPLANTED|PLANTED|EXPLODED>");
        replyInfo(sender, "  intermission info|skip      match end <attacker|defender> [原因]");
        replyInfo(sender, "权限: 查看 " + PERMISSION_VIEW + " / 修改 " + PERMISSION_ADMIN + "（均未在 plugin.yml 声明，默认仅 OP）");
    }

    private void replyOk(CommandSender sender, String text) {
        sender.sendMessage(Component.text("[sg] " + text, NamedTextColor.GREEN));
    }

    private void replyInfo(CommandSender sender, String text) {
        sender.sendMessage(Component.text("[sg] " + text, NamedTextColor.GRAY));
    }

    private void replyError(CommandSender sender, String text) {
        sender.sendMessage(Component.text("[sg] " + text, NamedTextColor.RED));
    }
}
