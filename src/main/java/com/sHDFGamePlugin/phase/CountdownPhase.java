package com.sHDFGamePlugin.phase;

import com.sHDFGamePlugin.core.GameContext;
import org.checkerframework.checker.units.qual.C;

public class CountdownPhase implements GamePhase{

    private static final CountdownPhase INSTANCE = new CountdownPhase();

    private CountdownPhase(){
    }

    public static CountdownPhase getInstance(){
        return INSTANCE;
    }

    @Override
    public void onEnter() {
        GameContext.getInstance().getPlugin().getLogger().info("游戏进入 COUNTDOWN 状态");
    }

    @Override
    public void onExit() {

    }
}
