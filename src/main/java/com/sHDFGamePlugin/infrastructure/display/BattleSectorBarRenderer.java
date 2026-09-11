package com.sHDFGamePlugin.infrastructure.display;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 战斗 BossBar 渲染器：第一条"据点链总览" + 每颗炸弹各一条"明细条"。
 *
 * <h2>第一条（总览）内容（已拍板格式）</h2>
 * <pre>
 * [剩余时间 mm:ss] 进攻方票数: 12 >> &lt;已攻占据点…&gt; >> &lt;进行中的据点: [B1 未安放] [B2 已安放]&gt; >> &lt;尚未开启的据点…&gt;
 * </pre>
 * <ul>
 *   <li>{@code >>} 分隔的是<b>三段据点链</b>：左侧=已被进攻方攻占的据点，中间=进行中的据点
 *       （<b>只有它</b>展示各炸弹状态），右侧=尚未开启的据点；</li>
 *   <li>已攻占据点绿色删除线并带 {@code ✔}；尚未开启据点深灰并带 {@code ·}。</li>
 * </ul>
 *
 * <h2>明细条</h2>
 * 每颗"进行中据点的炸弹"各一条：{@code 炸弹名 [状态] 剩余引信 Xs}（PLANTED）/
 * {@code 炸弹名 [状态] 安放需 Xs}（UNPLANTED）/ {@code 炸弹名 [状态]}（EXPLODED，灰 + 删除线）；
 * 条体颜色跟随状态（UNPLANTED 绿 / PLANTED 红 / EXPLODED 白——见 {@link #detailBarColor(BattleBombState)}）。
 * <b>已爆炸的炸弹同样保留明细条</b>（用户拍板：三态配色含 EXPLODED，明细条数 = 当前据点炸弹数）。
 *
 * <h2>生命周期</h2>
 * {@link #update(BattleDisplayState)} 幂等同步（新增/更新/不再需要的条自动摘除）；
 * {@link #hideAll()} 摘除并释放全部 BossBar 实例；{@link #clear()} 语义等价（供 onExit 调用）。
 * 集成方在阶段 {@code onExit} 调 {@link #clear()} 即可保证<b>不残留跨局 BossBar</b>。
 */
public final class BattleSectorBarRenderer {

    /** 三段据点链的分隔符（已拍板） */
    private static final String SECTOR_SEPARATOR = ">>";

    private final BattleDisplayViewer viewer;

    //玩家 -> 总览条实例
    private final Map<UUID, BossBar> overviewBars = new HashMap<>();
    //玩家 -> bombId -> 明细条实例（LinkedHashMap 保持配置顺序）
    private final Map<UUID, Map<String, BossBar>> detailBars = new HashMap<>();

    /** 默认宿主：每玩家一份 BossBar 实例，show/hide 严格成对 */
    public BattleSectorBarRenderer() {
        this(defaultViewer());
    }

    public BattleSectorBarRenderer(BattleDisplayViewer viewer) {
        if(viewer == null){
            throw new IllegalArgumentException("viewer must not be null");
        }
        this.viewer = viewer;
    }

    /** 基于 Adventure Audience 的默认宿主 */
    public static BattleDisplayViewer defaultViewer() {
        return (player, bar, show) -> {
            if(show){
                player.showBossBar(bar);
            }
            else{
                player.hideBossBar(bar);
            }
        };
    }

    // ==================== 纯渲染（可单测，无副作用） ====================

    /** 第一条文本：{@code [剩余时间 mm:ss] 进攻方票数: xxx <据点链>} */
    public static Component renderOverviewName(BattleDisplayState state) {
        if(state == null){
            return Component.text("[--:--] 进攻方票数: 0", NamedTextColor.GRAY);
        }
        Component result = Component.text("[" + state.remainingTimeText() + "]", NamedTextColor.AQUA)
                .append(Component.text(" 进攻方票数: ", NamedTextColor.GRAY))
                .append(Component.text(String.valueOf(state.attackerTickets()), NamedTextColor.WHITE))
                .append(Component.text(" " + SECTOR_SEPARATOR + " ", NamedTextColor.DARK_GRAY));

        boolean first = true;
        for(BattleSectorInfo sector : state.sectors()){
            if(!first){
                result = result.append(Component.text(" " + SECTOR_SEPARATOR + " ", NamedTextColor.DARK_GRAY));
            }
            result = result.append(renderSectorNode(sector));
            first = false;
        }
        if(first){
            result = result.append(Component.text("无据点数据", NamedTextColor.DARK_GRAY));
        }
        return result;
    }

    /**
     * 单个据点节点：
     * <ul>
     *   <li>CAPTURED：{@code ✔ 据点名}（绿色 + 删除线）；</li>
     *   <li>ACTIVE（待开启形态，{@link BattleSectorInfo#isOpening()}）：{@code 据点名(Ns)}（黄色 + 加粗倒计时），
     *       <b>不</b>渲染该据点炸弹状态——间歇期内炸弹不可交互，显示"绿=可安放"会误导玩家；</li>
     *   <li>ACTIVE（正常形态）：{@code 据点名: [B1 未安放] [B2 已安放]}（<b>只有它</b>显示炸弹状态）；</li>
     *   <li>PENDING：{@code · 据点名}（深灰）。</li>
     * </ul>
     */
    public static Component renderSectorNode(BattleSectorInfo sector) {
        if(sector == null){
            return Component.empty();
        }
        if(sector.status() == BattleSectorInfo.Status.CAPTURED){
            return Component.text("✔ ", NamedTextColor.GREEN)
                    .append(sector.displayName().colorIfAbsent(NamedTextColor.GREEN).decorate(TextDecoration.STRIKETHROUGH));
        }
        if(sector.status() == BattleSectorInfo.Status.PENDING){
            return Component.text("· ", NamedTextColor.DARK_GRAY)
                    .append(sector.displayName().colorIfAbsent(NamedTextColor.DARK_GRAY));
        }

        //间歇期"待开启"形态：只显示 名称(Ns) 倒计时，不显示炸弹状态
        if(sector.isOpening()){
            int seconds = (int) Math.ceil(sector.openingInTicks() / 20.0);
            return sector.displayName().colorIfAbsent(NamedTextColor.YELLOW)
                    .append(Component.text("(" + seconds + "s)", NamedTextColor.YELLOW, TextDecoration.BOLD));
        }

        Component result = sector.displayName().colorIfAbsent(NamedTextColor.YELLOW)
                .append(Component.text(":", NamedTextColor.YELLOW));
        for(BattleBombInfo bomb : sector.bombs()){
            result = result.append(Component.text(" ", NamedTextColor.DARK_GRAY))
                    .append(bomb.state().inlineComponent(plainNameOf(bomb)));
        }
        return result;
    }

    /** 单颗炸弹明细条文本：{@code 炸弹名 [状态] 剩余引信 Xs} / {@code 炸弹名 [状态] 安放需 Xs} */
    public static Component renderBombDetailName(BattleBombInfo bomb) {
        if(bomb == null){
            return Component.empty();
        }
        Component result = bomb.displayName().colorIfAbsent(NamedTextColor.WHITE)
                .append(Component.text(" ", NamedTextColor.DARK_GRAY))
                .append(bomb.state().statusComponent());

        if(bomb.state() == BattleBombState.PLANTED){
            return result.append(Component.text("  剩余引信 ", NamedTextColor.GRAY))
                    .append(Component.text(bomb.fuseRemainingSeconds() + "s", NamedTextColor.RED, TextDecoration.BOLD));
        }
        if(bomb.state() == BattleBombState.EXPLODED){
            return result.append(Component.text("  已引爆", NamedTextColor.GRAY));
        }
        return result.append(Component.text("  安放需 ", NamedTextColor.GRAY))
                .append(Component.text(bomb.plantTotalSeconds() + "s", NamedTextColor.GREEN));
    }

    /** 明细条实体颜色：UNPLANTED=绿 / PLANTED=红 / EXPLODED=白（Adventure 无灰实体条，灰色由文案承担） */
    public static BossBar.Color detailBarColor(BattleBombState state) {
        if(state == null){
            return BossBar.Color.WHITE;
        }
        return switch (state){
            case UNPLANTED -> BossBar.Color.GREEN;
            case PLANTED -> BossBar.Color.RED;
            //BossBar.Color 只有 PINK/BLUE/RED/GREEN/YELLOW/PURPLE/WHITE，没有 GRAY：
            //已爆炸的"灰色"由名称组件（灰 + 删除线）承担，条体取最接近的白色
            case EXPLODED -> BossBar.Color.WHITE;
        };
    }

    /** 明细条进度：PLANTED 显示引信剩余比例，其余显示 1.0 */
    public static float detailBarProgress(BattleBombInfo bomb) {
        if(bomb == null || bomb.state() != BattleBombState.PLANTED){
            return 1.0f;
        }
        int total = Math.max(bomb.plantTotalTicks(), bomb.fuseRemainingTicks());
        if(total <= 0){
            return 0.0f;
        }
        return clamp01(bomb.fuseRemainingTicks() / (float) total);
    }

    /** 总览条进度：优先按据点时限剩余比例，无时限时退化为票数比例 */
    public static float overviewProgress(BattleDisplayState state) {
        if(state == null){
            return 1.0f;
        }
        if(state.sectorTimeLimitTotalTicks() > 0){
            return clamp01(state.remainingSectorTicks() / (float) state.sectorTimeLimitTotalTicks());
        }
        if(state.attackerMaxTickets() > 0){
            return clamp01(state.attackerTickets() / (float) state.attackerMaxTickets());
        }
        return 1.0f;
    }

    // ==================== 渲染与同步 ====================

    /**
     * 幂等刷新：把 {@code state} 投影到所有已显示的玩家身上。
     * <p>
     * 调用频率由集成方控制（<b>建议 20 tick 或事件驱动</b>，不要每 tick 调用）；
     * 未 {@link #show(Player)} 过的玩家不会被显示。
     */
    public void update(BattleDisplayState state) {
        Component overviewName = renderOverviewName(state);
        float overviewProgress = overviewProgress(state);

        List<BattleBombInfo> activeBombs = new ArrayList<>();
        BattleSectorInfo activeSector = state == null ? null : state.activeSector();
        if(activeSector != null){
            activeBombs.addAll(activeSector.bombs());
        }

        for(Player player : viewers()){
            BossBar overview = overviewBars.get(player.getUniqueId());
            if(overview != null){
                overview.name(overviewName);
                overview.progress(overviewProgress);
                overview.color(BossBar.Color.WHITE);
                overview.overlay(BossBar.Overlay.PROGRESS);
            }
            syncDetailBars(player, activeBombs);
        }
    }

    /** 为玩家显示（总览条 + 当前明细条）；幂等：重复调用不会创建第二条 */
    public void show(Player player) {
        if(player == null){
            return;
        }
        UUID uuid = player.getUniqueId();
        if(!overviewBars.containsKey(uuid)){
            BossBar bar = BossBar.bossBar(renderOverviewName(null), 1.0f,
                    BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
            overviewBars.put(uuid, bar);
            viewer.apply(player, bar, true);
        }
    }

    /** 摘除该玩家的总览条与全部明细条，并释放实例 */
    public void hide(Player player) {
        if(player == null){
            return;
        }
        UUID uuid = player.getUniqueId();
        BossBar overview = overviewBars.remove(uuid);
        if(overview != null){
            viewer.apply(player, overview, false);
        }
        removeDetailBars(player, uuid);
    }

    /**
     * 摘除所有玩家的全部 BossBar（并释放实例）。
     * <p>强制清理入口，集成方在阶段 {@code onExit} 调用；离线玩家记录一并释放。</p>
     */
    public void hideAll() {
        for(UUID uuid : new ArrayList<>(overviewBars.keySet())){
            Player player = onlinePlayer(uuid);
            BossBar bar = overviewBars.remove(uuid);
            if(player != null && bar != null){
                viewer.apply(player, bar, false);
            }
        }
        for(UUID uuid : new ArrayList<>(detailBars.keySet())){
            Player player = onlinePlayer(uuid);
            Map<String, BossBar> details = detailBars.remove(uuid);
            if(player != null && details != null){
                for(BossBar bar : details.values()){
                    viewer.apply(player, bar, false);
                }
            }
        }
    }

    /** 语义等价于 {@link #hideAll()}：强调"一次性清理"，供集成方 onExit 调用 */
    public void clear() {
        hideAll();
    }

    /** 当前是否已为某玩家挂载了总览条 */
    public boolean isShown(UUID uuid) {
        return overviewBars.containsKey(uuid);
    }

    /** 当前已挂载总览条的玩家数（自检用） */
    public int shownPlayerCount() {
        return overviewBars.size();
    }

    /** 某玩家当前明细条数量（自检用） */
    public int detailBarCount(UUID uuid) {
        Map<String, BossBar> details = detailBars.get(uuid);
        return details == null ? 0 : details.size();
    }

    // ==================== 内部 ====================

    private void syncDetailBars(Player player, List<BattleBombInfo> bombs) {
        UUID uuid = player.getUniqueId();
        Map<String, BossBar> details = detailBars.get(uuid);
        if(details == null){
            details = new LinkedHashMap<>();
            detailBars.put(uuid, details);
        }

        Set<String> keep = new LinkedHashSet<>();
        for(BattleBombInfo bomb : bombs){
            //已爆炸的炸弹同样保留一条明细条（用户拍板）：灰 + 删除线，进度满
            keep.add(bomb.bombId());
            BossBar bar = details.get(bomb.bombId());
            if(bar == null){
                bar = BossBar.bossBar(renderBombDetailName(bomb), detailBarProgress(bomb),
                        detailBarColor(bomb.state()), BossBar.Overlay.NOTCHED_10);
                details.put(bomb.bombId(), bar);
                viewer.apply(player, bar, true);
            }
            else{
                bar.name(renderBombDetailName(bomb));
                bar.progress(detailBarProgress(bomb));
                bar.color(detailBarColor(bomb.state()));
            }
        }

        for(String bombId : new ArrayList<>(details.keySet())){
            if(!keep.contains(bombId)){
                BossBar stale = details.remove(bombId);
                if(stale != null){
                    viewer.apply(player, stale, false);
                }
            }
        }
    }

    private void removeDetailBars(Player player, UUID uuid) {
        Map<String, BossBar> details = detailBars.remove(uuid);
        if(details != null){
            for(BossBar bar : details.values()){
                viewer.apply(player, bar, false);
            }
        }
    }

    private Set<Player> viewers() {
        Set<Player> result = new LinkedHashSet<>();
        for(UUID uuid : new ArrayList<>(overviewBars.keySet())){
            Player player = onlinePlayer(uuid);
            if(player != null){
                result.add(player);
            }
        }
        //只显示过明细条、没有总览条的玩家极少见，但一并覆盖
        for(UUID uuid : new ArrayList<>(detailBars.keySet())){
            Player player = onlinePlayer(uuid);
            if(player != null){
                result.add(player);
            }
        }
        return result;
    }

    private static Player onlinePlayer(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if(player == null || !player.isOnline()){
            return null;
        }
        return player;
    }

    private static String plainNameOf(BattleBombInfo bomb) {
        String text = PlainTextComponentSerializer.plainText().serialize(bomb.displayName());
        if(text == null || text.isBlank()){
            return bomb.bombId();
        }
        return text;
    }

    private static float clamp01(float value) {
        if(Float.isNaN(value) || value < 0f){
            return 0f;
        }
        if(value > 1f){
            return 1f;
        }
        return value;
    }
}
