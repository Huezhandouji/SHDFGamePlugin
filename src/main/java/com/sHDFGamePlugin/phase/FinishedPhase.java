package com.sHDFGamePlugin.phase;

import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.core.GameState;
import com.sHDFGamePlugin.core.GameStateMachine;
import com.sHDFGamePlugin.domain.sector.SectorManager;
import com.sHDFGamePlugin.domain.spawn.SpawnManager;
import com.sHDFGamePlugin.domain.team.TeamManager;
import com.sHDFGamePlugin.domain.ticket.TicketManager;
import com.sHDFGamePlugin.infrastructure.DisconnectProtection;
import com.sHDFGamePlugin.infrastructure.RoleBridge;
import com.sHDFGamePlugin.infrastructure.config.ConfigManager;
import com.sHDFGamePlugin.infrastructure.gui.ChestGui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.ArrayList;

/**
 * 结算阶段：对局结束后集中清理系统状态，并在重置完成后将所有玩家踢出游戏。
 * <p>
 * 流程：清理对局机制/管理器 → 重置在线玩家运行时状态 → 踢出全部玩家 → 回到 IDLE 等待下一局。
 * 本阶段不订阅 quit 事件，玩家被踢出时不会触发任何"保留状态"逻辑，从机制上杜绝跨局残留。
 */
public class FinishedPhase implements GamePhase {

    private static final FinishedPhase INSTANCE = new FinishedPhase();

    private FinishedPhase() {}

    public static FinishedPhase getInstance() {
        return INSTANCE;
    }

    @Override
    public void onEnter() {
        GameContext.getInstance().getPlugin().getLogger().info("进入 FINISHED 状态, 开始对局清理...");

        //1. 重置对局机制与管理器状态（防止跨局残留）
        resetSystemState();
        //2. 重置在线玩家的运行时状态（清背包/状态等，避免旧局物品带入下一局）
        resetOnlinePlayers();
        //3. 重置完成后，将所有玩家踢出游戏
        kickAllPlayers();
        //4. 回到 IDLE，等待玩家重新加入开新局
        GameStateMachine.getInstance().transitionTo(GameState.IDLE);
    }

    @Override
    public void onExit() {
    }

    /** 集中清理本局产生的所有系统状态 */
    private void resetSystemState(){
        //据点/炸弹：停止引信与据点时限任务
        SectorManager.getInstance().cleanup();
        //队伍/玩家状态：清空全部 PlayerStatus（含 selectedRoleId / state）
        TeamManager.getInstance().reset();
        //重生队列
        SpawnManager.getInstance().clearAll();
        //票数
        TicketManager.getInstance().reset();
        //角色占用记录清空，重复规则还原为配置默认值
        RoleBridge.getInstance().clearAllOccupiedRoles();
        RoleBridge.getInstance().setAllowDuplicateRoles(ConfigManager.getInstance().isAllowDuplicateRoles());
        //取消所有挂起的断线保护任务（防止残留回调跨局触发）
        DisconnectProtection.getInstance().cancelAll();
        //关闭所有打开的游戏 GUI
        ChestGui.closeAllGuis();
    }

    /** 重置每个在线玩家的运行时状态（背包/经验/状态效果/游戏模式等） */
    private void resetOnlinePlayers(){
        for(Player player : Bukkit.getOnlinePlayers()){
            player.getInventory().clear();
            player.getEnderChest().clear();
            player.setHealth(player.getMaxHealth());
            player.setFoodLevel(20);
            player.setSaturation(5f);
            player.setLevel(0);
            player.setExp(0f);
            player.setFireTicks(0);
            player.setFallDistance(0);
            player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
            player.setGameMode(GameMode.ADVENTURE);
            //复位等待重生/死亡可能遗留的碰撞状态（隐身效果随上方"清除全部药水效果"一并移除）
            player.setCollidable(true);
            player.closeInventory();
        }
    }

    /** 将所有在线玩家踢出服务器（本阶段未订阅 quit 事件，踢出不触发任何保留逻辑） */
    private void kickAllPlayers(){
        for(Player player : new ArrayList<>(Bukkit.getOnlinePlayers())){
            player.kick(Component.text("本场对局已结束, 请重新加入开始新对局", NamedTextColor.GREEN, TextDecoration.BOLD));
        }
    }
}
