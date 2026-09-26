package com.sHDFGamePlugin;

import com.sHDFGamePlugin.command.ShdfGameCommand;
import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.infrastructure.item.InteractionManager;
import com.sHDFGamePlugin.listener.FoodLevelKeeper;
import com.sHDFGamePlugin.listener.PlayerJoinListener;
import com.sHDFGamePlugin.listener.PlayerQuitListener;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;


/**
 * 插件主类：启动时校验依赖、创建全局上下文（{@link GameContext}）、注册事件监听与主指令，
 * 并启动状态机；禁用时关闭上下文，让全部组件随引用释放。
 * <p>
 * 本类<b>不再持有静态实例</b>：需要插件实例的组件都从 {@link GameContext#getPlugin()} 取。
 */
public final class SHDFGamePlugin extends JavaPlugin {

    /**
     * 全局上下文（由本类在 onEnable 创建、onDisable 关闭后置空）。
     * <p>所有游戏组件（管理器/控制器/阶段）都由它持有——插件禁用后不再有静态引用把它们
     * （以及其引用的世界、玩家、调度任务）留在堆里。
     */
    private GameContext gameContext;

    //计分板
    private Scoreboard tempScoreboard;

    @Override
    public void onEnable() {

        getLogger().info("SHDFGamePlugin launching...");

        if(!checkDependencies()){
            getServer().getPluginManager().disablePlugin(this);
            getLogger().severe("Failed to launch SHDFGamePlugin.");
            return;
        }

        saveDefaultConfig();

        //一次性装配全部组件（构造期间不注册监听器、不启动调度、不读配置）
        gameContext = new GameContext(this);
        //配置必须在任何组件被使用前加载：缺失必要字段时 fail-fast，插件不进入半初始化状态
        gameContext.getConfigManager().init();

        //计分板：准备/选角阶段与表现层复位共用同一份
        tempScoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        gameContext.setTempScoreboard(tempScoreboard);

        //角色桥接：经 ServicesManager 取外部插件 RoleAPI，未注册时抛出异常
        gameContext.getRoleBridge().init();

        getLogger().info("SHDFGamePlugin successfully enabled!");

        registerListeners();
        registerCommands();

        gameContext.getGameStateMachine().start();
    }

    @Override
    public void onDisable() {
        //置空引用必须放在 finally 里：任何阶段的 onExit 抛异常都不能让它被跳过——
        //否则被服务端持有的插件实例会一直钉住整个上下文，正是本类要消除的泄漏
        try {
            if(gameContext != null){
                //关闭上下文：触发当前阶段 onExit、清空事件总线订阅、注销监听器、关闭所有 GUI
                gameContext.shutdown();
            }
        }
        catch (Throwable t){
            getLogger().severe("关闭游戏上下文时发生异常（已忽略，继续释放引用）: " + t);
            t.printStackTrace();
        }
        finally {
            gameContext = null;
            tempScoreboard = null;
        }
        getLogger().info("ShadowHunterGame disabled!");
    }

    /** 注册事件监听器（交互管理器由上下文持有，这里只负责挂到服务端） */
    private void registerListeners(){
        InteractionManager interactionManager = gameContext.getInteractionManager();
        interactionManager.init();
        Bukkit.getPluginManager().registerEvents(new PlayerJoinListener(), this);
        Bukkit.getPluginManager().registerEvents(new PlayerQuitListener(), this);
        Bukkit.getPluginManager().registerEvents(new FoodLevelKeeper(), this);
    }

    /** 注册主指令 shdfgame（别名 sg） */
    private void registerCommands(){
        PluginCommand shdfGameCommand = getCommand("shdfgame");
        if(shdfGameCommand != null){
            ShdfGameCommand executor = new ShdfGameCommand(gameContext);
            shdfGameCommand.setExecutor(executor);
            shdfGameCommand.setTabCompleter(executor);
        }
    }

    private boolean checkDependencies(){
        if(getServer().getPluginManager().getPlugin("ShadowHunterRolesPlugin") == null){
            getLogger().severe("Failed to find the dependency 'ShadowHunterRolesPlugin'!");
            getLogger().severe("You should install 'ShadowHunterRolesPlugin' first!");
            return false;
        }
        getLogger().info("Succeed to find the dependency 'ShadowHunterRolesPlugin'!");
        return true;
    }

    public Scoreboard getTempScoreboard() {
        return tempScoreboard;
    }

    /** 全局上下文；插件未启用（或已禁用）时为 null */
    public GameContext getGameContext() {
        return gameContext;
    }
}
