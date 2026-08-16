package com.sHDFGamePlugin;

import com.sHDFGamePlugin.command.ACommand;
import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.core.GameStateMachine;
import com.sHDFGamePlugin.infrastructure.item.InteractionManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;


public final class SHDFGamePlugin extends JavaPlugin {

    private static SHDFGamePlugin instance;

    @Override
    public void onEnable() {

        getLogger().info("SHDFGamePlugin launching...");

        if(!checkDependencies()){
            getServer().getPluginManager().disablePlugin(this);
            getLogger().severe("Failed to launch SHDFGamePlugin.");
            return;
        }

        instance = this;

        GameContext.getInstance().init(this);
        GameStateMachine.getInstance().start();

        saveDefaultConfig();

        getLogger().info("SHDFGamePlugin successfully enabled!");

        Bukkit.getPluginManager().registerEvents(InteractionManager.getInstance(), this);

        getCommand("shdf").setExecutor(new ACommand());


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

    public static SHDFGamePlugin getInstance(){
        return instance;
    }
}
