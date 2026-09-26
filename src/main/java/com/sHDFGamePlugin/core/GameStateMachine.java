package com.sHDFGamePlugin.core;

import com.sHDFGamePlugin.phase.FinishedPhase;
import com.sHDFGamePlugin.phase.GamePhase;
import com.sHDFGamePlugin.phase.IdlePhase;
import com.sHDFGamePlugin.phase.PlayingPhase;
import com.sHDFGamePlugin.phase.RoleSelectingPhase;
import com.sHDFGamePlugin.phase.WaitingPhase;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumMap;
import java.util.Map;

/**
 * 游戏状态机：管理 IDLE / WAITING / ROLE_SELECTING / PLAYING / FINISHED 的流转。
 * <p>
 * 每个 {@link GameState} 对应一个 {@link GamePhase}；切换状态时依次调用
 * 旧状态的 onExit 与新状态的 onEnter。
 * <p>
 * 阶段实例由 {@link GameContext} 创建后经构造函数注入，本类不持有任何静态实例。
 */
public class GameStateMachine {

    private final JavaPlugin plugin;
    private final Map<GameState, GamePhase> phases = new EnumMap<>(GameState.class);

    private GameState currentState;

    public GameStateMachine(JavaPlugin plugin, IdlePhase idlePhase, WaitingPhase waitingPhase,
                            RoleSelectingPhase roleSelectingPhase, PlayingPhase playingPhase,
                            FinishedPhase finishedPhase) {
        this.plugin = plugin;
        this.phases.put(GameState.IDLE, idlePhase);
        this.phases.put(GameState.WAITING, waitingPhase);
        this.phases.put(GameState.ROLE_SELECTING, roleSelectingPhase);
        this.phases.put(GameState.PLAYING, playingPhase);
        this.phases.put(GameState.FINISHED, finishedPhase);
    }

    public void start(){
        if(currentState == null){
            transitionTo(GameState.IDLE);
        }
    }

    /**
     * 切换状态：依次调用旧状态 onExit 与新状态 onEnter。
     * <p>
     * 约定（异常安全）：
     * - 目标状态与当前状态相同时直接早退：重复切同一个状态不再重跑 onExit/onEnter，
     *   避免同一阶段被重复初始化而留下两份订阅/常驻任务；
     * - 沿用既有顺序：currentState 在 onEnter 之前更新（基线即如此），因此 onExit 内的重入性
     *   状态切换（如空服回 IDLE）与 onEnter 内的状态判断都以新状态为准，不会切回旧状态；
     * - onEnter 用 try/finally 包住：finally 里无论 onEnter 成功、抛异常还是提前 return，
     *   "已进入新状态"这一事实都不会被破坏（currentState 与日志保持一致），
     *   不会留下"状态机指向旧状态、而旧状态已被 onExit 清理"的半初始化状态；
     * - onEnter 抛异常时记录日志（含状态名与异常）后继续向上抛出，绝不静默吞掉，
     *   下一次 transitionTo 会正常调用新状态的 onExit 做清理。
     */
    public void transitionTo(GameState newState){
        if(newState == null) return;
        //早退守卫：目标状态 == 当前状态时不做任何事
        if(currentState == newState) return;

        if(currentState != null){
            plugin.getLogger().info("退出状态: " + currentState.name());
            GamePhase currentPhase = phases.get(currentState);
            if(currentPhase != null){
                currentPhase.onExit();
            }
        }

        //当前状态先更新：onExit 的重入性切换与 onEnter 内的状态判断都以新状态为准
        currentState = newState;
        try{
            plugin.getLogger().info("进入状态: " + newState.name());
            GamePhase newPhase = phases.get(newState);
            if(newPhase == null){
                plugin.getLogger().severe("状态 " + newState.name() + " 没有对应的阶段实现, 无法调用 onEnter!");
                return;
            }
            newPhase.onEnter();
        }
        catch (Throwable t){
            //记录日志后继续向上抛出：不静默吞异常
            plugin.getLogger().severe("进入状态 " + newState.name() + " 时 onEnter 抛出异常: " + t);
            t.printStackTrace();
            throw t;
        }
        finally{
            //try/finally 语义：onEnter 正常返回、抛异常或提前 return 时，本次都已完整进入目标状态
        }
    }

    public GameState getCurrentState(){
        return currentState;
    }

    /** 按状态取阶段实现；未注册的状态返回 null */
    public GamePhase getPhase(GameState state){
        return phases.get(state);
    }

    public void shutdown(){
        if(currentState != null){
            GamePhase phase = phases.get(currentState);
            if(phase != null){
                phase.onExit();
            }
        }
    }
}
