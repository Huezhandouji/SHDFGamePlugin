package com.sHDFGamePlugin.core;

import com.sHDFGamePlugin.phase.*;

import java.util.EnumMap;
import java.util.Map;

public class GameStateMachine {

    private static final GameStateMachine INSTANCE = new GameStateMachine();

    private GameState currentState;
    private final Map<GameState, GamePhase> phases;

    private GameStateMachine(){
        phases = new EnumMap<>(GameState.class);
        phases.put(GameState.IDLE, IdlePhase.getInstance());
        phases.put(GameState.WAITING, WaitingPhase.getInstance());
        phases.put(GameState.PLAYING, PlayingPhase.getInstance());
        phases.put(GameState.FINISHED, FinishedPhase.getInstance());
    }

    public static GameStateMachine getInstance(){
        return INSTANCE;
    }

    public void start(){
        if(currentState == null){
            transitionTo(GameState.IDLE);
        }
    }

    public void transitionTo(GameState newState){
        if(currentState != null){
            GameContext.getInstance().getPlugin().getLogger().info("退出状态: " + currentState.name());
            phases.get(currentState).onExit();
        }

        currentState = newState;
        GameContext.getInstance().getPlugin().getLogger().info("进入状态: " + newState.name());
        phases.get(newState).onEnter();
    }

    public GameState getCurrentState(){
        return currentState;
    }

    public void shutdown(){
        if(currentState != null){
            phases.get(currentState).onExit();
        }
    }
}
