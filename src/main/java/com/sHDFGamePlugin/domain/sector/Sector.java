package com.sHDFGamePlugin.domain.sector;

import net.kyori.adventure.text.Component;

public class Sector {

    private final String id;
    private final Component name;
    private final Region region;

    //时间都以tick算
    private final int preheatTime;
    private final int captureTime;
    private final int timeLimit;

    private final int ticketReward;

    private final Region attackerSpawnRegion;
    private final Region defenderSpawnRegion;
    private final Region attackerActiveRegion;
    private final Region defenderActiveRegion;

    private Sector(String id, Component name, Region region, int preheatTime, int captureTime,
                   int timeLimit, int ticketReward, Region attackerSpawnRegion, Region defenderSpawnRegion,
                   Region attackerActiveRegion, Region defenderActiveRegion) {

        this.id = id;
        this.name = name;
        this.region = region;
        this.preheatTime = preheatTime;
        this.captureTime = captureTime;
        this.timeLimit = timeLimit;
        this.ticketReward = ticketReward;
        this.attackerSpawnRegion = attackerSpawnRegion;
        this.defenderSpawnRegion = defenderSpawnRegion;
        this.attackerActiveRegion = attackerActiveRegion;
        this.defenderActiveRegion = defenderActiveRegion;
    }

    public static class Builder{
        private String id;
        private Component name;
        private Region region;
        private int preheatTime;
        private int captureTime;
        private int timeLimit;
        private int ticketReward;
        private Region attackerSpawnRegion;
        private Region defenderSpawnRegion;
        private Region attackerActiveRegion;
        private Region defenderActiveRegion;

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

        public Builder region(Region region) {
            this.region = region;
            return this;
        }

        public Builder preheatTime(int preheatTime) {
            this.preheatTime = preheatTime;
            return this;
        }

        public Builder captureTime(int captureTime) {
            this.captureTime = captureTime;
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

        public Builder attackerSpawnRegion(Region attackerSpawnRegion) {
            this.attackerSpawnRegion = attackerSpawnRegion;
            return this;
        }

        public Builder defenderSpawnRegion(Region defenderSpawnRegion) {
            this.defenderSpawnRegion = defenderSpawnRegion;
            return this;
        }

        public Builder attackerActiveRegion(Region attackerActiveRegion) {
            this.attackerActiveRegion = attackerActiveRegion;
            return this;
        }

        public Builder defenderActiveRegion(Region defenderActiveRegion) {
            this.defenderActiveRegion = defenderActiveRegion;
            return this;
        }

        public Sector build(){
            if (id == null || name == null || region == null) {
                throw new IllegalStateException("Fields: id, name, region, are required!");
            }
            if (attackerSpawnRegion == null || defenderSpawnRegion == null) {
                throw new IllegalStateException("Fields: attackerSpawnRegion, defenderSpawnRegion, are required!");
            }
            if(attackerActiveRegion == null || defenderActiveRegion == null){
                throw new IllegalStateException("Fields: attackerActiveRegion, defenderActiveRegion, are required!");
            }
            if (preheatTime <= 0 || captureTime <= 0 || ticketReward < 0 || timeLimit <= 0) {
                throw new IllegalStateException("Number args must be positive!");
            }

            return new Sector(id, name, region, preheatTime, captureTime, timeLimit,
                    ticketReward, attackerSpawnRegion, defenderSpawnRegion,
                    attackerActiveRegion, defenderActiveRegion);
        }

    }

    public String getId() {
        return id;
    }

    public Component getName() {
        return name;
    }

    public Region getRegion() {
        return region;
    }

    public int getPreheatTime() {
        return preheatTime;
    }

    public int getCaptureTime() {
        return captureTime;
    }

    public int getTimeLimit() {
        return timeLimit;
    }

    public int getTicketReward() {
        return ticketReward;
    }

    public Region getAttackerSpawnRegion() {
        return attackerSpawnRegion;
    }

    public Region getDefenderSpawnRegion() {
        return defenderSpawnRegion;
    }

    public Region getAttackerActiveRegion() {
        return attackerActiveRegion;
    }

    public Region getDefenderActiveRegion() {
        return defenderActiveRegion;
    }
}
