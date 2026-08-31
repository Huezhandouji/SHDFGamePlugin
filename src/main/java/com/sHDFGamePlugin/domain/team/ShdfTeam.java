package com.sHDFGamePlugin.domain.team;

public enum ShdfTeam {

    /** 进攻方（SHADOW） */
    ATTACKER,
    /** 防守方（HUNTER） */
    DEFENDER,
    /** 观战者：不参战 */
    SPECTATOR,
    /** 随机分配：开局前自动分到进攻或防守 */
    UNKNOWN;

    /** 是否参战（进攻/防守/随机均参战，观战者除外） */
    public boolean isParticipant() {
        return this == ATTACKER || this == DEFENDER || this == UNKNOWN;
    }

    /** 是否战斗人员（进攻/防守；随机与观战者不算） */
    public boolean isCombatant() {
        return this == ATTACKER || this == DEFENDER;
    }
}
