package com.sHDFGamePlugin.phase;

import com.sHDFGamePlugin.SHDFGamePlugin;
import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.core.GameState;
import com.sHDFGamePlugin.core.GameStateMachine;
import com.sHDFGamePlugin.domain.sector.SectorManager;
import com.sHDFGamePlugin.domain.spawn.SpawnManager;
import com.sHDFGamePlugin.domain.team.PlayerState;
import com.sHDFGamePlugin.domain.team.PlayerStatus;
import com.sHDFGamePlugin.domain.team.ShdfTeam;
import com.sHDFGamePlugin.domain.team.TeamManager;
import com.sHDFGamePlugin.domain.ticket.TicketManager;
import com.sHDFGamePlugin.infrastructure.DisconnectProtection;
import com.sHDFGamePlugin.infrastructure.GameEventBus;
import com.sHDFGamePlugin.infrastructure.HashBiMap;
import com.sHDFGamePlugin.infrastructure.RoleBridge;
import com.sHDFGamePlugin.infrastructure.config.ConfigManager;
import com.sHDFGamePlugin.infrastructure.event.InventoryClickGameItemEvent;
import com.sHDFGamePlugin.infrastructure.event.RightClickGameItemEvent;
import com.sHDFGamePlugin.infrastructure.event.ShdfPlayerJoinEvent;
import com.sHDFGamePlugin.infrastructure.event.ShdfPlayerQuitEvent;
import com.sHDFGamePlugin.infrastructure.gui.ChestGui;
import com.sHDFGamePlugin.infrastructure.gui.RoleSelectionGui;
import com.sHDFGamePlugin.infrastructure.item.GameItem;
import com.sHDFGamePlugin.infrastructure.item.GameItemRegistry;
import com.sHDFGamePlugin.util.GameCountdown;
import com.sHDFGamePlugin.util.MessageUtil;
import com.sHDFGamePlugin.util.SoundUtil;
import com.shadowHunterRolesPlugin.registry.RoleRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 角色选择阶段：参战玩家从本队角色池中选择整场使用的角色。
 * <p>
 * 职责：
 * - 进入时自动分配 UNKNOWN 玩家、按人数判定是否启用重复角色、发放选择物品；
 * - 右键物品打开角色选择 GUI，选择后仅记录 selectedRoleId（角色在战斗阶段统一应用）；
 * - 倒计时结束（或无倒计时时全员选完）为未选玩家自动分配随机角色，入场部署移交 PlayingPhase。
 */
public class RoleSelectingPhase implements GamePhase {

    private RoleSelectingPhase(){}

    private static final RoleSelectingPhase INSTANCE = new RoleSelectingPhase();
    /** 获取单例实例 */
    public static RoleSelectingPhase getInstance(){
        return INSTANCE;
    }

    //GameItem id（阶段前缀风格）
    private static final String PREFIX = "gameItem_roleSelectingPhase_";
    private static final String attackerRoleSelectorId = PREFIX + "roleSelector_attacker";
    private static final String defenderRoleSelectorId = PREFIX + "roleSelector_defender";
    private static final String clearRoleButtonId = PREFIX + "clearRoleButton";

    //角色选择倒计时默认时长（tick）：duration 未配置或为 0 时使用
    private static final int DEFAULT_ROLE_SELECTION_DURATION = 600;

    //双方可用角色池
    private List<String> availableAttackerRoleIds = new ArrayList<>();
    private List<String> availableDefenderRoleIds = new ArrayList<>();

    //已注册的角色按钮（双向表：角色名 <-> 按钮 GameItem id，按阵营分表，点击校验以此为准）
    private final HashBiMap<String, String> registeredAttackerRoleButtonIds = new HashBiMap<>();
    private final HashBiMap<String, String> registeredDefenderRoleButtonIds = new HashBiMap<>();

    //角色选择 GUI 组件（每次进入阶段新建一个，随阶段生命周期废弃；渲染逻辑见 infrastructure/gui/RoleSelectionGui）
    private RoleSelectionGui roleSelectionGui;

    //侧边栏计分板
    private Objective sidebarObjective;

    //倒计时
    private GameCountdown countdown;

    //事件订阅
    private GameEventBus.Subscription joinSubscription;
    private GameEventBus.Subscription quitSubscription;
    private GameEventBus.Subscription inventoryClickSubscription;
    private GameEventBus.Subscription rightClickGameItemSubscription;

