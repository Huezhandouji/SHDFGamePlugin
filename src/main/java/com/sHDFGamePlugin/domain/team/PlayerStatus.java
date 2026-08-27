package com.sHDFGamePlugin.domain.team;

import java.util.UUID;

public class PlayerStatus {

    private final UUID uuid;
    private ShdfTeam shdfTeam;
    private boolean ready;
    private boolean inBattle;

    public PlayerStatus(UUID uuid, ShdfTeam shdfTeam) {
        this.uuid = uuid;
        this.shdfTeam = shdfTeam;
        this.ready = false;
        this.inBattle = false;
    }

    public UUID getUuid() {
        return uuid;
    }

    public ShdfTeam getTeam() {
        return shdfTeam;
    }

    public void setTeam(ShdfTeam shdfTeam) {
        this.shdfTeam = shdfTeam;
    }

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public boolean isInBattle() {
        return inBattle;
    }

    public void setInBattle(boolean inBattle) {
        this.inBattle = inBattle;
    }
}
