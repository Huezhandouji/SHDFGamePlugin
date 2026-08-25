package com.sHDFGamePlugin.domain.team;

public enum Team {

    ATTACKER,
    DEFENDER,
    SPECTATOR,
    UNKNOWN;

    public boolean isParticipant() {
        return this == ATTACKER || this == DEFENDER || this == UNKNOWN;
    }

    public boolean isCombatant() {
        return this == ATTACKER || this == DEFENDER;
    }
}
