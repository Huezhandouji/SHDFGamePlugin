package com.sHDFGamePlugin.domain.team;

import com.sHDFGamePlugin.infrastructure.GameEventBus;
import com.sHDFGamePlugin.infrastructure.event.TeamChangedEvent;
import org.jetbrains.annotations.NotNull;

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

    public void addPlayer(@NotNull UUID uuid, @NotNull ShdfTeam shdfTeam, @NotNull PlayerState playerState) {
        players.put(uuid, new PlayerStatus(uuid, shdfTeam, playerState));
    }

    public void removePlayer(UUID uuid){
        players.remove(uuid);
    }

    public void removeAllPlayers(){
        players.clear();
    }

    public ShdfTeam getTeam(UUID uuid){
        PlayerStatus status = players.get(uuid);
        return status != null ? status.getTeam() : null;
    }

    public void setTeam(UUID uuid, ShdfTeam newShdfTeam){
        PlayerStatus status = players.get(uuid);
        if(status == null) return;

        ShdfTeam oldShdfTeam = status.getTeam();
        if(oldShdfTeam == newShdfTeam) return;

        status.setTeam(newShdfTeam);
        GameEventBus.publish(new TeamChangedEvent(uuid, oldShdfTeam, newShdfTeam));
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

    public List<UUID> getAllPlayersUuidsInTeam(ShdfTeam shdfTeam){
        return players.values().stream()
                .filter(status -> status.getTeam() == shdfTeam)
                .map(PlayerStatus::getUuid)
                .collect(Collectors.toList());
    }

    public int getPlayerPopulationOnTeam(ShdfTeam shdfTeam){
        return (int) players.values().stream()
                .filter(status -> status.getTeam() == shdfTeam)
                .count();
    }

    public int getPlayerPopulation(){
        return players.size();
    }



    public int getSideDiff(){
        int a = getPlayerPopulationOnTeam(ShdfTeam.ATTACKER);
        int d =  getPlayerPopulationOnTeam(ShdfTeam.DEFENDER);
        return Math.abs(a - d);
    }

    public boolean hasUnknownTeamPlayers(){
        return players.values().stream().anyMatch(status -> status.getTeam() == ShdfTeam.UNKNOWN);
    }

    public void autoAssignUnknownTeamPlayers(){
        List<UUID> unknowns = getAllPlayersUuidsInTeam(ShdfTeam.UNKNOWN);
        for(UUID uuid : unknowns){
            ShdfTeam target = chooseLessPopulationTeam();
            setTeam(uuid, target);
        }
        ;
    }

    private ShdfTeam chooseLessPopulationTeam(){
        int a = getPlayerPopulationOnTeam(ShdfTeam.ATTACKER);
        int d =  getPlayerPopulationOnTeam(ShdfTeam.DEFENDER);
        if(a < d) return ShdfTeam.ATTACKER;
        if(a > d) return ShdfTeam.DEFENDER;
        return new Random().nextBoolean() ? ShdfTeam.ATTACKER : ShdfTeam.DEFENDER;
    }

    public Collection<PlayerStatus> getAllPlayerStatuses(){
        return Collections.unmodifiableCollection(players.values());
    }

    public Collection<PlayerStatus> getAllPlayerStatusesInTeam(ShdfTeam shdfTeam){
        return players.values().stream().filter(status -> status.getTeam() == shdfTeam).toList();
    }

    public PlayerStatus getPlayerStatus(UUID uuid){
        return players.get(uuid);
    }
}
