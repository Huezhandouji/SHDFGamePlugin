package com.sHDFGamePlugin.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.List;

public class GameManager {

    private static GameManager instance;

    private GameState state = GameState.IDLE;
    private final Map<UUID, PlayerData> players = new HashMap<>();

    private GameManager(){

    }

    static {
        instance = new GameManager();
    }

    public static GameManager getInstance(){
        return instance;
    }

    public GameState getState(){
        return state;
    }

    public void setState(GameState state){
        this.state = state;
        updateScoreboard();
    }

    public void addPlayer(Player player, PlayerData data){
        players.put(player.getUniqueId(), data);
    }

    public PlayerData removePlayer(Player player){
        return players.remove(player.getUniqueId());
    }

    public PlayerData getPlayerData(Player player){
        return players.get(player.getUniqueId());
    }

    public Collection<PlayerData> getAllPlayerData(){
        return players.values();
    }

    public List<PlayerData> getAllPlayerDataBySide(Side side){
        List<PlayerData> result = new ArrayList<>();
        for(PlayerData data : players.values()){
            if(data.getSide() == side){
                result.add(data);
            }
        }
        return result;
    }

    public int getSidePopulation(Side side){
        int count = 0;
        for(PlayerData data : players.values()){
            if(data.getSide() == side){
                count += 1;
            }
        }
        return count;
    }

    public int getTotalPopulation(){
        return players.size();
    }

    public int getTotalCombatantPopulation(){
        int count = 0;
        for(PlayerData data : players.values()){
            if(data.isCombatant()){
                count += 1;
            }
        }
        return count;
    }

    public boolean hasPlayer(Player player){
        return players.containsKey(player.getUniqueId());
    }

    public boolean isCombatant(Player player){
        PlayerData data = getPlayerData(player);
        return data != null && data.isCombatant();
    }

    //广播消息
    public void broadcast(Component text){
        for(UUID uuid : players.keySet()){
            Player player = Bukkit.getPlayer(uuid);
            if(player != null && player.isOnline()){
                player.sendMessage(Component.text("#战场快报#").color(NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true)
                                    .append(text));

            }
        }
    }

    //TODO
    public void updateScoreboard(){

    }

    public void showBossBar(String title, float progress){

    }

    public void hideBossBar(){

    }
}
