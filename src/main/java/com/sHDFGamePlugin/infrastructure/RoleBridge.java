package com.sHDFGamePlugin.infrastructure;

import com.sHDFGamePlugin.domain.team.ShdfTeam;
import com.sHDFGamePlugin.domain.team.TeamManager;
import com.sHDFGamePlugin.infrastructure.config.ConfigManager;
import com.sHDFGamePlugin.infrastructure.config.MapConfig;
import com.shadowHunterRolesPlugin.api.RoleAPI;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.List;

/**
 * 角色桥接（单例）：封装外部插件 ShadowHunterRolesPlugin 的 RoleAPI。
 * <p>
 * 校验阵营角色池、管理角色占用（可选去重）、按玩家记录当前角色。
 */
public class RoleBridge {

    private static RoleBridge INSTANCE = new RoleBridge();

    private RoleAPI roleAPI;
    private MapConfig currentMapConfig;
    private boolean allowDuplicateRoles;

    private Map<UUID, String> attackerPlayerRoleMap = new HashMap<UUID, String>();
    private Map<UUID, String> defenderPlayerRoleMap = new HashMap<UUID, String>();

    private RoleBridge() {}

    public static RoleBridge getInstance() {
        return INSTANCE;
    }

    public void init(){
        this.roleAPI = Bukkit.getServicesManager().load(RoleAPI.class);
        if(this.roleAPI == null){
            throw new IllegalStateException("Role API 未注册，请检查 ShadowHunterRolesPlugin 是否正确加载");
        }
        this.allowDuplicateRoles = ConfigManager.getInstance().isAllowDuplicateRoles();
    }

    public void setCurrentMapConfig(MapConfig mapConfig){
        this.currentMapConfig = mapConfig;
        clearAllOccupiedRoles();
    }

    public void clearAllOccupiedRoles(){
        List<UUID> allRoledCombatantUUID = new ArrayList<>();
        allRoledCombatantUUID.addAll(attackerPlayerRoleMap.keySet());
        allRoledCombatantUUID.addAll(defenderPlayerRoleMap.keySet());

        for(UUID uuid: allRoledCombatantUUID){
            clearPlayerRole(uuid);
        }

        attackerPlayerRoleMap.clear();
        defenderPlayerRoleMap.clear();
    }

    public List<Player> getAllRoledAttackerPlayers(){
        List<Player> result = new ArrayList<>();
        for(UUID uuid : new ArrayList<>( this.attackerPlayerRoleMap.keySet())){
            Player p = Bukkit.getPlayer(uuid);
            if(p != null){
                result.add(p);
            }
        }
        return result;
    }

    public List<Player> getAllRoledDefenderPlayers(){
        List<Player> result = new ArrayList<>();
        for(UUID uuid : new ArrayList<>(defenderPlayerRoleMap.keySet())){
            Player p = Bukkit.getPlayer(uuid);
            if(p != null){
                result.add(p);
            }
        }
        return result;
    }


    public void clearPlayerRole(UUID uuid){
        String currentRole = roleAPI.getPlayerRoleId(uuid);
        if(currentRole != null && !currentRole.isEmpty()){
            roleAPI.clearPlayerRole(uuid);
            releaseRoleRecord(uuid);
        }
    }

    private void releaseRoleRecord(UUID uuid){
        attackerPlayerRoleMap.remove(uuid);
        defenderPlayerRoleMap.remove(uuid);
    }

    public boolean setPlayerRole(UUID uuid, String roleId){
        //参数有问题，不是战斗人员拒绝设置角色
        Player player = Bukkit.getPlayer(uuid);
        if(player == null || roleId == null || roleId.isEmpty()) return false;
        ShdfTeam shdfTeam = TeamManager.getInstance().getTeam(uuid);
        if(!shdfTeam.isParticipant()) return false;

        //检查所属阵营角色池，如果角色池里面没有将要设置的角色，拒绝设置
        if (currentMapConfig == null) return false;
        List<String> rolePool;
        switch (shdfTeam){
            case ATTACKER -> rolePool = currentMapConfig.getAttackerRoles();
            case DEFENDER -> rolePool = currentMapConfig.getDefenderRoles();
            default -> {
                return false;
            }
        }
        if (!rolePool.contains(roleId)) {
            return false;
        }

        //根据阵营获取对应的角色记录
        Collection<String> occupiedRoles;
        switch (shdfTeam){
            case ATTACKER -> occupiedRoles = attackerPlayerRoleMap.values();
            case DEFENDER -> occupiedRoles = defenderPlayerRoleMap.values();
            default -> {
                return false;
            }
        }

        //如果不允许选择相同角色，判断将要设置的角色是否已经被占用
        if(!allowDuplicateRoles){
            String currentRole = roleAPI.getPlayerRoleId(uuid);
            //如果记录中有使用该角色的玩家并且自己当前角色不是这个角色，拒绝设置角色
            if(occupiedRoles.contains(roleId) && (currentRole == null || !currentRole.equals(roleId))){
                return false;
            }
        }

        //设置角色
        boolean success = roleAPI.setPlayerRole(uuid, roleId);
        if(!success){
            return false;
        }
        switch (shdfTeam){
            case ATTACKER -> attackerPlayerRoleMap.put(uuid, roleId);
            case DEFENDER -> defenderPlayerRoleMap.put(uuid, roleId);
            default -> {
                return false;
            }
        }

        return true;
    }

    //获取玩家角色
    public String getPlayerRole(UUID uuid){
        return roleAPI.getPlayerRoleId(uuid);
    }

    //查询角色描述
    public Component getRoleDisplayName(String roleId){
        return roleAPI.getRoleDisplayName(roleId);
    }

    public Component getRoleDescription(String roleId){
        return roleAPI.getRoleDescription(roleId);
    }

    public Material getRoleIcon(String roleId){
        return roleAPI.getRoleIcon(roleId);
    }

    //查询角色是否存在
    public boolean isValidRoleId(String roleId){
        return roleAPI.isValidRoleId(roleId);
    }
}
