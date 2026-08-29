package com.sHDFGamePlugin.infrastructure.event;

import com.sHDFGamePlugin.domain.sector.Sector;
import com.sHDFGamePlugin.infrastructure.SHDFGameEvent;
import com.sHDFGamePlugin.infrastructure.config.BombConfig;

/** 炸弹安放成功（UNPLANTED -> PLANTED） */
public class BombPlantedEvent extends SHDFGameEvent {

    private final Sector sector;
    private final BombConfig bomb;

    public BombPlantedEvent(Sector sector, BombConfig bomb) {
        this.sector = sector;
        this.bomb = bomb;
    }

    public Sector getSector() {
        return sector;
    }

    public BombConfig getBomb() {
        return bomb;
    }
}
