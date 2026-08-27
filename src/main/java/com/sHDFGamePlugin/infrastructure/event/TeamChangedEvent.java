package com.sHDFGamePlugin.infrastructure.event;

import com.sHDFGamePlugin.domain.team.ShdfTeam;
import com.sHDFGamePlugin.infrastructure.SHDFGameEvent;

import java.util.UUID;

public class TeamChangedEvent extends SHDFGameEvent {

    private final UUID playerUuid;;
    private final ShdfTeam oldShdfTeam;
    private final ShdfTeam newShdfTeam;

    public TeamChangedEvent(UUID playerUuid, ShdfTeam oldShdfTeam, ShdfTeam newShdfTeam) {
        this.playerUuid = playerUuid;
        this.oldShdfTeam = oldShdfTeam;
        this.newShdfTeam = newShdfTeam;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public ShdfTeam getOldTeam() {
        return oldShdfTeam;
    }

    public ShdfTeam getNewTeam() {
        return newShdfTeam;
    }
}
