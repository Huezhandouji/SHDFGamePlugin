package com.sHDFGamePlugin.phase;

import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.core.GameState;
import com.sHDFGamePlugin.core.GameStateMachine;
import com.sHDFGamePlugin.infrastructure.GameEventBus;
import com.sHDFGamePlugin.infrastructure.event.ShdfPlayerJoinEvent;

public class IdlePhase implements GamePhase{

    private static final IdlePhase INSTANCE = new IdlePhase();

    private IdlePhase(){}

    public static IdlePhase getInstance(){
        return INSTANCE;
    }

    private GameEventBus.Subscription joinSubscription;

    @Override
    public void onEnter() {
        joinSubscription = GameEventBus.subscribe(ShdfPlayerJoinEvent.class, event -> {
            GameStateMachine.getInstance().transitionTo(GameState.WAITING);
        });
    }

    @Override
    public void onExit() {
        if(joinSubscription != null){
            joinSubscription.unsubscribe();
            joinSubscription = null;
        }
    }
}