    /**
     * 阶段进入：分配随机阵营 → 载入角色池 → 同步地图配置给 RoleBridge →
     * 重置本局重复规则/占用 → 重复角色判定 → 参战玩家发放选择物品 →
     * 注册物品与按钮 → 启动倒计时 → 订阅事件 → 传送双方入场。
     */
    @Override
    public void onEnter() {
        TeamManager teamManager = TeamManager.getInstance();

        //1. 随机阵营玩家分到双方（此后参战人数定型）
        teamManager.autoAssignUnknownTeamPlayers();

        //2. 载入角色池
        loadAvailableRoleIds();

        //3. 把当前地图配置同步给角色桥接（角色池校验/占用记录以它为准）
        RoleBridge.getInstance().setCurrentMapConfig(ConfigManager.getInstance().getSelectedMapConfig());

        //4. 重置本局角色状态：重复规则按配置还原、清空上一局占用记录
        RoleBridge.getInstance().setAllowDuplicateRoles(ConfigManager.getInstance().isAllowDuplicateRoles());
        RoleBridge.getInstance().clearAllOccupiedRoles();

        //5. 重复角色判定：任一队人数 > 角色数量 → 本局允许重复
        checkDuplicateRolesRule();

        //6. 参战玩家进入选角状态 + 发放选择物品
        for(Player player : Bukkit.getOnlinePlayers()){
            UUID uuid = player.getUniqueId();
            ShdfTeam team = teamManager.getTeam(uuid);
            if(team == null || !team.isCombatant()) continue;
            PlayerStatus status = teamManager.getPlayerStatus(uuid);
            if(status != null){
                status.setState(PlayerState.ROLE_SELECTING);
            }
            player.getInventory().clear();
            giveRoleSelectorItem(player, team);
        }

        //7. 注册选择物品与角色按钮（GUI 渲染组件按本局重新创建，避免跨局残留回调/内容）
        createRoleSelectionGui();
        registerRoleSelectorItems();
        registerRoleButtons();

        //8. 启动倒计时
        startCountdown();

        //9. 订阅事件
        subscribeEvents();

        //10.传送玩家至相应大厅
        teleportAllPlayersToSpawnpoint();

        //11.更新侧边栏
        updateSidebarObjective();
    }

    /** 阶段退出：注销订阅、取消倒计时、关闭所有打开的 GUI 并清理快捷栏选择物品 */
    @Override
    public void onExit() {
        unsubscribeEvents();
        cancelCountdown();
        ChestGui.closeAllGuis();
        //清理快捷栏选择物品
        for(Player player : Bukkit.getOnlinePlayers()){
            player.getInventory().setItem(0, null);
        }
        //注销侧边栏计分板目标（对齐 WaitingPhase），防止进入 PLAYING 后残留 role_selecting_phase_sidebar
        if(sidebarObjective != null){
            sidebarObjective.unregister();
            sidebarObjective = null;
        }
    }

    /** 创建一个新的侧边栏计分板 */
    private void createSidebarObjective(){
        sidebarObjective = SHDFGamePlugin.getInstance().getTempScoreboard().registerNewObjective("role_selecting_phase_sidebar", Criteria.DUMMY,
                Component.text("DECAYING FRONTLINE", NamedTextColor.GOLD, TextDecoration.BOLD));
        sidebarObjective.setDisplaySlot(DisplaySlot.SIDEBAR);
    }

    /** 更新计分板 */
    @SuppressWarnings("deprecated")
    private void updateSidebarObjective(){
        ConfigManager config = ConfigManager.getInstance();
        //重建侧边栏：先注销旧的（兼容跨阶段残留），再新建
        if(sidebarObjective != null){
            sidebarObjective.unregister();
        }
        createSidebarObjective();

        sidebarObjective.getScore(ChatColor.BOLD + "角色选择阶段").setScore(-1);
        sidebarObjective.getScore(" ").setScore(-2);

        if(RoleBridge.getInstance().isAllowDuplicateRoles()){
            sidebarObjective.getScore("本场对局允许使用重复角色").setScore(-3);
        }
        else{
            sidebarObjective.getScore("本场对局不允许使用重复角色").setScore(-3);
        }

        sidebarObjective.getScore("  ").setScore(-4);

        //倒计时行：倒计时运行时在 -8 显示"游戏将在 mm:ss 后开始"
        if(countdown != null && countdown.isRunning()){
            int seconds = (int) Math.ceil(countdown.getRemainingTicks() / 20.0);
            String mmss = String.format("%02d:%02d", seconds / 60, seconds % 60);
            sidebarObjective.getScore(ChatColor.GOLD + "对局阶段 " + mmss + " 后开始").setScore(-5);
        }
    }

    // ==================== 进入阶段 ====================



