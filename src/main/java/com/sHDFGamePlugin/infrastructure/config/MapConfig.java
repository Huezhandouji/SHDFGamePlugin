package com.sHDFGamePlugin.infrastructure.config;

import com.sHDFGamePlugin.domain.sector.Sector;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MapConfig {

    private final String id;
    private final String name;
    private final String description;
    private final String icon;
    private final int maxTickets;
    private final int initialTickets;
    private final int attackerRespawnTime;
    private final int defenderRespawnTime;
    private final String world;
    private final Vector spectatorSpawnpoint;
    private final List<String> attackerRoles;
    private final List<String> defenderRoles;

    private final List<Sector> sectors;

    public MapConfig(String id, String name, String description,
                     String icon, int maxTickets, int initialTickets,
                     int attackerRespawnTime, int defenderRespawnTime,
                     String world, Vector spectatorSpawnpoint,
                     List<String> attackerRoles, List<String> defenderRoles,
                     List<Sector> sectors) {

        this.id = id;
        this.name = name;
        this.description = description;
        this.icon = icon;
        this.maxTickets = maxTickets;
        this.initialTickets = initialTickets;
        this.attackerRespawnTime = attackerRespawnTime;
        this.defenderRespawnTime = defenderRespawnTime;
        this.world = world;
        this.spectatorSpawnpoint = spectatorSpawnpoint;
        this.attackerRoles = attackerRoles;
        this.defenderRoles = defenderRoles;
        this.sectors = sectors;
    }

    //getters

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getIcon() {
        return icon;
    }

    public int getMaxTickets() {
        return maxTickets;
    }

    public int getInitialTickets() {
        return initialTickets;
    }

    public int getAttackerRespawnTime() {
        return attackerRespawnTime;
    }

    public int getDefenderRespawnTime() {
        return defenderRespawnTime;
    }

    public String getWorld() {
        return world;
    }

    public Vector getSpectatorSpawnpoint() {
        return spectatorSpawnpoint;
    }

    public List<String> getAttackerRoles() {
        return attackerRoles;
    }

    public List<String> getDefenderRoles() {
        return defenderRoles;
    }

    public List<String> getAllRoles(){
        List<String> allRoles = new ArrayList<>();
        allRoles.addAll(attackerRoles);
        allRoles.addAll(defenderRoles);
        return allRoles;
    }

    public List<Sector> getSectors() {
        return sectors;
    }
}
