package com.sHDFGamePlugin.core;

import org.bukkit.entity.Player;

import java.util.UUID;

public class PlayerData {

    private final UUID uuid;
    private final String name;
    private Side side;
    private boolean ready;
    private boolean online;
    private String votedMap;
    private int kills;
    private int deaths;

    public PlayerData(Player player){
        this.uuid = player.getUniqueId();
        this.name = player.getName();
        this.side = Side.SPECTATOR;
        this.ready = false;
        this.online = true;
        this.votedMap = null;
        this.kills = 0;
        this.deaths = 0;
    }

    //getter

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public Side getSide() {
        return side;
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isOnline() {
        return online;
    }

    public String getVotedMap() {
        return votedMap;
    }

    public int getKills() {
        return kills;
    }

    public int getDeaths() {
        return deaths;
    }

    //setter

    public void setSide(Side side) {
        this.side = side;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    public void setVotedMap(String votedMap) {
        this.votedMap = votedMap;
    }

    public void setKills(int kills) {
        this.kills = kills;
    }

    public void setDeaths(int deaths) {
        this.deaths = deaths;
    }

    //快捷方法
    public boolean isCombatant(){
        return side.isCombatant();
    }

    public void addKill(){
        kills += 1;
    }

    public void addDeath(){
        deaths -= 1;
    }

    public void resetStats(){
        kills = 0;
        deaths = 0;
    }

    public double getKD(){
        if(deaths == 0){
            return kills > 0 ? kills : 0d;
        }
        return (double) kills / deaths;
    }

    public Player getPlayer(){
        return org.bukkit.Bukkit.getPlayer(uuid);
    }


}
