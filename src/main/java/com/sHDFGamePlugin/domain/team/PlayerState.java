package com.sHDFGamePlugin.domain.team;

public enum PlayerState {

    /** 等待阶段：在大厅 */
    WAITING,
    /** 角色选择阶段 */
    ROLE_SELECTING,
    /** 部署阶段：死亡后等待部署 */
    DEPLOYING,
    /** 战斗中：已部署进场 */
    IN_BATTLE

}
