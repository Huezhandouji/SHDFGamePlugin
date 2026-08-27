package com.sHDFGamePlugin.domain.team;

import com.sHDFGamePlugin.infrastructure.GameEventBus;
import com.sHDFGamePlugin.infrastructure.event.TeamChangedEvent;

import java.util.*;
import java.util.stream.Collectors;

public class TeamManager {

    private static TeamManager instance = new TeamManager();

    private final Map<UUID, PlayerStatus> players = new HashMap<>();

    private TeamManager() {}

    public static TeamManager getInstance() {
        return instance;
    }

    public void reset(){
        players.clear();
    }

    public void addPlayer(UUID uuid, Team team){
        players.put(uuid, new PlayerStatus(uuid, team));
    }

    public void removePlayer(UUID uuid){
        players.remove(uuid);
    }

    public Team getTeam(UUID uuid){
        PlayerStatus status = players.get(uuid);
        return status != null ? status.getTeam() : null;
    }

    public void setTeam(UUID uuid, Team newTeam){
        PlayerStatus status = players.get(uuid);
        if(status == null) return;

        Team oldTeam = status.getTeam();
        if(oldTeam == newTeam) return;

        status.setTeam(newTeam);
        GameEventBus.publish(new TeamChangedEvent(uuid, oldTeam, newTeam));
    }

    public void setReady(UUID uuid, boolean ready){
        PlayerStatus status = players.get(uuid);
        if(status == null) return;
        status.setReady(ready);
    }
    public boolean isReady(UUID uuid){
        PlayerStatus status = players.get(uuid);
        return status != null && status.isReady();
    }

    public List<UUID> getAllPlayersUuidsInTeam(Team team){
        return players.values().stream()
                .filter(status -> status.getTeam() == team)
                .map(PlayerStatus::getUuid)
                .collect(Collectors.toList());
    }

    public int getPlayerPopulationOnTeam(Team team){
        return (int) players.values().stream()
                .filter(status -> status.getTeam() == team)
                .count();
    }

    public int getPlayerPopulation(){
        return players.size();
    }



    public int getSideDiff(){
        int a = getPlayerPopulationOnTeam(Team.ATTACKER);
        int d =  getPlayerPopulationOnTeam(Team.DEFENDER);
        return Math.abs(a - d);
    }

    public boolean hasUnknownTeamPlayers(){
        return players.values().stream().anyMatch(status -> status.getTeam() == Team.UNKNOWN);
    }

    public void autoAssignUnknownTeamPlayers(){
        List<UUID> unknowns = getAllPlayersUuidsInTeam(Team.UNKNOWN);
        for(UUID uuid : unknowns){
            Team target = chooseLessPopulationTeam();
            setTeam(uuid, target);
        }
        ;
    }

    private Team chooseLessPopulationTeam(){
        int a = getPlayerPopulationOnTeam(Team.ATTACKER);
        int d =  getPlayerPopulationOnTeam(Team.DEFENDER);
        if(a < d) return Team.ATTACKER;
        if(a > d) return Team.DEFENDER;
        return new Random().nextBoolean() ? Team.ATTACKER : Team.DEFENDER;
    }

    public Collection<PlayerStatus> getAllPlayerStatuses(){
        return Collections.unmodifiableCollection(players.values());
    }

    public Collection<PlayerStatus> getAllPlayerStatusesInTeam(Team team){
        return players.values().stream().filter(status -> status.getTeam() == team).toList();
    }

    public PlayerStatus getPlayerStatus(UUID uuid){
        return players.get(uuid);
    }
}
