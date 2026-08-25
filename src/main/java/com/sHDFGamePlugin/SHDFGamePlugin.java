package com.sHDFGamePlugin;

import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.core.GameStateMachine;
import com.sHDFGamePlugin.infrastructure.RoleBridge;
import com.sHDFGamePlugin.infrastructure.item.InteractionManager;
import com.sHDFGamePlugin.listener.PlayerJoinListener;
import com.sHDFGamePlugin.listener.PlayerQuitListener;
import org.bukkit.Bukkit;
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



        saveDefaultConfig();

        getLogger().info("SHDFGamePlugin successfully enabled!");

        Bukkit.getPluginManager().registerEvents(InteractionManager.getInstance(), this);
        Bukkit.getPluginManager().registerEvents(new PlayerJoinListener(), this);
        Bukkit.getPluginManager().registerEvents(new PlayerQuitListener(), this);

        GameContext.getInstance().init(this);
        RoleBridge.getInstance().init();
        GameStateMachine.getInstance().start();


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
