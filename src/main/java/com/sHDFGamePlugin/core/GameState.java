package com.sHDFGamePlugin.core;

public enum GameState {

    /** 空闲：无玩家，等待第一人加入 */
    IDLE,
    /** 准备大厅：选队/准备/倒计时 */
    WAITING,
    /** 角色选择：选定整场使用的角色 */
    ROLE_SELECTING,
    /** 对局中：炸弹爆破推进 */
    PLAYING,
    /** 结算 */
    FINISHED

}
