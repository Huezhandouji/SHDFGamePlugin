package com.sHDFGamePlugin.domain.sector;

import com.sHDFGamePlugin.domain.team.PlayerStatus;
import com.sHDFGamePlugin.domain.team.Team;
import com.sHDFGamePlugin.domain.team.TeamManager;
import com.sHDFGamePlugin.infrastructure.GameEventBus;
import com.sHDFGamePlugin.infrastructure.event.SectorCapturedEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

public class SectorManager {

    private static final SectorManager INSTANCE = new SectorManager();

    private List<Sector> sectors;
    private int currentIndex;
    private boolean allCaptured;

    //以tick计
    private int preheatProgress;
    private int captureProgress;
    private boolean preheatDone;

    private SectorTimeLimit currentTimeLimit;

    private SectorManager(){}

    public static SectorManager getInstance(){
        return INSTANCE;
    }

    //加载地图列表，激活第一个据点
    public void loadMap(List<Sector> sectors){
        this.sectors = sectors;
        this.currentIndex = 0;
        this.allCaptured = false;
        this.preheatProgress = 0;
        this.captureProgress = 0;
        this.preheatDone = false;

        if(sectors != null && !sectors.isEmpty()){
            activateCurrentSector();
        }
        else {
            this.allCaptured = true;
        }
    }

    public void update(){
        if(allCaptured || sectors == null || sectors.isEmpty()) return;

        Sector currentSector = getCurrentSector();
        if(currentSector == null) return;

        int attackerCount = countAliveCombatantsInRegion(Team.ATTACKER, currentSector.getRegion());
        int defenderCount = countAliveCombatantsInRegion(Team.DEFENDER, currentSector.getRegion());

        //当进攻方占优势
        if(attackerCount > defenderCount){
            if(!preheatDone){
                preheatProgress += 1;
                if(preheatProgress >= currentSector.getPreheatTime()){
                    preheatDone = true;
                    preheatProgress = 0;
                }
            }
            else{
                captureProgress += 1;
                if(captureProgress >= currentSector.getCaptureTime()){
                    captureProgress = 0;
                    preheatDone = false;
                    onSectorCaptured(currentSector);
                }
            }
        }
        else{
            //当进攻方不再占优
            preheatDone = false;
            preheatProgress = 0;
        }
    }

    private void onSectorCaptured(Sector sector){
        //发布一个事件，由PlayingPhase监听
        GameEventBus.publish(new SectorCapturedEvent(sector));

        //停止时限倒计时
        if(currentTimeLimit != null){
            currentTimeLimit.stop();
            currentTimeLimit = null;
        }

        if(currentIndex + 1 < sectors.size()){
            currentIndex += 1;
            activateCurrentSector();
        }
        else{
            allCaptured = true;
        }
    }

    private void activateCurrentSector(){
        Sector current = getCurrentSector();
        if(current == null) return;

        preheatProgress = 0;
        captureProgress = 0;
        preheatDone = false;

        //启动倒计时
        currentTimeLimit = new SectorTimeLimit(current);
        currentTimeLimit.start();
    }

    private int countAliveCombatantsInRegion(Team team, Region region){
        int count = 0;
        TeamManager teamManager = TeamManager.getInstance();
        for(UUID uuid : teamManager.getAllPlayersUuidsInTeam(team)){
            Player player = Bukkit.getPlayer(uuid);
            if(player == null || player.isDead() || !player.isOnline()) continue;
            PlayerStatus status = teamManager.getPlayerStatus(uuid);
            if(!status.isInBattle()) continue;

            if(region.contains(player.getLocation().toVector())){
                count += 1;
            }
        }
        return count;
    }

    public Sector getCurrentSector(){
        if(sectors == null || currentIndex < 0 || currentIndex >= sectors.size()) return null;
        return sectors.get(currentIndex);
    }

    public boolean isAllCaptured(){
        return allCaptured;
    }

    public int getPreheatProgress(){
        return preheatProgress;
    }

    public int getCaptureProgress(){
        return captureProgress;
    }

    public boolean isPreheatDone(){
        return preheatDone;
    }

    public int getCurrentTimeLimitRemaining(){
        return currentTimeLimit != null ? currentTimeLimit.getRemainingTicks() : 0;
    }

    public void cleanup(){
        if(currentTimeLimit != null){
            currentTimeLimit.stop();
            currentTimeLimit = null;
        }
        sectors = null;
        allCaptured = false;
        currentIndex = 0;
        preheatProgress = 0;
        captureProgress = 0;
        preheatDone = false;
    }
}
