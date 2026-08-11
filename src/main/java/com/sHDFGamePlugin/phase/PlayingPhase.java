package com.sHDFGamePlugin.phase;

import com.sHDFGamePlugin.core.GameContext;

public class PlayingPhase implements GamePhase {

    private static final PlayingPhase INSTANCE = new PlayingPhase();

    private PlayingPhase() {}

    public static PlayingPhase getInstance() {
        return INSTANCE;
    }


    @Override
    public void onEnter() {
        GameContext.getInstance().getPlugin().getLogger().info("游戏进入 PLAYING 状态");
    }

    @Override
    public void onExit() {

    }
}
