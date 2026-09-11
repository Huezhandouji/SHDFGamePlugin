package com.sHDFGamePlugin.phase.playing;

import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.domain.sector.SectorManager;
import com.sHDFGamePlugin.domain.team.PlayerStatus;
import com.sHDFGamePlugin.domain.team.ShdfTeam;
import com.sHDFGamePlugin.domain.team.TeamManager;
import com.sHDFGamePlugin.infrastructure.GameEventBus;
import com.sHDFGamePlugin.infrastructure.RoleBridge;
import com.sHDFGamePlugin.infrastructure.config.ConfigManager;
import com.sHDFGamePlugin.infrastructure.config.MapConfig;
import com.sHDFGamePlugin.infrastructure.event.BombExplodedEvent;
import com.sHDFGamePlugin.infrastructure.event.InventoryClickGameItemEvent;
import com.sHDFGamePlugin.infrastructure.gui.ChestGui;
import com.sHDFGamePlugin.infrastructure.gui.RoleSelectionGui;
import com.sHDFGamePlugin.infrastructure.item.GameItemRegistry;
import com.sHDFGamePlugin.util.MessageUtil;
import com.sHDFGamePlugin.util.SoundUtil;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 区域推进间隔（间歇期）模块：当前据点被攻占 → 下一个据点正式开启之间的状态机。
 * <p>
 * 机制（地图级配置 {@code sector_advance_interval}，tick）：
 * <ul>
 *     <li>当前据点全部炸弹爆炸且<b>还有下一个据点</b>时进入间歇期，持续该配置时长；</li>
 *     <li>间歇期内新据点"已激活未开启"：炸弹不可交互（开始路径 + 进行中读条两条路径都被
 *     {@link BombInteractionController} 挡住），据点时限尚未起表——<b>间歇期不消耗进攻方时限</b>；</li>
 *     <li>倒计时结束调用 {@link SectorManager#openCurrentSector()} 正式开启新据点（时限从此刻起算）；</li>
 *     <li>间歇期内战斗菜单出现"切换角色"入口，点击打开角色选择子菜单（复用
 *     {@link RoleSelectionGui}），选择结果写入 {@code PlayerStatus.selectedRoleId}，在玩家下次
 *     部署时由 {@code SpawnManager.deployPlayer → RoleBridge.setPlayerRole} 生效；间歇期结束后
 *     该入口变为不可点击的屏障，已打开的菜单会被刷成禁用态。</li>
 * </ul>
 * 反馈节流：开始/结束各广播一次；剩余秒数只在秒数变化时刷新 ActionBar，不每 tick 广播。
 * <p>
 * 生命周期：{@link #start()} / {@link #stop()} 由门面 {@code PlayingPhase.onEnter/onExit} 成对调用。
 * <b>订阅顺序</b>：{@code start()} 必须早于 {@code SectorProgressController.subscribe()}——GameEventBus
 * 按注册顺序分发，间歇期要在"据点推进"把新据点激活之前看到"当前据点全部炸弹爆炸"这一刻。
 */
public final class IntermissionController {

    private static final IntermissionController INSTANCE = new IntermissionController();

    /** 间歇期切角色子菜单的角色按钮 GameItem id 前缀（与选角阶段同布局、不同前缀，避免 id 冲突） */
    private static final String ROLE_BUTTON_ID_PREFIX = "gameItem_playingPhase_role_";

    private IntermissionController() {}

    public static IntermissionController getInstance() {
        return INSTANCE;
    }

    // ==================== 状态 ====================

    //间歇期是否进行中
    private boolean intermissionActive;
    //间歇期总时长（tick）与剩余（tick）
    private int totalTicks;
    private int remainingTicks;
    //剩余秒数播报去重：只在秒数变化时刷新（不每 tick 广播）
    private int lastAnnouncedSecond = -1;

    private ScheduledTask tickTask;

    private GameEventBus.Subscription bombExplodedSubscription;
    private GameEventBus.Subscription inventoryClickSubscription;

    //角色选择 GUI 组件：可点击版（间歇期内打开）与只读版（间歇期结束时把已打开的菜单刷成禁用态）
    private RoleSelectionGui switchRoleGui;
    private RoleSelectionGui readOnlyRoleGui;

    //角色按钮 GameItem id -> roleId（点击回调反查；同一 roleId 可能同时出现在两队角色池，故以 id 为键）
    private final Map<String, String> roleIdByButtonId = new HashMap<>();

    //已打开的战斗菜单 / 切角色子菜单：间歇期开始与结束时刷新按钮状态（GUI 关闭后自动丢弃跟踪）
    private final Map<UUID, ChestGui> trackedBattleMenus = new HashMap<>();
    private final Map<UUID, ChestGui> trackedSubmenus = new HashMap<>();

    // ==================== 生命周期 ====================

    /**
     * 注册本模块：建 GUI 组件 + 注册两队角色按钮 + 订阅事件。
     * <p>
     * <b>必须由门面在 {@code SectorProgressController.subscribe()} 之前调用</b>（见类注释的订阅顺序说明）。
     */
    public void start(){
        switchRoleGui = RoleSelectionGui.create(ROLE_BUTTON_ID_PREFIX, null, true);
        readOnlyRoleGui = RoleSelectionGui.create(ROLE_BUTTON_ID_PREFIX, null, false);

        bombExplodedSubscription = GameEventBus.subscribe(BombExplodedEvent.class, this::handleBombExploded);
        inventoryClickSubscription = GameEventBus.subscribe(InventoryClickGameItemEvent.class, this::handleInventoryClick);

        registerRoleButtons();
    }

    /** 阶段退出清理：退订、注销角色按钮、停表并清空全部本局状态（不残留跨局任务与菜单跟踪） */
    public void stop(){
        if(bombExplodedSubscription != null){
            bombExplodedSubscription.unsubscribe();
            bombExplodedSubscription = null;
        }
        if(inventoryClickSubscription != null){
            inventoryClickSubscription.unsubscribe();
            inventoryClickSubscription = null;
        }
        unregisterRoleButtons();
        reset();
        switchRoleGui = null;
        readOnlyRoleGui = null;
    }

    /** 停表并清空间歇期状态与菜单跟踪（对局结束/阶段退出；不发玩家可见反馈） */
    private void reset(){
        intermissionActive = false;
        totalTicks = 0;
        remainingTicks = 0;
        lastAnnouncedSecond = -1;
        stopTickTask();
        trackedBattleMenus.clear();
        trackedSubmenus.clear();
    }

    // ==================== 状态查询 ====================

    /** 间歇期是否进行中（炸弹门闩与战斗菜单"切换角色"入口状态都以此为准） */
    public boolean isIntermissionActive(){
        return intermissionActive;
    }

    /** 间歇期剩余 tick（非间歇期返回 0） */
    public int getRemainingTicks(){
        return intermissionActive ? remainingTicks : 0;
    }

    /** 间歇期总时长 tick（非间歇期返回 0） */
    public int getTotalTicks(){
        return intermissionActive ? totalTicks : 0;
    }

    /** 提前结束间歇期（立即开启新据点并刷新按钮状态）；供 /sg 调试指令使用 */
    public void endIntermissionNow(){
        if(!intermissionActive) return;
        finishIntermission();
    }

    // ==================== 进入间歇期 ====================

    /**
     * 炸弹爆炸：当前据点全部炸弹爆炸且还有下一个据点 → 进入间歇期。
     * <p>
     * 本处理器早于 {@code SectorProgressController} 的同类处理器执行（订阅顺序），因此此刻
     * {@code getCurrentSector()} 仍是刚被攻占的据点、{@code isAllBombsExploded()} 为 true；
     * 随后对局闭环才调用 {@code advanceToNextSector()} 激活新据点（有间歇期时不起表）。
     * <p>
     * {@code sector_advance_interval <= 0}（含缺键回退默认）时不做间歇期：{@code SectorManager}
     * 在推进后立即开启新据点（等同旧行为）。最后一个据点不进入间歇期（推进即全据点攻占）。
     */
    private void handleBombExploded(BombExplodedEvent event){
        if(MatchSessionState.getInstance().isMatchEnded()) return;

        SectorManager sectorManager = SectorManager.getInstance();
        if(sectorManager.getSectorAdvanceInterval() <= 0) return;
        if(event.getSector() != sectorManager.getCurrentSector()) return;
        if(!sectorManager.isAllBombsExploded()) return;
        if(!sectorManager.hasNextSector()) return;

        beginIntermission(sectorManager.getSectorAdvanceInterval());
    }

    private void beginIntermission(int ticks){
        stopTickTask();
        intermissionActive = true;
        totalTicks = ticks;
        remainingTicks = ticks;
        lastAnnouncedSecond = -1;

        MessageUtil.broadcastPrefixedMessage(Component.text(
                "据点已被攻占! 新据点将在 " + toSeconds(ticks) + " 秒后开启",
                NamedTextColor.GOLD, TextDecoration.BOLD));

        //间歇期开始：已打开的战斗菜单立即出现"切换角色"入口
        refreshBattleMenus(true);

        ScheduledTask task = GameContext.getInstance().getPlugin().getServer().getGlobalRegionScheduler()
                .runAtFixedRate(GameContext.getInstance().getPlugin(),
                        scheduledTask -> tickIntermission(),
                        1L, 1L);
        tickTask = task;
    }

    /** 每 tick 递减；剩余秒数变化时刷新一次 ActionBar（节流），归零则正式开启新据点 */
    private void tickIntermission(){
        if(!intermissionActive) return;
        //对局已结束（最后一个据点攻占后 endMatch）：静默收尾，不再开启据点
        if(MatchSessionState.getInstance().isMatchEnded()){
            reset();
            return;
        }

        remainingTicks -= 1;
        if(remainingTicks <= 0){
            finishIntermission();
            return;
        }

        int seconds = toSeconds(remainingTicks);
        if(seconds == lastAnnouncedSecond) return;
        lastAnnouncedSecond = seconds;
        Component bar = Component.text("新据点将在 " + seconds + " 秒后开启", NamedTextColor.GOLD);
        for(Player player : Bukkit.getOnlinePlayers()){
            player.sendActionBar(bar);
        }
    }

    /** 间歇期结束：正式开启当前据点（进攻方时限从此刻起算）+ 广播 + 把"切换角色"入口刷成不可用 */
    private void finishIntermission(){
        intermissionActive = false;
        stopTickTask();
        totalTicks = 0;
        remainingTicks = 0;
        lastAnnouncedSecond = -1;

        //新据点正式开启：SectorTimeLimit 从这里开始计时（间歇期不消耗进攻方时限）
        SectorManager.getInstance().openCurrentSector();

        MessageUtil.broadcastPrefixedMessage(Component.text(
                "新据点已开启, 进攻方时限开始计时!", NamedTextColor.GREEN, TextDecoration.BOLD));
        for(Player player : Bukkit.getOnlinePlayers()){
            player.sendActionBar(Component.empty());
        }

        //间歇期结束：战斗菜单入口变屏障且不可点击；仍打开的切角色子菜单刷成只读
        refreshBattleMenus(false);
        refreshSubmenus(readOnlyRoleGui);
    }

    private void stopTickTask(){
        if(tickTask != null){
            tickTask.cancel();
            tickTask = null;
        }
    }

    private static int toSeconds(int ticks){
        return (int) Math.ceil(ticks / 20.0);
    }

    // ==================== 战斗菜单 / 切角色子菜单 ====================

    /** 由门面在打开战斗菜单后调用：记录该菜单，供间歇期开始/结束时刷新"切换角色"入口 */
    public void trackBattleMenu(Player player, ChestGui gui){
        if(player == null || gui == null) return;
        trackedBattleMenus.put(player.getUniqueId(), gui);
    }

    /** 刷新已打开战斗菜单里的"切换角色"入口：enabled = 间歇期是否进行中 */
    private void refreshBattleMenus(boolean enabled){
        for(Player player : Bukkit.getOnlinePlayers()){
            UUID uuid = player.getUniqueId();
            ChestGui gui = trackedBattleMenus.get(uuid);
            if(gui == null) continue;
            if(!isActuallyOpen(player, gui)){
                trackedBattleMenus.remove(uuid);
                continue;
            }
            gui.setSlot(PlayingItemFactory.BATTLE_MENU_SWITCH_ROLE_SLOT,
                    PlayingItemFactory.getInstance().createSwitchRoleMenuItem(enabled));
            gui.refresh();
        }
    }

    /** 用指定渲染器刷新已打开的切角色子菜单（可点击版 = 实时占用名单；只读版 = 间歇期结束后的禁用态） */
    private void refreshSubmenus(RoleSelectionGui renderer){
        if(renderer == null) return;
        for(Player player : Bukkit.getOnlinePlayers()){
            UUID uuid = player.getUniqueId();
            ChestGui gui = trackedSubmenus.get(uuid);
            if(gui == null) continue;
            if(!isActuallyOpen(player, gui)){
                trackedSubmenus.remove(uuid);
                continue;
            }
            ShdfTeam team = TeamManager.getInstance().getTeam(uuid);
            if(team == null || !team.isCombatant()){
                trackedSubmenus.remove(uuid);
                continue;
            }
            renderer.applyContent(gui, team, getRolePool(team));
        }
    }

    /**
     * GUI 是否真的还开着。
     * <p>
     * 不能用 {@code ChestGui.getOpenGui}：打开表没有监听 {@code InventoryCloseEvent}，玩家手动关闭后
     * 仍可能残留，直接写槽位会写进一个已关闭的背包。用背包身份核对最可靠。
     */
    private boolean isActuallyOpen(Player player, ChestGui gui){
        return player.getOpenInventory().getTopInventory() == gui.getInventory();
    }

    // ==================== 角色按钮注册（复用选角阶段的 GUI 组件） ====================

    /** 注册两队全部有效角色按钮：GameItem id 由 {@link RoleSelectionGui} 统一生成，与菜单里显示的 id 同源 */
    private void registerRoleButtons(){
        roleIdByButtonId.clear();
        MapConfig mapConfig = ConfigManager.getInstance().getSelectedMapConfig();
        if(mapConfig == null) return;
        registerRoleButtonsForTeam(mapConfig.getAttackerRoles(), ShdfTeam.ATTACKER);
        registerRoleButtonsForTeam(mapConfig.getDefenderRoles(), ShdfTeam.DEFENDER);
    }

    private void registerRoleButtonsForTeam(List<String> roleIds, ShdfTeam team){
        if(roleIds == null) return;
        RoleBridge roleBridge = RoleBridge.getInstance();
        for(String roleId : roleIds){
            //只注册已实现（有效）的角色，与选角阶段一致
            if(!roleBridge.isValidRoleId(roleId)) continue;
            String gameItemId = switchRoleGui.generateRoleButtonGameItemId(team, roleId);
            if(roleIdByButtonId.containsKey(gameItemId)){
                GameContext.getInstance().getPlugin().getLogger().warning(
                        "[IntermissionController] 角色按钮 id 冲突, 跳过: " + team + "/" + roleId + " -> " + gameItemId);
                continue;
            }
            roleIdByButtonId.put(gameItemId, roleId);
            GameItemRegistry.createAndRegister(gameItemId, builder ->
                    builder.canDrop(false).canMove(false)
                            .inventoryClickHandler(event ->
                                    GameEventBus.publish(new InventoryClickGameItemEvent(
                                            (Player) event.getWhoClicked(), gameItemId))));
        }
    }

    private void unregisterRoleButtons(){
        for(String gameItemId : roleIdByButtonId.keySet()){
            GameItemRegistry.unregister(gameItemId);
        }
        roleIdByButtonId.clear();
    }

    // ==================== 点击路由与切角色 ====================

    /** 库存点击入口：战斗菜单"切换角色"入口 或 子菜单里的角色按钮 */
    private void handleInventoryClick(InventoryClickGameItemEvent event){
        Player player = event.getPlayer();
        String itemId = event.getGameItemId();

        if(PlayingItemFactory.BATTLE_MENU_SWITCH_ROLE_ITEM_ID.equals(itemId)){
            openSwitchRoleMenu(player);
            return;
        }

        String roleId = roleIdByButtonId.get(itemId);
        if(roleId == null) return;
        selectRoleForNextDeploy(player, roleId);
    }

    /** 打开切角色子菜单（复用选角阶段组件）：仅间歇期内可用 */
    private void openSwitchRoleMenu(Player player){
        if(!intermissionActive){
            MessageUtil.sendMessageWithPrefix(player, Component.text("间歇期已结束, 无法切换角色", NamedTextColor.RED));
            return;
        }
        ShdfTeam team = TeamManager.getInstance().getTeam(player.getUniqueId());
        if(team == null || !team.isCombatant()) return;

        ChestGui gui = switchRoleGui.openMenu(player, team, getRolePool(team), getSideDisplayName(team));
        trackedSubmenus.put(player.getUniqueId(), gui);
    }

    /**
     * 切换"下次部署使用的角色"：写 {@code PlayerStatus.selectedRoleId}，在该玩家下次部署时生效
     * （{@code SpawnManager.deployPlayer → RoleBridge.setPlayerRole}）。
     */
    private void selectRoleForNextDeploy(Player player, String roleId){
        if(!intermissionActive){
            MessageUtil.sendMessageWithPrefix(player, Component.text("间歇期已结束, 无法切换角色", NamedTextColor.RED));
            return;
        }
        UUID uuid = player.getUniqueId();
        TeamManager teamManager = TeamManager.getInstance();
        ShdfTeam team = teamManager.getTeam(uuid);
        if(team == null || !team.isCombatant()){
            MessageUtil.sendMessageWithPrefix(player, Component.text("只有参战人员才能切换角色", NamedTextColor.GRAY));
            return;
        }
        PlayerStatus status = teamManager.getPlayerStatus(uuid);
        if(status == null) return;

        if(isRoleTakenByOthers(team, roleId, uuid)){
            MessageUtil.sendMessageWithPrefix(player, Component.text("该角色已被同队其他玩家选择或占用", NamedTextColor.RED));
            SoundUtil.playNoticeFailCombinedSound(player);
            refreshSubmenus(switchRoleGui);
            return;
        }

        status.setSelectedRoleId(roleId);
        player.closeInventory();
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        MessageUtil.sendMessageWithPrefix(player,
                Component.text("已选择角色: " + roleId + " (将在你下次部署时生效)", NamedTextColor.GREEN));

        refreshSubmenus(switchRoleGui);
    }

    /**
     * 该角色是否已被同队其他玩家占用（仅在禁止重复角色时判定）。
     * <p>
     * 两个口径<b>取并集</b>，缺一不可：
     * <ol>
     *     <li>与选角阶段同口径——本队其他玩家的 {@code PlayerStatus.selectedRoleId}：未部署玩家只有这份记录；</li>
     *     <li>{@link RoleBridge} 占用表——它<b>只在部署时填充</b>：已部署玩家的旧角色在其重新部署前仍然实际生效，
     *     即使他的 selectedRoleId 已改成别的角色；只查 selectedRoleId 会漏掉这种占用，产生同队重复角色。</li>
     * </ol>
     */
    private boolean isRoleTakenByOthers(ShdfTeam team, String roleId, UUID selfUuid){
        RoleBridge roleBridge = RoleBridge.getInstance();
        if(roleBridge.isAllowDuplicateRoles()) return false;

        for(PlayerStatus status : TeamManager.getInstance().getAllPlayerStatusesInTeam(team)){
            if(roleId.equals(status.getSelectedRoleId()) && !status.getUuid().equals(selfUuid)){
                return true;
            }
        }
        if(roleBridge.getOccupiedRoles(team).contains(roleId)){
            //自己当前已生效的角色不算"被别人占用"（与 RoleBridge.setPlayerRole 的判定保持一致）
            String ownAppliedRole = roleBridge.getPlayerRole(selfUuid);
            if(ownAppliedRole == null || !ownAppliedRole.equals(roleId)){
                return true;
            }
        }
        return false;
    }

    private List<String> getRolePool(ShdfTeam team){
        MapConfig mapConfig = ConfigManager.getInstance().getSelectedMapConfig();
        if(mapConfig == null) return List.of();
        if(team == ShdfTeam.ATTACKER) return mapConfig.getAttackerRoles();
        return mapConfig.getDefenderRoles();
    }

    private String getSideDisplayName(ShdfTeam team){
        if(team == ShdfTeam.ATTACKER) return "进攻方";
        return "防守方";
    }
}
