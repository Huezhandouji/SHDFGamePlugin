package com.sHDFGamePlugin.phase;

import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.core.GameState;
import com.sHDFGamePlugin.infrastructure.GameEventBus;
import com.sHDFGamePlugin.infrastructure.event.ShdfPlayerJoinEvent;

/**
 * 空闲阶段：无玩家时驻留；首个玩家加入即转入 WAITING。
 * <p>
 * 实例由 {@link GameContext} 创建并持有，本类没有静态单例。
 */
public class IdlePhase implements GamePhase{

    private final GameContext ctx;

    private GameEventBus.Subscription joinSubscription;

    public IdlePhase(GameContext ctx){
        this.ctx = ctx;
    }

    @Override
    public void onEnter() {
        joinSubscription = GameEventBus.subscribe(ShdfPlayerJoinEvent.class, event -> {
            ctx.getGameStateMachine().transitionTo(GameState.WAITING);
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
