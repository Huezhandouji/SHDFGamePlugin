package com.sHDFGamePlugin.phase;

/**
 * 游戏阶段接口：状态进入/退出时的生命周期回调。
 * <p>
 * 实现类通常为单例：onEnter 注册事件订阅，onExit 清理资源。
 */
public interface GamePhase {

    void onEnter();

    void onExit();
}
