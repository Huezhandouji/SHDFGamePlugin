package com.sHDFGamePlugin.infrastructure.event;

import com.sHDFGamePlugin.domain.sector.Sector;
import com.sHDFGamePlugin.infrastructure.SHDFGameEvent;
import org.bukkit.GameEvent;

public class SectorTimeLimitExpiredEvent extends SHDFGameEvent {

    private final Sector sector;

    public SectorTimeLimitExpiredEvent(Sector sector) {
        this.sector = sector;
    }

    public Sector getSector() {
        return sector;
    }

}
