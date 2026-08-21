package com.sHDFGamePlugin.infrastructure.event;

import com.sHDFGamePlugin.SHDFGamePlugin;
import com.sHDFGamePlugin.domain.sector.Sector;
import com.sHDFGamePlugin.infrastructure.SHDFGameEvent;

public class SectorCapturedEvent extends SHDFGameEvent {

    private final Sector sector;

    public SectorCapturedEvent(Sector sector) {
        this.sector = sector;
    }

    public Sector getSector() {
        return sector;
    }
}
