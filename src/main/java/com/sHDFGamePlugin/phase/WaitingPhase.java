package com.sHDFGamePlugin.phase;

import com.sHDFGamePlugin.core.GameContext;

public class WaitingPhase implements GamePhase {

    private static final WaitingPhase INSTANCE = new WaitingPhase();

    private WaitingPhase(){
    }

    public static WaitingPhase getInstance(){
        return INSTANCE;
    }


    @Override
    public void onEnter() {
        GameContext.getInstance().getPlugin().getLogger().info("游戏进入 WAITING 状态");
    }

    @Override
    public void onExit() {

    }
}
