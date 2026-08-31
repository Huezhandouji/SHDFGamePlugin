package com.sHDFGamePlugin;

import com.sHDFGamePlugin.command.ShdfGameCommand;
import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.core.GameStateMachine;
import com.sHDFGamePlugin.infrastructure.RoleBridge;
import com.sHDFGamePlugin.infrastructure.item.InteractionManager;
import com.sHDFGamePlugin.listener.PlayerJoinListener;
import com.sHDFGamePlugin.listener.PlayerQuitListener;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;


/**
 * 插件主类：启动时校验依赖、初始化全局上下文/角色桥接/事件监听，并启动状态机。
 */
public final class SHDFGamePlugin extends JavaPlugin {

    private static SHDFGamePlugin instance;

    //计分板
    Scoreboard tempScoreboard;

    @Override
    public void onEnable() {

        getLogger().info("SHDFGamePlugin launching...");

        if(!checkDependencies()){
            getServer().getPluginManager().disablePlugin(this);
            getLogger().severe("Failed to launch SHDFGamePlugin.");
            return;
        }

        instance = this;



        saveDefaultConfig();

        getLogger().info("SHDFGamePlugin successfully enabled!");

        Bukkit.getPluginManager().registerEvents(InteractionManager.getInstance(), this);
        Bukkit.getPluginManager().registerEvents(new PlayerJoinListener(), this);
        Bukkit.getPluginManager().registerEvents(new PlayerQuitListener(), this);

        GameContext.getInstance().init(this);
        RoleBridge.getInstance().init();

        //创建计分板
        tempScoreboard = Bukkit.getScoreboardManager().getNewScoreboard();

        GameStateMachine.getInstance().start();

        //注册主指令 shdfgame（别名 sg）
        PluginCommand shdfGameCommand = getCommand("shdfgame");
        if(shdfGameCommand != null){
            ShdfGameCommand executor = new ShdfGameCommand();
            shdfGameCommand.setExecutor(executor);
            shdfGameCommand.setTabCompleter(executor);
        }
    }

    @Override
    public void onDisable() {
        GameStateMachine.getInstance().shutdown();
        getLogger().info("ShadowHunterGame disabled!");
    }

    private boolean checkDependencies(){
        if(getServer().getPluginManager().getPlugin("ShadowHunterRolesPlugin") == null){
            getLogger().severe("Failed to find the dependency 'ShadowHunterRolesPlugin'!");
            getLogger().severe("You should install 'ShadowHunterRolesPlugin' first!");
            return false;
        }
        getLogger().info("Succeeded to find the dependency 'ShadowHunterRolesPlugin'!");
        return true;
    }

    public Scoreboard getTempScoreboard() {
        return tempScoreboard;
    }

    public static SHDFGamePlugin getInstance(){
        return instance;
    }
}