    /** 从当前选中地图载入双方可用角色池；地图缺失时告警并保持空池 */
    private void loadAvailableRoleIds(){
        ConfigManager config = ConfigManager.getInstance();
        if(config.getSelectedMapConfig() == null){
            GameContext.getInstance().getPlugin().getLogger().warning("[RoleSelectingPhase] 未选择地图, 角色池为空!");
            return;
        }
        availableAttackerRoleIds = config.getSelectedMapConfig().getAttackerRoles();
        availableDefenderRoleIds = config.getSelectedMapConfig().getDefenderRoles();
    }

    /** 把双方在线玩家分别传送到本阶段（role_selection 世界）的阵营出生点 */
    private void teleportAllPlayersToSpawnpoint(){
        TeamManager teamManager = TeamManager.getInstance();
        ConfigManager config = ConfigManager.getInstance();
        World world = Bukkit.getWorld(config.getRoleSelectionWorld());
        if(world == null) return;

        Location attackerSp = config.getRoleSelectionAttackerSpawnpoint().toLocation(world);
        for(UUID pid : teamManager.getAllPlayersUuidsInTeam(ShdfTeam.ATTACKER)){
            Player player = Bukkit.getPlayer(pid);
            if(player == null || !player.isOnline()) continue;
            player.teleport(attackerSp);
        }

        Location defenderSp = config.getRoleSelectionDefenderSpawnpoint().toLocation(world);
        for(UUID pid : teamManager.getAllPlayersUuidsInTeam(ShdfTeam.DEFENDER)){
            Player player = Bukkit.getPlayer(pid);
            if(player == null || !player.isOnline()) continue;
            player.teleport(defenderSp);
        }
    }

    /** 任一队伍人数大于其角色数量 → 本局启用允许重复角色 */
    private void checkDuplicateRolesRule(){
        TeamManager teamManager = TeamManager.getInstance();
        int attackers = teamManager.getPlayerPopulationOnTeam(ShdfTeam.ATTACKER);
        int defenders = teamManager.getPlayerPopulationOnTeam(ShdfTeam.DEFENDER);
        if(attackers > availableAttackerRoleIds.size() || defenders > availableDefenderRoleIds.size()){
            RoleBridge.getInstance().setAllowDuplicateRoles(true);
            GameContext.getInstance().getPlugin().getLogger().warning(
                    "[RoleSelectingPhase] 任一队伍人数超过角色数量 (进攻 " + attackers + "/" + availableAttackerRoleIds.size()
                            + ", 防守 " + defenders + "/" + availableDefenderRoleIds.size() + "), 本局已启用允许重复角色!");
            MessageUtil.sendPrefixedMessageToAllPlayers(Component.text("由于有队伍人数超过已配置的角色数量, 本场对局将允许重复角色!", NamedTextColor.YELLOW, TextDecoration.BOLD));
        }
    }

