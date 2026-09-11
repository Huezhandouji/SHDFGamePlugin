package com.sHDFGamePlugin.infrastructure.display;

import net.kyori.adventure.text.Component;

import java.util.List;

/**
 * 表现层据点快照（不可变值对象）：一条记录承载据点链三段语义。
 * <ul>
 *   <li>{@link Status#CAPTURED} —— 已被进攻方攻占（出现在第一条 BossBar 的 {@code >>} <b>左侧</b>）；
 *       <b>包含"刚被清空、正在间歇"的那一个</b>——攻占那一刻即判定为已攻占</li>
 *   <li>{@link Status#ACTIVE} —— 中段据点。两种形态：
 *       <ul>
 *         <li>正常进行中：展示该据点名 + 各炸弹状态（三态配色）</li>
 *         <li>间歇期待开启（{@link #isOpening()}）：展示该据点名 + {@code (Ns)} 开启倒计时，
 *             <b>不</b>展示炸弹状态（间歇期内炸弹不可交互，显示"绿=可安放"会误导玩家）</li>
 *       </ul>
 *   </li>
 *   <li>{@link Status#PENDING} —— 尚未开启的据点（出现在 {@code >>} <b>右侧</b>）</li>
 * </ul>
 * 间歇期链示例：{@code >> 前线哨站 >> 中心广场(8s) >> 核心基地}
 */
public final class BattleSectorInfo {

    public enum Status {

        /** 已被进攻方攻占 */
        CAPTURED,
        /** 中段据点：正常进行中，或间歇期待开启（见 {@link BattleSectorInfo#isOpening()}） */
        ACTIVE,
        /** 尚未开启 */
        PENDING
    }

    private final String sectorId;
    private final Component name;
    private final Status status;

    /** 仅 ACTIVE 据点有意义；CAPTURED/PENDING 传空列表 */
    private final List<BattleBombInfo> bombs;

    /** 据点内已爆炸炸弹数（进度分子） */
    private final int capturedBombs;

    /** 据点内炸弹总数（进度分母） */
    private final int totalBombs;

    /**
     * 间歇期开启倒计时（tick）：仅 ACTIVE 据点、且"已攻占上一个据点、等待本据点开启"时 &gt; 0。
     * <p>0 = 正常形态（展示炸弹状态）；&gt; 0 = 待开启形态（展示 {@code 名称(Ns)}，不展示炸弹状态）。</p>
     */
    private final int openingInTicks;

    private BattleSectorInfo(String sectorId, Component name, Status status,
                             List<BattleBombInfo> bombs, int capturedBombs, int totalBombs,
                             int openingInTicks) {
        this.sectorId = sectorId;
        this.name = name;
        this.status = status == null ? Status.PENDING : status;
        this.bombs = bombs == null ? List.of() : List.copyOf(bombs);
        this.totalBombs = Math.max(totalBombs, this.bombs.size());
        this.capturedBombs = Math.max(0, Math.min(capturedBombs, this.totalBombs));
        this.openingInTicks = Math.max(openingInTicks, 0);
    }

    /** 已攻占据点：不携带炸弹明细 */
    public static BattleSectorInfo captured(String sectorId, Component name) {
        return new BattleSectorInfo(sectorId, name, Status.CAPTURED, List.of(), 0, 0, 0);
    }

    /** 尚未开启据点：不携带炸弹明细 */
    public static BattleSectorInfo pending(String sectorId, Component name) {
        return new BattleSectorInfo(sectorId, name, Status.PENDING, List.of(), 0, 0, 0);
    }

    /** 进行中据点：携带炸弹明细；已爆炸数由明细推导，也可显式覆盖（用于"据点进度 2/3"） */
    public static BattleSectorInfo active(String sectorId, Component name, List<BattleBombInfo> bombs) {
        int captured = 0;
        if(bombs != null){
            for(BattleBombInfo bomb : bombs){
                if(bomb.state() == BattleBombState.EXPLODED){
                    captured++;
                }
            }
        }
        return new BattleSectorInfo(sectorId, name, Status.ACTIVE, bombs, captured, bombs == null ? 0 : bombs.size(), 0);
    }

    /** 进行中据点：显式指定进度（炸弹明细与进度分母可能不一致时使用） */
    public static BattleSectorInfo active(String sectorId, Component name, List<BattleBombInfo> bombs,
                                          int capturedBombs, int totalBombs) {
        return new BattleSectorInfo(sectorId, name, Status.ACTIVE, bombs, capturedBombs, totalBombs, 0);
    }

    /** 进行中据点：显式指定进度与间歇期开启倒计时（{@code openingInTicks > 0} 时按"待开启形态"渲染） */
    public static BattleSectorInfo active(String sectorId, Component name, List<BattleBombInfo> bombs,
                                          int capturedBombs, int totalBombs, int openingInTicks) {
        return new BattleSectorInfo(sectorId, name, Status.ACTIVE, bombs, capturedBombs, totalBombs, openingInTicks);
    }

    /**
     * 间歇期待开启的中段据点：一段"即将开启的下一个据点 + 开启倒计时"。
     * <p>渲染为 {@code 名称(Ns)}，<b>不</b>展示炸弹状态（间歇期内炸弹不可交互）；
     * 倒计时归零、据点正式开启后，改用 {@link #active(String, Component, List)} 恢复正常形态。</p>
     *
     * @param openingInTicks 间歇期剩余 tick（&lt;= 0 时等价于普通 {@code active}，但通常调用方只在间歇期使用本工厂）
     */
    public static BattleSectorInfo opening(String sectorId, Component name, int openingInTicks) {
        return new BattleSectorInfo(sectorId, name, Status.ACTIVE, List.of(), 0, 0, openingInTicks);
    }

    /**
     * 基于当前记录返回一份开启倒计时（tick）被设定的副本。
     * <p>{@code openingInTicks <= 0} 时返回"正常形态"副本（倒计时清零、保留原有炸弹明细）。
     * 早于本 API 构造的记录（倒计时为 0）调用本方法设置正值即进入待开启形态。</p>
     */
    public BattleSectorInfo withOpeningInTicks(int openingInTicks) {
        return new BattleSectorInfo(sectorId, name, status, bombs, capturedBombs, totalBombs, openingInTicks);
    }

    public String sectorId() {
        return sectorId;
    }

    public Component name() {
        return name;
    }

    public Status status() {
        return status;
    }

    /** 进行中据点的炸弹明细（保持配置顺序）；其余状态为空列表 */
    public List<BattleBombInfo> bombs() {
        return bombs;
    }

    public int capturedBombs() {
        return capturedBombs;
    }

    public int totalBombs() {
        return totalBombs;
    }

    /** 间歇期开启倒计时（tick）；0 = 非待开启形态 */
    public int openingInTicks() {
        return openingInTicks;
    }

    /** 是否处于"待开启"形态（中段据点 + 开启倒计时 &gt; 0）：此时渲染 {@code 名称(Ns)} 且不显示炸弹状态 */
    public boolean isOpening() {
        return status == Status.ACTIVE && openingInTicks > 0;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    /** "2/3" 形式的据点进度（分母为 0 时显示 0/0） */
    public String progressText() {
        return capturedBombs + "/" + totalBombs;
    }

    /** 显示名（缺失时退回据点 id） */
    public Component displayName() {
        if(name == null){
            return Component.text(sectorId == null ? "未知据点" : sectorId);
        }
        return name;
    }
}
