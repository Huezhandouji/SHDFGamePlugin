package com.sHDFGamePlugin.infrastructure;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class ConfigManager {

    private static final ConfigManager INSTANCE = new ConfigManager();

    private FileConfiguration mainConfig;
    private FileConfiguration mapConfig;

    private ConfigManager(){}

    public static ConfigManager getInstance() {
        return INSTANCE;
    }

    public void init(JavaPlugin plugin) {
        plugin.saveDefaultConfig();
        mainConfig = plugin.getConfig();

        File mapsFile = new File(plugin.getDataFolder(), "maps.yml");
        if(!mapsFile.exists()){
            plugin.saveResource("maps.yml", false);
        }
        mapConfig = YamlConfiguration.loadConfiguration(mapsFile);
    }

    //WAITING阶段
    public int getMinPlayerPerSide(){
        return mainConfig.getInt("waiting.min_players_per_side", 2);
    }

    public int getMaxSideDiff() {
        return mainConfig.getInt("waiting.max_side_diff", 2);
    }

    public int getMaxPopulation() {
        return mainConfig.getInt("waiting.max_population", 16);
    }

    public boolean isDefaultSpectator() {
        return mainConfig.getBoolean("waiting.default_side_after_join_spectator", false);
    }


    public boolean isRequireReady() {
        return mainConfig.getBoolean("waiting.require_ready", true);
    }

    //PLAYING阶段
    public int getRespawnInvulnerabilityTime() {
        return mainConfig.getInt("playing.respawn_invulnerability_time", 60);
    }

    public boolean isAllowDuplicateRoles() {
        return mainConfig.getBoolean("playing.allow_duplicate_roles", false);
    }

    //MAPS
    public java.util.List<String> getMapNames() {
        return mainConfig.getStringList("maps");
    }


}
