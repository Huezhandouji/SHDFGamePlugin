package com.sHDFGamePlugin.phase;

import com.sHDFGamePlugin.core.GameContext;

public class IdlePhase implements GamePhase{

    private static final IdlePhase INSTANCE = new IdlePhase();

    private IdlePhase(){
    }

    public static IdlePhase getInstance(){
    return INSTANCE;
    }

    @Override
    public void onEnter() {
        GameContext.getInstance().getPlugin().getLogger().info("游戏进入 IDLE 状态，等待玩家加入");
    }

    @Override
    public void onExit() {

    }
}
