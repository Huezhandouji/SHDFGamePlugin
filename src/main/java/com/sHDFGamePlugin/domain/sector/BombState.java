package com.sHDFGamePlugin.domain.sector;

/**
 * 据点炸弹状态（由 SectorManager 维护）。
 * <p>
 * 状态流转：
 * UNPLANTED --安放成功--> PLANTED --引信归零--> EXPLODED
 * PLANTED   --拆弹成功--> UNPLANTED
 */
public enum BombState {

    /** 未安放：进攻方可发起安放 */
    UNPLANTED,

    /** 已安放：引信倒计时中，防守方可发起拆弹 */
    PLANTED,

    /** 已爆炸：本据点推进完成 */
    EXPLODED;

    /** 是否允许安放 */
    public boolean isPlantable() {
        return this == UNPLANTED;
    }

    /** 是否允许拆除 */
    public boolean isDefusable() {
        return this == PLANTED;
    }
}
