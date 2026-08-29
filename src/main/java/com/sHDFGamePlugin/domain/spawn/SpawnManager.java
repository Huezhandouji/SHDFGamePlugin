package com.sHDFGamePlugin.domain.spawn;

import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.infrastructure.regionExpression.CubeRegion;
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

public class SpawnManager {

    private static final SpawnManager INSTANCE = new SpawnManager();

    //重生队列
    private final Map<UUID, PendingRespawn> respawnQueue = new HashMap<>();

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
    }

    public void clearAll() {
        respawnQueue.clear();
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

    //执行玩家部署: 应用角色，传送，回复状态 然后清除队列中的记录
    public boolean deployPlayer(UUID uuid, String roleId){
        PendingRespawn pending = respawnQueue.get(uuid);
        if(pending == null) return false;
        if(!canRespawn(uuid)) return false;

        Player player = Bukkit.getPlayer(uuid);
        if(player == null) return false;

        //获取出生点区域
        Sector currentSector = SectorManager.getInstance().getCurrentSector();
        if(currentSector == null) return false;

        CubeRegion spawnRegion;
        switch (pending.shdfTeam){
            case ATTACKER -> spawnRegion = currentSector.getAttackerSpawnRegion();
            case DEFENDER -> spawnRegion = currentSector.getDefenderSpawnRegion();
            default -> throw new IllegalArgumentException("Invalid shdfTeam");
        }

        Vector spawnPointVector = spawnRegion.randomPoint();

        //传送并恢复状态
        World world = Bukkit.getWorld(currentMapConfig.getWorld());
        if(world == null){
            GameContext.getInstance().getPlugin().getLogger().warning("[SpawnManager] World in map config not found!");
            return false;
        }
        player.teleport(spawnPointVector.toLocation(world));
        player.setGameMode(GameMode.ADVENTURE);

        PlayerStatus status = TeamManager.getInstance().getPlayerStatus(uuid);
        if(status == null) return false;
        //部署完成，玩家进入战斗状态
        status.setState(PlayerState.IN_BATTLE);

        //设置角色
        boolean roleApplied = RoleBridge.getInstance().setPlayerRole(uuid, roleId);
        if(!roleApplied) return false;

        respawnQueue.remove(uuid);
        return true;
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
