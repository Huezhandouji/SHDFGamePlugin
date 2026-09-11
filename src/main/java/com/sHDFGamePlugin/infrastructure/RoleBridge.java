package com.sHDFGamePlugin.infrastructure;

import com.sHDFGamePlugin.domain.team.ShdfTeam;
import com.sHDFGamePlugin.domain.team.TeamManager;
import com.sHDFGamePlugin.infrastructure.config.ConfigManager;
import com.sHDFGamePlugin.infrastructure.config.MapConfig;
import com.shadowHunterRolesPlugin.api.RoleAPI;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
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

    //角色应用失败原因去重记录：玩家 uuid -> 最近一次已记录的原因（同一玩家同一原因只记一次，避免部署重试刷屏）
    private final Map<UUID, String> roleFailureReasonLogged = new HashMap<>();

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

    /** 设置本局是否允许重复角色（选角阶段按人数动态调整） */
    public void setAllowDuplicateRoles(boolean allow){
        this.allowDuplicateRoles = allow;
    }

    public boolean isAllowDuplicateRoles(){
        return allowDuplicateRoles;
    }

    /** 指定阵营当前已被占用的角色 id 集合 */
    public Collection<String> getOccupiedRoles(ShdfTeam shdfTeam){
        return switch (shdfTeam){
            case ATTACKER -> attackerPlayerRoleMap.values();
            case DEFENDER -> defenderPlayerRoleMap.values();
            default -> List.of();
        };
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
        roleFailureReasonLogged.clear();
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
        }
        //无论角色插件当前是否仍记录该角色，都释放本地占用表，
        //避免死亡/观战等场景下角色插件已清空角色但本地占用表残留导致重新部署失败
        releaseRoleRecord(uuid);
    }

    private void releaseRoleRecord(UUID uuid){
        attackerPlayerRoleMap.remove(uuid);
        defenderPlayerRoleMap.remove(uuid);
        roleFailureReasonLogged.remove(uuid);
    }

    public boolean setPlayerRole(UUID uuid, String roleId){
        //参数有问题，不是战斗人员拒绝设置角色
        Player player = Bukkit.getPlayer(uuid);
        if(player == null){
            return failRole(uuid, roleId, "玩家不在线");
        }
        if(roleId == null || roleId.isEmpty()){
            return failRole(uuid, roleId, "未选择角色(selectedRoleId 为空)");
        }
        ShdfTeam shdfTeam = TeamManager.getInstance().getTeam(uuid);
        if(!shdfTeam.isParticipant()){
            return failRole(uuid, roleId, "阵营非参战方: " + shdfTeam);
        }

        //检查所属阵营角色池，如果角色池里面没有将要设置的角色，拒绝设置
        if (currentMapConfig == null) {
            return failRole(uuid, roleId, "未设置当前地图配置(角色池来源缺失)");
        }
        List<String> rolePool;
        switch (shdfTeam){
            case ATTACKER -> rolePool = currentMapConfig.getAttackerRoles();
            case DEFENDER -> rolePool = currentMapConfig.getDefenderRoles();
            default -> {
                return failRole(uuid, roleId, "阵营无角色池: " + shdfTeam);
            }
        }
        if (!rolePool.contains(roleId)) {
            return failRole(uuid, roleId, "角色不在本方角色池: " + roleId);
        }

        //根据阵营获取对应的角色记录
        Collection<String> occupiedRoles;
        switch (shdfTeam){
            case ATTACKER -> occupiedRoles = attackerPlayerRoleMap.values();
            case DEFENDER -> occupiedRoles = defenderPlayerRoleMap.values();
            default -> {
                return failRole(uuid, roleId, "阵营无角色占用表: " + shdfTeam);
            }
        }

        //如果不允许选择相同角色，判断将要设置的角色是否已经被占用
        if(!allowDuplicateRoles){
            String currentRole = roleAPI.getPlayerRoleId(uuid);
            //如果记录中有使用该角色的玩家并且自己当前角色不是这个角色，拒绝设置角色
            if(occupiedRoles.contains(roleId) && (currentRole == null || !currentRole.equals(roleId))){
                return failRole(uuid, roleId, "角色已被同队玩家占用(当前不允许重复角色)");
            }
        }

        //设置角色
        boolean success = roleAPI.setPlayerRole(uuid, roleId);
        if(!success){
            return failRole(uuid, roleId, "RoleAPI.setPlayerRole 返回 false");
        }
        switch (shdfTeam){
            case ATTACKER -> attackerPlayerRoleMap.put(uuid, roleId);
            case DEFENDER -> defenderPlayerRoleMap.put(uuid, roleId);
            default -> {
                return failRole(uuid, roleId, "阵营无角色记录表: " + shdfTeam);
            }
        }

        roleFailureReasonLogged.remove(uuid);
        return true;
    }

    /**
     * 记录角色应用失败原因（含玩家名与 uuid）并返回 false。
     * <p>
     * 同一玩家同一原因只记一次：部署失败会被每 tick 重试，逐 tick 打日志会刷屏；
     * 原因变化或设置成功后清除记录，因此仍能定位新问题。
     */
    private boolean failRole(UUID uuid, String roleId, String reason){
        if(!reason.equals(roleFailureReasonLogged.get(uuid))){
            roleFailureReasonLogged.put(uuid, reason);

            Player player = Bukkit.getPlayer(uuid);
            String playerName = player != null ? player.getName() : "unknown";
            Bukkit.getLogger().warning("[RoleBridge] 玩家 " + playerName + " (" + uuid
                    + ") 角色应用失败: roleId=" + roleId + " 原因=" + reason);
        }
        return false;
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

    /**
     * 获取实体的最后伤害者 UUID（击杀归属用）。
     * <p>
     * 注意：角色插件的伤害做过特殊处理，查找"最后伤害者"必须用此方法，
     * 不要使用原版 {@code LivingEntity#getKiller()} 等原版途径。
     */
    public UUID getLastDamagerUuid(LivingEntity entity){
        return roleAPI.getLastDamagerUuid(entity);
    }
}
