package com.sHDFGamePlugin.core;

import com.sHDFGamePlugin.infrastructure.config.ConfigManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 全局游戏上下文（单例容器）。
 * <p>
 * 持有插件实例与配置管理器，供各模块获取依赖。
 */
public class GameContext {

    private static final GameContext INSTANCE = new GameContext();

    private JavaPlugin plugin;
    private ConfigManager configManager;

    private GameContext(){
    }

    public static GameContext getInstance(){
        return INSTANCE;
    }

    public void init(JavaPlugin plugin){
        this.plugin = plugin;
        this.configManager = ConfigManager.getInstance();
        this.configManager.init(plugin);
    }

    public JavaPlugin getPlugin(){
        return plugin;
    }

    public ConfigManager getConfigManager(){
        return configManager;
    }

}
