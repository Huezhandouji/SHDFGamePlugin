package com.sHDFGamePlugin.config;

import com.sHDFGamePlugin.SHDFGamePlugin;
import com.shadowHunterRolesPlugin.ShadowHunterRolesPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

public class GameConfig {

    private final static GameConfig instance;
    private FileConfiguration config;

    static {
        instance = new GameConfig();
    }

    private GameConfig(){

    }

    public static GameConfig getInstance(){
        return instance;
    }

    public void loadConfig(){
        SHDFGamePlugin plugin = SHDFGamePlugin.getInstance();
        plugin.saveDefaultConfig();
        this.config = plugin.getConfig();
    }

    public void reloadConfig(){
        SHDFGamePlugin.getInstance().reloadConfig();
        this.config = ShadowHunterRolesPlugin.getInstance().getConfig();
    }

    public int getMinPlayerPerSide(){
        return config.getInt("waiting.min_players_per_side", 1);
    }

    public int getMaxPlayerPerSide(){
        return config.getInt("waiting.max_players_per_side", 8);
    }

    public int getMaxSpectators(){
        return config.getInt("waiting.max_spectators", 4);
    }


    public int getMaxSideDiff(){
        return config.getInt("waiting.max_side_diff", 1);
    }

    //硬编码
    public int getCountdownTicks(){
        return 200;
    }

    //对局阶段配置
    public int getMaxDuration(){
        return config.getInt("playing.max_duration", 12000);
    }

    public int getRespawnInvulnerability(){
        return config.getInt("playing.respawn_invulnerability", 60);
    }

    //地图列表
    public List<String> getAvailableMaps(){
        return config.getStringList("maps");
    }
}
