package com.sHDFGamePlugin.domain.team;

import java.util.UUID;

public class PlayerStatus {

    private final UUID uuid;
    private Team team;
    private boolean ready;

    public PlayerStatus(UUID uuid, Team team) {
        this.uuid = uuid;
        this.team = team;
        this.ready = false;
    }

    public UUID getUuid() {
        return uuid;
    }

    public Team getTeam() {
        return team;
    }

    public void setTeam(Team team) {
        this.team = team;
    }

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }
}
