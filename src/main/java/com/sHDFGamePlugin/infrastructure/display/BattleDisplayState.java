package com.sHDFGamePlugin.infrastructure.display;

import net.kyori.adventure.text.Component;

import java.util.List;

/**
 * 表现层渲染输入（不可变值对象）：一帧要显示的全部数据。
 * <p>
 * 调用方（集成层）每帧收集数据后交给 {@link BattleDisplayService}，
 * 渲染组件不反向读取 domain / phase，保证表现层可独立编译、可独立测试。
 * <p>
 * 玩家维度数据（阵营 / 角色名）见 {@link PlayerView}。
 */
public final class BattleDisplayState {

    private final int remainingSectorTicks;
    private final int sectorTimeLimitTotalTicks;
    private final int attackerTickets;
    private final int attackerMaxTickets;
    private final List<BattleSectorInfo> sectors;
    private final int intermissionRemainingTicks;

    private BattleDisplayState(int remainingSectorTicks, int sectorTimeLimitTotalTicks, int attackerTickets,
                               int attackerMaxTickets, List<BattleSectorInfo> sectors,
                               int intermissionRemainingTicks) {
        this.remainingSectorTicks = Math.max(remainingSectorTicks, 0);
        this.sectorTimeLimitTotalTicks = Math.max(sectorTimeLimitTotalTicks, 0);
        this.attackerTickets = Math.max(attackerTickets, 0);
        this.attackerMaxTickets = Math.max(attackerMaxTickets, 0);
        this.sectors = sectors == null ? List.of() : List.copyOf(sectors);
        this.intermissionRemainingTicks = Math.max(intermissionRemainingTicks, 0);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 当前据点时限剩余 tick（"剩余时间 mm:ss"的来源） */
    public int remainingSectorTicks() {
        return remainingSectorTicks;
    }

    /** 当前据点时限总长 tick（总览条进度条用；为 0 时退化为票数比例） */
    public int sectorTimeLimitTotalTicks() {
        return sectorTimeLimitTotalTicks;
    }

    /** 进攻方剩余票数 */
    public int attackerTickets() {
        return attackerTickets;
    }

    /** 进攻方票数上限（仅用于展示） */
    public int attackerMaxTickets() {
        return attackerMaxTickets;
    }

    /** 据点链（保持地图配置顺序，含 CAPTURED / ACTIVE / PENDING 三段语义） */
    public List<BattleSectorInfo> sectors() {
        return sectors;
    }

    /** 间歇期剩余 tick；0 表示不在间歇期（由 t21 的间歇期机制提供） */
    public int intermissionRemainingTicks() {
        return intermissionRemainingTicks;
    }

    public boolean isIntermission() {
        return intermissionRemainingTicks > 0;
    }

    /** 当前进行中的据点；不存在返回 null */
    public BattleSectorInfo activeSector() {
        for(BattleSectorInfo sector : sectors){
            if(sector.isActive()){
                return sector;
            }
        }
        return null;
    }

    /** 下一个尚未开启的据点；不存在返回 null */
    public BattleSectorInfo nextPendingSector() {
        for(BattleSectorInfo sector : sectors){
            if(sector.status() == BattleSectorInfo.Status.PENDING){
                return sector;
            }
        }
        return null;
    }

    /** 剩余时间 mm:ss（向上取整到秒） */
    public String remainingTimeText() {
        return formatTicks(remainingSectorTicks);
    }

    /** tick → mm:ss（向上取整到秒） */
    public static String formatTicks(int ticks) {
        int seconds = (int) Math.ceil(Math.max(ticks, 0) / 20.0);
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    public static final class Builder {

        private int remainingSectorTicks;
        private int sectorTimeLimitTotalTicks;
        private int attackerTickets;
        private int attackerMaxTickets;
        private List<BattleSectorInfo> sectors;
        private int intermissionRemainingTicks;

        private Builder() {}

        public Builder remainingSectorTicks(int remainingSectorTicks) {
            this.remainingSectorTicks = remainingSectorTicks;
            return this;
        }

        public Builder sectorTimeLimitTotalTicks(int sectorTimeLimitTotalTicks) {
            this.sectorTimeLimitTotalTicks = sectorTimeLimitTotalTicks;
            return this;
        }

        public Builder attackerTickets(int attackerTickets) {
            this.attackerTickets = attackerTickets;
            return this;
        }

        public Builder attackerMaxTickets(int attackerMaxTickets) {
            this.attackerMaxTickets = attackerMaxTickets;
            return this;
        }

        public Builder sectors(List<BattleSectorInfo> sectors) {
            this.sectors = sectors;
            return this;
        }

        public Builder intermissionRemainingTicks(int intermissionRemainingTicks) {
            this.intermissionRemainingTicks = intermissionRemainingTicks;
            return this;
        }

        public BattleDisplayState build() {
            return new BattleDisplayState(remainingSectorTicks, sectorTimeLimitTotalTicks, attackerTickets,
                    attackerMaxTickets, sectors, intermissionRemainingTicks);
        }
    }

    /**
     * 玩家维度快照：侧边栏最后两行（我方阵营、所持角色）。
     * <p>
     * 与 {@link BattleDisplayState} 分离，便于给同一帧的不同玩家渲染各自阵营与角色。
     */
    public static final class PlayerView {

        private final Component teamName;
        private final Component roleName;

        public PlayerView(Component teamName, Component roleName) {
            this.teamName = teamName;
            this.roleName = roleName;
        }

        public Component teamName() {
            return teamName;
        }

        public Component roleName() {
            return roleName;
        }
    }
}
