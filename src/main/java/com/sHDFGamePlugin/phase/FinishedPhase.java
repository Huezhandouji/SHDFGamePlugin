package com.sHDFGamePlugin.phase;

import com.sHDFGamePlugin.core.GameContext;

public class FinishedPhase implements GamePhase {

    private static final FinishedPhase INSTANCE = new FinishedPhase();

    private FinishedPhase() {}

    public static FinishedPhase getInstance() {
        return INSTANCE;
    }


    @Override
    public void onEnter() {
        GameContext.getInstance().getPlugin().getLogger().info("游戏进入 FINISHED 状态");
    }

    @Override
    public void onExit() {

    }
}
