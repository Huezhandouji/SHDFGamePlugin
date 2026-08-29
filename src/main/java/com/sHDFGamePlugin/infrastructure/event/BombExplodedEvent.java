package com.sHDFGamePlugin.infrastructure.event;

import com.sHDFGamePlugin.domain.sector.Sector;
import com.sHDFGamePlugin.infrastructure.SHDFGameEvent;
import com.sHDFGamePlugin.infrastructure.config.BombConfig;

/** 炸弹爆炸（引信归零），PlayingPhase 收到后检查是否全部爆炸再推进据点 */
public class BombExplodedEvent extends SHDFGameEvent {

    private final Sector sector;
    private final BombConfig bomb;

    public BombExplodedEvent(Sector sector, BombConfig bomb) {
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
