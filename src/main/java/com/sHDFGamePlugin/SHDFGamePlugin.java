package com.sHDFGamePlugin;

import org.bukkit.plugin.java.JavaPlugin;


public final class SHDFGamePlugin extends JavaPlugin {

    private static SHDFGamePlugin instance;

    @Override
    public void onEnable() {
        instance = this;

        if(!checkDependencies()){
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();

    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
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
