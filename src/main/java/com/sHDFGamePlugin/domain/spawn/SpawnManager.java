package com.sHDFGamePlugin.domain.spawn;

import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.infrastructure.regionNotation.CubeRegion;
import com.sHDFGamePlugin.domain.sector.Sector;
import com.sHDFGamePlugin.domain.sector.SectorManager;
import com.sHDFGamePlugin.domain.team.PlayerState;
import com.sHDFGamePlugin.domain.team.PlayerStatus;
import com.sHDFGamePlugin.domain.team.ShdfTeam;
import com.sHDFGamePlugin.domain.team.TeamManager;
import com.sHDFGamePlugin.infrastructure.RoleBridge;
import com.sHDFGamePlugin.infrastructure.config.MapConfig;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 重生/部署管理（单例）：玩家死亡后加入重生队列等待倒计时，倒计时结束可部署进场。
 * <p>
 * 部署时按当前据点的阵营出生区传送、恢复状态并应用角色。
 */
public class SpawnManager {

    private static final SpawnManager INSTANCE = new SpawnManager();

    //重生队列
    private final Map<UUID, PendingRespawn> respawnQueue = new HashMap<>();

    //部署失败原因去重记录：玩家 uuid -> 最近一次已记录的原因（同一玩家同一原因只记一次，避免每 tick 重试刷屏）
    private final Map<UUID, String> deployFailureReasonLogged = new HashMap<>();

    private MapConfig currentMapConfig;

    private SpawnManager() {}

    public static SpawnManager getInstance() {
        return INSTANCE;
    }

    //设置当前地图配置
    public void setCurrentMapConfig(MapConfig mapConfig) {
        this.currentMapConfig = mapConfig;
    }

    //玩家死亡时调用，将其加入重生队列
    public void addPlayer(UUID uuid, ShdfTeam shdfTeam) {
        if(currentMapConfig == null) return;
        //必须是战斗人员
        if(!shdfTeam.isParticipant()) return;
        int waitTicks;
        switch (shdfTeam){
            case ATTACKER -> waitTicks = currentMapConfig.getAttackerRespawnTime();
            case DEFENDER -> waitTicks = currentMapConfig.getDefenderRespawnTime();
            default -> {
                GameContext.getInstance().getPlugin().getLogger().warning("[SpawnManager] Invalid ShdfTeam Type");
                return;
            }
        }

        respawnQueue.put(uuid, new PendingRespawn(uuid, shdfTeam, waitTicks));
    }

    public void removePlayer(UUID uuid) {
        respawnQueue.remove(uuid);
        deployFailureReasonLogged.remove(uuid);
    }

    public void clearAll() {
        respawnQueue.clear();
        deployFailureReasonLogged.clear();
    }

    public void update(){
        for(PendingRespawn pending : respawnQueue.values()){
            if(pending.remainingTicks > 0){
                pending.remainingTicks -= 1;
            }
        }
    }

    public int getRemainingRespawnTime(UUID uuid) {
        PendingRespawn pending = respawnQueue.get(uuid);
        return pending != null ? pending.remainingTicks : 0;
    }

    public boolean canRespawn(UUID uuid){
        PendingRespawn pending = respawnQueue.get(uuid);
        return pending != null && pending.remainingTicks <= 0;
    }

    //执行玩家部署: 应用角色，传送，恢复状态 然后清除队列中的记录
    //注意：角色应用失败时不传送/不改状态，保持 DEPLOYING 等待下个 tick 重试，避免"已传送但无物品"的半部署状态
    public boolean deployPlayer(UUID uuid, String roleId){
        PendingRespawn pending = respawnQueue.get(uuid);
        if(pending == null){
            logDeployFailure(uuid, "不在重生队列中(未加入队列或已被移除)");
            return false;
        }
        if(!canRespawn(uuid)){
            logDeployFailure(uuid, "重生倒计时未结束");
            return false;
        }

        Player player = Bukkit.getPlayer(uuid);
        if(player == null){
            logDeployFailure(uuid, "玩家不在线");
            return false;
        }

        Sector currentSector = SectorManager.getInstance().getCurrentSector();
        if(currentSector == null){
            logDeployFailure(uuid, "当前据点为 null(据点未加载或已全部攻占)");
            return false;
        }

        World world = Bukkit.getWorld(currentMapConfig.getWorld());
        if(world == null){
            logDeployFailure(uuid, "地图世界不存在: " + currentMapConfig.getWorld());
            return false;
        }

        PlayerStatus status = TeamManager.getInstance().getPlayerStatus(uuid);
        if(status == null){
            logDeployFailure(uuid, "缺少 PlayerStatus(玩家不在队伍/状态已过期)");
            return false;
        }

        //先应用角色：失败则不部署（保持 DEPLOYING），由 PlayingPhase 下个 tick 重试
        //（RoleBridge.setPlayerRole 内部对每个失败分支另有可区分原因日志）
        boolean roleApplied = RoleBridge.getInstance().setPlayerRole(uuid, roleId);
        if(!roleApplied){
            logDeployFailure(uuid, "角色应用失败: roleId=" + roleId);
            return false;
        }

        //获取出生点区域
        CubeRegion spawnRegion;
        switch (pending.shdfTeam){
            case ATTACKER -> spawnRegion = currentSector.getAttackerSpawnRegion();
            case DEFENDER -> spawnRegion = currentSector.getDefenderSpawnRegion();
            default -> throw new IllegalArgumentException("Invalid shdfTeam");
        }
        Vector spawnPointVector = spawnRegion.randomPoint();

        //传送并恢复状态
        player.teleport(spawnPointVector.toLocation(world));
        player.setGameMode(GameMode.ADVENTURE);
        //部署完成，玩家进入战斗状态
        status.setState(PlayerState.IN_BATTLE);

        respawnQueue.remove(uuid);
        deployFailureReasonLogged.remove(uuid);
        return true;
    }

    /**
     * 记录部署失败原因（含玩家名与 uuid）。
     * <p>
     * 同一玩家同一原因只记一次：部署失败会由 PlayingPhase 每 tick 重试，逐 tick 打日志会把最新日志刷没；
     * 原因变化（例如从"角色应用失败"变成"玩家不在线"）或部署成功后清除记录，因此仍能定位新问题。
     */
    private void logDeployFailure(UUID uuid, String reason){
        if(reason.equals(deployFailureReasonLogged.get(uuid))) return;
        deployFailureReasonLogged.put(uuid, reason);

        Player player = Bukkit.getPlayer(uuid);
        String playerName = player != null ? player.getName() : "unknown";
        GameContext.getInstance().getPlugin().getLogger().warning(
                "[SpawnManager] 玩家 " + playerName + " (" + uuid + ") 部署失败: " + reason);
    }



    private static class PendingRespawn{
        final UUID uuid;
        final ShdfTeam shdfTeam;
        int remainingTicks;

        PendingRespawn(UUID uuid, ShdfTeam shdfTeam, int remainingTicks){
            this.uuid = uuid;
            this.shdfTeam = shdfTeam;
            this.remainingTicks = remainingTicks;
        }
    }
}
