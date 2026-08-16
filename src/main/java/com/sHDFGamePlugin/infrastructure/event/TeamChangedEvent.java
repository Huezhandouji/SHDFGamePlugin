package com.sHDFGamePlugin.infrastructure.event;

import com.sHDFGamePlugin.domain.team.Team;
import com.sHDFGamePlugin.infrastructure.SHDFGameEvent;

import java.util.UUID;

public class TeamChangedEvent extends SHDFGameEvent {

    private final UUID playerUuid;;
    private final Team oldTeam;
    private final Team newTeam;

    public TeamChangedEvent(UUID playerUuid, Team oldTeam, Team newTeam) {
        this.playerUuid = playerUuid;
        this.oldTeam = oldTeam;
        this.newTeam = newTeam;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public Team getOldTeam() {
        return oldTeam;
    }

    public Team getNewTeam() {
        return newTeam;
    }
}
