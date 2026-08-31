package com.sHDFGamePlugin.domain.team;

import java.util.UUID;

public class PlayerStatus {

    private final UUID uuid;        // 玩家唯一标识
    private ShdfTeam shdfTeam;      // 当前阵营
    private boolean ready;          // 是否已准备（等待阶段使用）
    private PlayerState state;      // 玩家当前游戏状态（WAITING/ROLE_SELECTING/DEPLOYING/IN_BATTLE）

    //新增：玩家整场选定的角色ID（ROLE_SELECTION 阶段记录，部署时使用）
    private String selectedRoleId;

    public PlayerStatus(UUID uuid, ShdfTeam shdfTeam, PlayerState state) {
        this.uuid = uuid;
        this.shdfTeam = shdfTeam;
        this.ready = false;
        this.state = state;
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

    public PlayerState getState() {
        return state;
    }

    public void setState(PlayerState state) {
        this.state = state;
    }

    public String getSelectedRoleId() {
        return selectedRoleId;
    }

    public void setSelectedRoleId(String selectedRoleId) {
        this.selectedRoleId = selectedRoleId;
    }
}