    /** 给玩家发放快捷栏第 1 格的角色选择物品（按阵营区分物品 id，右键可打开选角菜单） */
    private void giveRoleSelectorItem(Player player, ShdfTeam team){
        String itemId;
        if(team == ShdfTeam.ATTACKER){
            itemId = attackerRoleSelectorId;
        }
        else{
            itemId = defenderRoleSelectorId;
        }
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("选择角色", NamedTextColor.GOLD, TextDecoration.BOLD));
        meta.lore(List.of(Component.text("右键打开角色选择菜单", NamedTextColor.GRAY)));
        meta = GameItem.applyIdOnItemMeta(itemId, meta);
        item.setItemMeta(meta);
        player.getInventory().setItem(0, item);
    }

    /** 注册两个"右键即发布 RightClickGameItemEvent"的角色选择物品 */
    private void registerRoleSelectorItems(){
        GameItemRegistry.createAndRegister(attackerRoleSelectorId, builder -> {
            builder.canMove(false).canDrop(false)
                    .rightClickHandler(event -> {
                        GameEventBus.publish(new RightClickGameItemEvent(event.getPlayer(), attackerRoleSelectorId));
                    });
        });
        GameItemRegistry.createAndRegister(defenderRoleSelectorId, builder -> {
            builder.canMove(false).canDrop(false)
                    .rightClickHandler(event -> {
                        GameEventBus.publish(new RightClickGameItemEvent(event.getPlayer(), defenderRoleSelectorId));
                    });
        });
    }

    /**
     * 创建本局的角色选择 GUI 渲染组件。
     * <p>
     * 按钮 GameItem id 前缀沿用阶段命名约定（{@code gameItem_roleSelectingPhase_role_}），
     * 保证与抽取前注册/反查的 id 完全一致；组件只负责渲染，不持有业务状态。
     */
    private void createRoleSelectionGui(){
        roleSelectionGui = RoleSelectionGui.create(PREFIX + "role_", clearRoleButtonId, true);
    }

    /** 重新注册本局全部角色按钮（含"清除角色"按钮）：先清空两张双向注册表，再按队注册 */
    private void registerRoleButtons(){        //每次进入阶段重新注册，两张表以本次为准
        registeredAttackerRoleButtonIds.clear();
        registeredDefenderRoleButtonIds.clear();
        registerRoleButtonsForTeam(availableAttackerRoleIds, ShdfTeam.ATTACKER);
        registerRoleButtonsForTeam(availableDefenderRoleIds, ShdfTeam.DEFENDER);
        registerClearRoleButton();
    }

    /** 注册"清除角色"按钮（库存点击发布事件） */
    private void registerClearRoleButton(){
        GameItemRegistry.createAndRegister(clearRoleButtonId, builder ->
                builder.canDrop(false).canMove(false)
                        .inventoryClickHandler(event ->
                                GameEventBus.publish(new InventoryClickGameItemEvent((Player) event.getWhoClicked(), clearRoleButtonId))));
    }

    /**
     * 为单个阵营注册角色按钮：按钮 GameItem id 由 {@link RoleSelectionGui} 统一生成
     * （保证"菜单里显示用的 id"与"点击回调的 id"同源），有效角色写入对应双向表并注册 GameItem；
     * 键/值冲突则跳过，与抽取前的注册语义一致。
     */
    private void registerRoleButtonsForTeam(List<String> roleIds, ShdfTeam team){
        RoleBridge roleBridge = RoleBridge.getInstance();
        //按阵营选择对应的注册表
        HashBiMap<String, String> registeredMap;
        if(team == ShdfTeam.ATTACKER){
            registeredMap = registeredAttackerRoleButtonIds;
        }
        else{
            registeredMap = registeredDefenderRoleButtonIds;
        }
        for(String roleId : roleIds){
            //只注册已实现（有效）的角色
            if(!roleBridge.isValidRoleId(roleId)) continue;
            String gameItemId = roleSelectionGui.generateRoleButtonGameItemId(team, roleId);
            //记录: 角色名 -> 按钮 id；put 失败说明键/值冲突，跳过本次注册
            if(!registeredMap.put(roleId, gameItemId)){
                GameContext.getInstance().getPlugin().getLogger().warning("[RoleSelectingPhase] 注册角色按钮冲突, 跳过: " + roleId + " -> " + gameItemId);
                continue;
            }
            GameItemRegistry.createAndRegister(gameItemId, builder ->
                    builder.canDrop(false).canMove(false)
                            .inventoryClickHandler(event ->
                                    GameEventBus.publish(new InventoryClickGameItemEvent((Player) event.getWhoClicked(), gameItemId))));
        }
    }

    // ==================== 倒计时 ====================

    /** 启动阶段倒计时：duration<=0 回退默认 600；内部 +1 补偿首 tick；30/20/10 秒里程碑播报，归零后执行收尾 */
    private void startCountdown(){
        //duration <= 0（未配置/写 0）时回退默认 600 tick；只跑倒计时，不做"手动选完"判定
        int duration = ConfigManager.getInstance().getRoleSelectionDuration();
        if(duration <= 0){
            duration = DEFAULT_ROLE_SELECTION_DURATION;
        }
        //自动 +1 补偿首 tick，配置里无需自行加 1
        countdown = new GameCountdown(GameContext.getInstance().getPlugin(), duration + 1);
        countdown.setOnTick(tick -> {
            //标题倒计时特效：仅在最后 10 秒（200 tick）内显示（与准备阶段一致）
            if(tick <= 200 && tick % 2 == 0){
                for(Player player : Bukkit.getOnlinePlayers()){
                    Title title = Title.title(
                            Component.text(">>> " + String.format("%.1f", tick / 20f) + " <<<", NamedTextColor.GREEN, TextDecoration.BOLD),
                            Component.text("对局即将开启", NamedTextColor.GREEN, TextDecoration.BOLD),
                            Title.Times.times(
                                    Duration.ZERO,
                                    Duration.ofMillis(1000),
                                    Duration.ZERO
                            )
                    );
                    player.showTitle(title);
                    if(tick % 20 == 0){
                        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                    }
                }
            }
            //里程碑播报：剩余 30秒 / 20秒 / 10秒 时向全员广播（与准备阶段一致）
            if(tick == 600 || tick == 400 || tick == 200){
                MessageUtil.sendPrefixedMessageToAllPlayers(
                        Component.text("对局在 " + (tick / 20) + " 秒后开启", NamedTextColor.GOLD));
            }
            updateSidebarObjective();
        });
        countdown.setOnFinish(() -> finishRoleSelection());
        countdown.start();
    }

    /** 若倒计时正在运行则取消（触发 onCancel），随后置空引用 */
    private void cancelCountdown(){
        if(countdown != null && countdown.isRunning()){
            countdown.cancel("角色选择阶段结束");
        }
        countdown = null;
    }

    // ==================== 结束阶段（倒计时结束） ====================

    /** 阶段收尾：若已无在线玩家则重置游戏状态并回 IDLE；否则为未选玩家自动分配角色 → 切换至 PLAYING（入场部署由 PlayingPhase.onEnter 统一负责） */
    private void finishRoleSelection(){
        //兜底：阶段结束时服务器已无玩家 → 重置游戏状态并回到 IDLE
        if(Bukkit.getOnlinePlayers().isEmpty()){
            GameContext.getInstance().getPlugin().getLogger().info("[RoleSelectingPhase] 角色选择阶段结束时服务器已无玩家, 重置游戏状态并回到 IDLE");
            resetGameState();
            GameStateMachine.getInstance().transitionTo(GameState.IDLE);
            return;
        }

        //为未选角色的参战玩家自动分配
        autoAssignRoles();
        //进入对局（入场部署：传送当前据点出生区 + 应用角色，由 PlayingPhase.onEnter 统一负责）
        GameStateMachine.getInstance().transitionTo(GameState.PLAYING);
    }

    /** 无玩家场景下的游戏状态重置（与 FinishedPhase 清理保持一致） */
    private void resetGameState(){
        //据点/炸弹：停止引信与据点时限任务
        SectorManager.getInstance().cleanup();
        //队伍/玩家状态：清空全部 PlayerStatus
        TeamManager.getInstance().reset();
        //重生队列
        SpawnManager.getInstance().clearAll();
        //票数
        TicketManager.getInstance().reset();
        //角色占用与重复规则还原
        RoleBridge.getInstance().clearAllOccupiedRoles();
        RoleBridge.getInstance().setAllowDuplicateRoles(ConfigManager.getInstance().isAllowDuplicateRoles());
        //取消挂起的断线保护任务
        DisconnectProtection.getInstance().cancelAll();
        //关闭所有打开的游戏 GUI
        ChestGui.closeAllGuis();
    }

    /** 按重复角色规则为未选玩家随机分配：允许重复→全池随机；禁止重复→未占用角色随机 */
    private void autoAssignRoles(){
        autoAssignRolesForTeam(ShdfTeam.ATTACKER, availableAttackerRoleIds);
        autoAssignRolesForTeam(ShdfTeam.DEFENDER, availableDefenderRoleIds);
    }

    /** 为单个阵营中未选角色的在线玩家随机分配（仅记录到 selectedRoleId，不应用角色）：允许重复→全池；禁止重复→未被本队选择的角色 */
    private void autoAssignRolesForTeam(ShdfTeam team, List<String> rolePool){
        Random random = new Random();
        for(UUID uuid : TeamManager.getInstance().getAllPlayersUuidsInTeam(team)){
            if(Bukkit.getPlayer(uuid) == null) continue; //离线玩家不分配
            PlayerStatus status = TeamManager.getInstance().getPlayerStatus(uuid);
            if(status == null || status.getSelectedRoleId() != null) continue;

            List<String> candidates = new ArrayList<>(rolePool);
            if(!RoleBridge.getInstance().isAllowDuplicateRoles()){
                //去掉本队已被选择的角色（基于 selectedRoleId，随循环分配实时更新）
                candidates.removeAll(getSelectedRoleIds(team));
            }
            if(candidates.isEmpty()){
                //防御性兜底：无空闲角色时强制全池随机并记录警告
                candidates = new ArrayList<>(rolePool);
                GameContext.getInstance().getPlugin().getLogger().warning("[RoleSelectingPhase] 自动分配时无空闲角色, 强制随机分配!");
            }
            status.setSelectedRoleId(candidates.get(random.nextInt(candidates.size())));
        }
    }

    /** 本队当前已被选择的所有角色 id（基于 selectedRoleId） */
    private Set<String> getSelectedRoleIds(ShdfTeam team){
        Set<String> roleIds = new HashSet<>();
        for(PlayerStatus status : TeamManager.getInstance().getAllPlayerStatusesInTeam(team)){
            if(status.getSelectedRoleId() != null){
                roleIds.add(status.getSelectedRoleId());
            }
        }
        return roleIds;
    }

    // ==================== 交互 ====================

    /** 右键事件入口：只处理两个角色选择物品，匹配则打开对应玩家的选角 GUI */
    private void handleRightClickGameItem(RightClickGameItemEvent event){
        String itemId = event.getGameItemId();
        if(itemId.equals(attackerRoleSelectorId) || itemId.equals(defenderRoleSelectorId)){
            openRoleSelectionGui(event.getPlayer());
        }
    }

    /** 库存点击入口：清除按钮单独处理；其余通过双向注册表反查按钮 id 对应的角色名，未注册则忽略，命中则执行选角 */
    private void handleInventoryClick(InventoryClickGameItemEvent event){
        String itemId = event.getGameItemId();
        //清除角色按钮单独处理
        if(itemId.equals(clearRoleButtonId)){
            clearOwnRole(event.getPlayer());
            return;
        }
        //通过双向表反查按钮 id 对应的角色名；两张表都查不到说明不是本阶段按钮
        String roleId = registeredAttackerRoleButtonIds.getByValue(itemId);
        if(roleId == null){
            roleId = registeredDefenderRoleButtonIds.getByValue(itemId);
        }
        if(roleId == null) return;
        selectRole(event.getPlayer(), roleId);
    }

    /**
     * 打开玩家的角色选择箱子 GUI：前面放本队有效角色按钮，最下面一行中间放"清除角色"按钮（箱子菜单上限 6 行）。
     * 布局与按钮渲染由 {@link RoleSelectionGui} 统一负责（与刷新逻辑同源）。
     */
    private void openRoleSelectionGui(Player player){
        TeamManager teamManager = TeamManager.getInstance();
        ShdfTeam team = teamManager.getTeam(player.getUniqueId());
        if(team == null || !team.isCombatant()) return;

        roleSelectionGui.openMenu(player, team, getRolePool(team), getSideDisplayName(team));
    }

    /** 本队角色池（进攻方 / 防守方） */
    private List<String> getRolePool(ShdfTeam team){
        if(team == ShdfTeam.ATTACKER){
            return availableAttackerRoleIds;
        }
        return availableDefenderRoleIds;
    }

    /** 阵营显示名（菜单标题用） */
    private String getSideDisplayName(ShdfTeam team){
        if(team == ShdfTeam.ATTACKER){
            return "进攻方";
        }
        return "防守方";
    }

    /** 查询本队中已选择指定角色的在线玩家名单（以 PlayerStatus.selectedRoleId 为准） */
    private List<String> getRoleSelectors(ShdfTeam team, String roleId){
        List<String> names = new ArrayList<>();
        for(PlayerStatus status : TeamManager.getInstance().getAllPlayerStatusesInTeam(team)){
            if(roleId.equals(status.getSelectedRoleId())){
                Player player = Bukkit.getPlayer(status.getUuid());
                if(player != null){
                    names.add(player.getName());
                }
            }
        }
        return names;
    }

    /** 清除玩家自己已选的角色：仅清空 selectedRoleId 记录（选角阶段不应用角色），播放失败音效并刷新菜单 */
    private void clearOwnRole(Player player){
        UUID uuid = player.getUniqueId();
        TeamManager teamManager = TeamManager.getInstance();
        ShdfTeam team = teamManager.getTeam(uuid);
        if(team == null || !team.isCombatant()) return;

        PlayerStatus status = teamManager.getPlayerStatus(uuid);
        if(status == null || status.getSelectedRoleId() == null){
            MessageUtil.sendMessageWithPrefix(player, Component.text("你当前没有选择角色", NamedTextColor.GRAY));
            return;
        }

        //选角阶段只记录选择，直接清空记录即可
        status.setSelectedRoleId(null);

        //清除角色：播放失败提示组合音效
        SoundUtil.playNoticeFailCombinedSound(player);
        MessageUtil.sendMessageWithPrefix(player, Component.text("已清除角色选择", NamedTextColor.GREEN));

        //保持菜单打开，刷新各按钮的占用名单与光效
        refreshOpenRoleGuis();
    }

    /** 玩家选角核心逻辑：校验参战身份 → 禁重复时检查是否已被本队其他玩家选择 → 仅记录 selectedRoleId（实际设置角色在对局阶段）→ 关闭 GUI → 刷新菜单 */
    private void selectRole(Player player, String roleId){
        UUID uuid = player.getUniqueId();
        TeamManager teamManager = TeamManager.getInstance();
        ShdfTeam team = teamManager.getTeam(uuid);
        if(team == null || !team.isCombatant()){
            MessageUtil.sendMessageWithPrefix(player, Component.text("只有参战人员才能选择角色", NamedTextColor.GRAY));
            return;
        }

        //禁用重复时：该角色已被本队其他玩家选择 → 拒绝
        if(!RoleBridge.getInstance().isAllowDuplicateRoles() && isRoleSelectedByOthers(team, roleId, uuid)){
            MessageUtil.sendMessageWithPrefix(player, Component.text("该角色已被其他玩家选择", NamedTextColor.RED));
            //保持选角菜单打开，重新渲染各按钮的占用名单与光效
            refreshOpenRoleGuis();
            return;
        }

        PlayerStatus status = teamManager.getPlayerStatus(uuid);
        if(status != null){
            //仅记录所选角色，实际设置角色在对局阶段部署时进行
            status.setSelectedRoleId(roleId);
        }
        player.closeInventory();
        //选择成功：播放经验球"叮"声
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        MessageUtil.sendMessageWithPrefix(player, Component.text("你选择了角色: " + roleId, NamedTextColor.GREEN));

        //刷新其他玩家已打开的角色菜单（占用标记更新）
        refreshOpenRoleGuis();
    }

    /** 本队中是否有其他玩家已选择该角色（排除自己） */
    private boolean isRoleSelectedByOthers(ShdfTeam team, String roleId, UUID selfUuid){
        for(PlayerStatus status : TeamManager.getInstance().getAllPlayerStatusesInTeam(team)){
            if(roleId.equals(status.getSelectedRoleId()) && !status.getUuid().equals(selfUuid)){
                return true;
            }
        }
        return false;
    }

    /** 重建所有已打开的角色菜单内容（选中/拒绝后调用，占用名单与光效实时更新；不调用 refresh 补空槽） */
    private void refreshOpenRoleGuis(){
        for(Player player : Bukkit.getOnlinePlayers()){
            ChestGui gui = ChestGui.getOpenGui(player);
            if(gui == null) continue;
            ShdfTeam team = TeamManager.getInstance().getTeam(player.getUniqueId());
            if(team == null || !team.isCombatant()) continue;

            //只重建有效角色按钮（与打开 GUI 时一致），槽位数量不变，不会出现空槽
            roleSelectionGui.applyContent(gui, team, getRolePool(team));
        }
    }

    // ==================== 玩家加入/退出 ====================

    /** 加入事件入口：先取消挂起的断线保护；保留的 ROLE_SELECTING 战斗身份则恢复参战，否则转为观战者 */
    private void handlePlayerJoin(ShdfPlayerJoinEvent event){
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        TeamManager teamManager = TeamManager.getInstance();

        //玩家已上线，取消其挂起的断线保护任务
        DisconnectProtection.getInstance().cancel(uuid);

        PlayerStatus status = teamManager.getPlayerStatus(uuid);
        //断线重连：保留的 PlayerStatus 且状态为本阶段 → 恢复战斗身份
        if(status != null && status.getState() == PlayerState.ROLE_SELECTING && status.getTeam().isCombatant()){
            restoreCombatant(player, status);
            return;
        }

        //新来者 / 状态过期 → 转为观战者并传送观战出生点
        makeSpectator(player);
    }

    /** 恢复参战身份：重新发放选择物品并传送回本方区域 */
    private void restoreCombatant(Player player, PlayerStatus status){
        player.getInventory().clear();
        giveRoleSelectorItem(player, status.getTeam());
        player.setGameMode(GameMode.ADVENTURE);
        //恢复插件持有的计分板实例：断线重连后必须重新挂上，否则看不到选角侧边栏
        player.setScoreboard(SHDFGamePlugin.getInstance().getTempScoreboard());

        ConfigManager config = ConfigManager.getInstance();
        World world = Bukkit.getWorld(config.getRoleSelectionWorld());
        if(world != null){
            Vector spawn;
            if(status.getTeam() == ShdfTeam.ATTACKER){
                spawn = config.getRoleSelectionAttackerSpawnpoint();
            }
            else{
                spawn = config.getRoleSelectionDefenderSpawnpoint();
            }
            if(spawn != null){
                player.teleport(spawn.toLocation(world));
            }
        }
        MessageUtil.sendMessageWithPrefix(player, Component.text("欢迎回来, 你仍是参战玩家", NamedTextColor.GREEN));
    }

    /** 转为观战者：传送至地图观战出生点 */
    private void makeSpectator(Player player){
        ConfigManager configManager = ConfigManager.getInstance();
        if(configManager.getSelectedMapConfig() == null){
            GameContext.getInstance().getPlugin().getLogger().warning("Selected map is null when a player joined in RoleSelectingPhase!");
            return;
        }
        World world = Bukkit.getWorld(configManager.getSelectedMapConfig().getWorld());
        if(world == null){
            GameContext.getInstance().getPlugin().getLogger().warning("World could not be found when a player joined in RoleSelectingPhase! Player Kicked!");
            player.kick(Component.text("SHDF插件出现意外错误", NamedTextColor.RED, TextDecoration.BOLD));
            return;
        }
        Location spawnLocation = configManager.getSpectatorSpawnpoint().toLocation(world);
        player.teleport(spawnLocation);

        UUID uuid = player.getUniqueId();
        TeamManager teamManager = TeamManager.getInstance();
        //释放旧角色占用后按观战者重新注册
        RoleBridge.getInstance().clearPlayerRole(uuid);
        teamManager.removePlayer(uuid);
        teamManager.addPlayer(uuid, ShdfTeam.SPECTATOR, PlayerState.ROLE_SELECTING);

        //观战者也要用插件持有的计分板实例，否则侧边栏对它不可见
        player.setScoreboard(SHDFGamePlugin.getInstance().getTempScoreboard());
        MessageUtil.sendMessageWithPrefix(player, Component.text("你在对局中加入, 已经被自动设置为旁观者, 请等待对局结束"));
        player.getInventory().clear();
        player.setGameMode(GameMode.SPECTATOR);
    }

    /** 退出事件入口：空服则回 IDLE；否则立即清除其已选角色（重连需重新选择），保留 PlayerStatus（断线保护）并挂超时清理 */
    private void handlePlayerQuit(ShdfPlayerQuitEvent event){
        Player quittingPlayer = event.getPlayer();
        UUID uuid = quittingPlayer.getUniqueId();

        //空服判定：除退出者外没有其他玩家在线（与 PlayingPhase 口径一致）
        if(isNoOtherPlayerOnline(quittingPlayer)){
            GameStateMachine.getInstance().transitionTo(GameState.IDLE);
            return;
        }

        //角色选择阶段退出：立即清除已选角色（释放角色槽位，重连后需重新选择）
        PlayerStatus status = TeamManager.getInstance().getPlayerStatus(uuid);
        if(status != null){
            status.setSelectedRoleId(null);
        }
        //刷新其余玩家已打开的角色菜单（占用名单与光效更新）
        refreshOpenRoleGuis();

        //断线保护：保留 PlayerStatus 与阵营身份，超过重连时限仍未上线才移除记录
        DisconnectProtection.getInstance().start(
                GameContext.getInstance().getPlugin(),
                uuid,
                ConfigManager.getInstance().getRoleSelectionReconnectTimeLimit(),
                expiredUuid -> {
                    TeamManager.getInstance().removePlayer(expiredUuid);
                    RoleBridge.getInstance().clearPlayerRole(expiredUuid);
                }
        );
    }

    /**
     * 除退出者本人之外是否还有其他玩家在线（空服判定口径，与 WaitingPhase 保持同一写法）。
     * <p>
     * PlayerQuitEvent 在玩家被移出在线列表之前触发，因此退出者仍在 {@code Bukkit.getOnlinePlayers()} 中；
     * 原 {@code isEmpty()} 判定在最后一名玩家退出时会误判为仍有玩家在线，导致状态机卡在 WAITING/ROLE_SELECTING
     * 无法重开。这里按 uuid 显式排除退出者，修复该判定。
     */
    private boolean isNoOtherPlayerOnline(Player quittingPlayer){
        if(quittingPlayer == null) return false;
        for(Player onlinePlayer : Bukkit.getOnlinePlayers()){
            if(!onlinePlayer.getUniqueId().equals(quittingPlayer.getUniqueId())){
                return false;
            }
        }
        return true;
    }

    // ==================== 事件订阅 ====================

    /** 订阅本阶段需要的 4 类事件（加入/退出/库存点击/右键物品） */
    private void subscribeEvents(){
        joinSubscription = GameEventBus.subscribe(ShdfPlayerJoinEvent.class, this::handlePlayerJoin);
        quitSubscription = GameEventBus.subscribe(ShdfPlayerQuitEvent.class, this::handlePlayerQuit);
        inventoryClickSubscription = GameEventBus.subscribe(InventoryClickGameItemEvent.class, this::handleInventoryClick);
        rightClickGameItemSubscription = GameEventBus.subscribe(RightClickGameItemEvent.class, this::handleRightClickGameItem);
    }

    /** 注销全部事件订阅（带空判，阶段重复进出安全） */
    private void unsubscribeEvents(){
        if(joinSubscription != null){
            joinSubscription.unsubscribe();
            joinSubscription = null;
        }
        if(quitSubscription != null){
            quitSubscription.unsubscribe();
            quitSubscription = null;
        }
        if(inventoryClickSubscription != null){
            inventoryClickSubscription.unsubscribe();
            inventoryClickSubscription = null;
        }
        if(rightClickGameItemSubscription != null){
            rightClickGameItemSubscription.unsubscribe();
            rightClickGameItemSubscription = null;
        }
    }
}
