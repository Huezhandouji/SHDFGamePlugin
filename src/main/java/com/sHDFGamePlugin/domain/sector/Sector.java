package com.sHDFGamePlugin.domain.sector;

import com.sHDFGamePlugin.infrastructure.config.BombConfig;
import com.sHDFGamePlugin.infrastructure.regionExpression.CubeRegion;
import net.kyori.adventure.text.Component;

import java.util.List;

public class Sector {

    private final String id;
    private final Component name;

    //时间都以tick算
    private final int timeLimit;
    private final int ticketReward;

    //炸弹配置列表
    private final List<BombConfig> bombs;

    private final CubeRegion attackerSpawnRegion;
    private final CubeRegion defenderSpawnRegion;
    private final CubeRegion attackerActiveRegion;
    private final CubeRegion defenderActiveRegion;

    private Sector(String id, Component name, int timeLimit, int ticketReward, List<BombConfig> bombs,
                   CubeRegion attackerSpawnRegion, CubeRegion defenderSpawnRegion,
                   CubeRegion attackerActiveRegion, CubeRegion defenderActiveRegion) {

        this.id = id;
        this.name = name;
        this.timeLimit = timeLimit;
        this.ticketReward = ticketReward;
        this.bombs = bombs;
        this.attackerSpawnRegion = attackerSpawnRegion;
        this.defenderSpawnRegion = defenderSpawnRegion;
        this.attackerActiveRegion = attackerActiveRegion;
        this.defenderActiveRegion = defenderActiveRegion;
    }

    public static class Builder{
        private String id;
        private Component name;
        private int timeLimit;
        private int ticketReward;
        private List<BombConfig> bombs;
        private CubeRegion attackerSpawnRegion;
        private CubeRegion defenderSpawnRegion;
        private CubeRegion attackerActiveRegion;
        private CubeRegion defenderActiveRegion;

        private Builder() {}

        public static Builder create(){
            return new Builder();
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder name(Component name) {
            this.name = name;
            return this;
        }

        public Builder timeLimit(int timeLimit) {
            this.timeLimit = timeLimit;
            return this;
        }

        public Builder ticketReward(int ticketReward) {
            this.ticketReward = ticketReward;
            return this;
        }

        public Builder bombs(List<BombConfig> bombs) {
            this.bombs = bombs;
            return this;
        }

        public Builder attackerSpawnRegion(CubeRegion attackerSpawnRegion) {
            this.attackerSpawnRegion = attackerSpawnRegion;
            return this;
        }

        public Builder defenderSpawnRegion(CubeRegion defenderSpawnRegion) {
            this.defenderSpawnRegion = defenderSpawnRegion;
            return this;
        }

        public Builder attackerActiveRegion(CubeRegion attackerActiveRegion) {
            this.attackerActiveRegion = attackerActiveRegion;
            return this;
        }

        public Builder defenderActiveRegion(CubeRegion defenderActiveRegion) {
            this.defenderActiveRegion = defenderActiveRegion;
            return this;
        }

        public Sector build(){
            if (id == null || name == null) {
                throw new IllegalStateException("Fields: id, name, are required!");
            }
            if (bombs == null || bombs.isEmpty()) {
                throw new IllegalStateException("Field: bombs is required and cannot be empty!");
            }
            if (attackerSpawnRegion == null || defenderSpawnRegion == null) {
                throw new IllegalStateException("Fields: attackerSpawnRegion, defenderSpawnRegion, are required!");
            }
            if(attackerActiveRegion == null || defenderActiveRegion == null){
                throw new IllegalStateException("Fields: attackerActiveRegion, defenderActiveRegion, are required!");
            }
            if (ticketReward < 0 || timeLimit <= 0) {
                throw new IllegalStateException("Number args must be positive!");
            }

            return new Sector(id, name, timeLimit, ticketReward, bombs,
                    attackerSpawnRegion, defenderSpawnRegion,
                    attackerActiveRegion, defenderActiveRegion);
        }

    }

    public String getId() {
        return id;
    }

    public Component getName() {
        return name;
    }

    public int getTimeLimit() {
        return timeLimit;
    }

    public int getTicketReward() {
        return ticketReward;
    }

    public List<BombConfig> getBombs() {
        return bombs;
    }

    public CubeRegion getAttackerSpawnRegion() {
        return attackerSpawnRegion;
    }

    public CubeRegion getDefenderSpawnRegion() {
        return defenderSpawnRegion;
    }

    public CubeRegion getAttackerActiveRegion() {
        return attackerActiveRegion;
    }

    public CubeRegion getDefenderActiveRegion() {
        return defenderActiveRegion;
    }
}
