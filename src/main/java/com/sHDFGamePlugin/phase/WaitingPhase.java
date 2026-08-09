package com.sHDFGamePlugin.phase;

import com.sHDFGamePlugin.config.GameConfig;
import com.sHDFGamePlugin.core.GameManager;
import com.sHDFGamePlugin.core.GameState;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;


public class WaitingPhase {

    private static WaitingPhase instance;

    private final GameManager manager;
    private final GameConfig config;

    private int countdownTaskId = -1;
    private int countdownTicks = 0;

    private WaitingPhase(){
        this.manager = GameManager.getInstance();
        this.config = GameConfig.getInstance();
    }

    static {
        instance = new WaitingPhase();
    }

    public static WaitingPhase getInstance(){
        return instance;
    }

    //玩家加入
    public void autoJoin(Player player){
        GameState state = manager.getState();

        if(state == GameState.PLAYING || state == GameState.FINISHED){
            addAsSpectator(player, Component.text("SHDF>>对局正在进行中, 已自动将你设为旁观者"));
            return;
        }
    }

    private void addAsSpectator(Player player, Component msg){

    }
}
