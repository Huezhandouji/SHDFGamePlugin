package com.sHDFGamePlugin.phase;

import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.core.GameState;
import com.sHDFGamePlugin.core.GameStateMachine;
import com.sHDFGamePlugin.domain.team.PlayerState;
import com.sHDFGamePlugin.domain.team.PlayerStatus;
import com.sHDFGamePlugin.domain.team.ShdfTeam;
import com.sHDFGamePlugin.domain.team.TeamManager;
import com.sHDFGamePlugin.infrastructure.GameEventBus;
import com.sHDFGamePlugin.infrastructure.HashBiMap;
import com.sHDFGamePlugin.infrastructure.RoleBridge;
import com.sHDFGamePlugin.infrastructure.config.ConfigManager;
import com.sHDFGamePlugin.infrastructure.event.InventoryClickGameItemEvent;
import com.sHDFGamePlugin.infrastructure.event.RightClickGameItemEvent;
import com.sHDFGamePlugin.infrastructure.event.ShdfPlayerJoinEvent;
import com.sHDFGamePlugin.infrastructure.event.ShdfPlayerQuitEvent;
import com.sHDFGamePlugin.infrastructure.gui.ChestGui;
import com.sHDFGamePlugin.infrastructure.item.GameItem;
import com.sHDFGamePlugin.infrastructure.item.GameItemRegistry;
import com.sHDFGamePlugin.util.GameCountdown;
import com.sHDFGamePlugin.util.MessageUtil;
import com.shadowHunterRolesPlugin.registry.RoleRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * 角色选择阶段：参战玩家从本队角色池中选择整场使用的角色。
 * <p>
 * 职责：
 * - 进入时自动分配 UNKNOWN 玩家、按人数判定是否启用重复角色、发放选择物品；
 * - 右键物品打开角色选择 GUI，选择后记录 selectedRoleId 并应用角色；
 * - 倒计时结束（或无倒计时时全员选完）为未选玩家自动分配随机角色，部署进入 PLAYING。
 */
public class RoleSelectingPhase implements GamePhase {

    private RoleSelectingPhase(){}

    private static final RoleSelectingPhase INSTANCE = new RoleSelectingPhase();
    public static RoleSelectingPhase getInstance(){
        return INSTANCE;
    }

    //GameItem id（阶段前缀风格）
    private static final String PREFIX = "gameItem_roleSelectingPhase_";
    private static final String attackerRoleSelectorId = PREFIX + "roleSelector_attacker";
    private static final String defenderRoleSelectorId = PREFIX + "roleSelector_defender";

    //断线保护时长（tick）：退出后保留 PlayerStatus，超时仍未重连才移除
    private static final long DISCONNECT_PROTECTION_TICKS = 1200;
    //角色选择倒计时默认时长（tick）：duration 未配置或为 0 时使用
    private static final int DEFAULT_ROLE_SELECTION_DURATION = 600;

    //双方可用角色池
    private List<String> availableAttackerRoleIds = new ArrayList<>();
    private List<String> availableDefenderRoleIds = new ArrayList<>();

    //已注册的角色按钮（双向表：角色名 <-> 按钮 GameItem id，按阵营分表，点击校验以此为准）
    private final HashBiMap<String, String> registeredAttackerRoleButtonIds = new HashBiMap<>();
    private final HashBiMap<String, String> registeredDefenderRoleButtonIds = new HashBiMap<>();

    //倒计时
    private GameCountdown countdown;

    //事件订阅
    private GameEventBus.Subscription joinSubscription;
    private GameEventBus.Subscription quitSubscription;
    private GameEventBus.Subscription inventoryClickSubscription;
    private GameEventBus.Subscription rightClickGameItemSubscription;

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

        //7. 注册选择物品与角色按钮
        registerRoleSelectorItems();
        registerRoleButtons();

        //8. 启动倒计时
        startCountdown();

        //9. 订阅事件
        subscribeEvents();

        teleportAllPlayersToSpawnpoint();
    }

    @Override
    public void onExit() {
        unsubscribeEvents();
        cancelCountdown();
        ChestGui.closeAllGuis();
        //清理快捷栏选择物品
        for(Player player : Bukkit.getOnlinePlayers()){
            player.getInventory().setItem(0, null);
        }


    }

    // ==================== 进入阶段 ====================

    private void loadAvailableRoleIds(){
        ConfigManager config = ConfigManager.getInstance();
        if(config.getSelectedMapConfig() == null){
            GameContext.getInstance().getPlugin().getLogger().warning("[RoleSelectingPhase] 未选择地图, 角色池为空!");
            return;
        }
        availableAttackerRoleIds = config.getSelectedMapConfig().getAttackerRoles();
        availableDefenderRoleIds = config.getSelectedMapConfig().getDefenderRoles();
    }

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

    private void registerRoleButtons(){
        //每次进入阶段重新注册，两张表以本次为准
        registeredAttackerRoleButtonIds.clear();
        registeredDefenderRoleButtonIds.clear();
        registerRoleButtonsForTeam(availableAttackerRoleIds, ShdfTeam.ATTACKER);
        registerRoleButtonsForTeam(availableDefenderRoleIds, ShdfTeam.DEFENDER);
    }

    private void registerRoleButtonsForTeam(List<String> roleIds, ShdfTeam team){
        RoleBridge roleBridge = RoleBridge.getInstance();
        String side;
        if(team == ShdfTeam.ATTACKER){
            side = "attacker";
        }
        else{
            side = "defender";
        }
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
            String gameItemId = PREFIX + "role_" + side + "_" + roleId;
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

    private void startCountdown(){
        //duration <= 0（未配置/写 0）时回退默认 600 tick；只跑倒计时，不做"手动选完"判定
        int duration = ConfigManager.getInstance().getRoleSelectionDuration();
        if(duration <= 0){
            duration = DEFAULT_ROLE_SELECTION_DURATION;
        }
        //自动 +1 补偿首 tick，配置里无需自行加 1
        countdown = new GameCountdown(GameContext.getInstance().getPlugin(), duration + 1);
        countdown.setOnTick(tick -> {
            //里程碑播报：剩余 30秒 / 20秒 / 10秒 时向全员广播（与准备阶段一致）
            if(tick == 600 || tick == 400 || tick == 200){
                MessageUtil.sendPrefixedMessageToAllPlayers(
                        Component.text("对局在 " + (tick / 20) + " 秒后开启", NamedTextColor.GOLD));
            }
        });
        countdown.setOnFinish(() -> finishRoleSelection());
        countdown.start();
    }

    private void cancelCountdown(){
        if(countdown != null && countdown.isRunning()){
            countdown.cancel("角色选择阶段结束");
        }
        countdown = null;
    }

    // ==================== 结束阶段（倒计时结束） ====================

    private void finishRoleSelection(){
        //为未选角色的参战玩家自动分配
        autoAssignRoles();
        //部署：传送出生点 + 进入战斗状态
        deployCombatants();
        //进入对局
        GameStateMachine.getInstance().transitionTo(GameState.PLAYING);
    }

    /** 按重复角色规则为未选玩家随机分配：允许重复→全池随机；禁止重复→未占用角色随机 */
    private void autoAssignRoles(){
        autoAssignRolesForTeam(ShdfTeam.ATTACKER, availableAttackerRoleIds);
        autoAssignRolesForTeam(ShdfTeam.DEFENDER, availableDefenderRoleIds);
    }

    private void autoAssignRolesForTeam(ShdfTeam team, List<String> rolePool){
        RoleBridge roleBridge = RoleBridge.getInstance();
        Random random = new Random();
        for(UUID uuid : TeamManager.getInstance().getAllPlayersUuidsInTeam(team)){
            if(Bukkit.getPlayer(uuid) == null) continue; //离线玩家不分配
            PlayerStatus status = TeamManager.getInstance().getPlayerStatus(uuid);
            if(status == null || status.getSelectedRoleId() != null) continue;

            List<String> candidates = new ArrayList<>(rolePool);
            if(!roleBridge.isAllowDuplicateRoles()){
                candidates.removeAll(roleBridge.getOccupiedRoles(team));
            }
            if(candidates.isEmpty()){
                //防御性兜底：无空闲角色时强制全池随机并记录警告
                candidates = new ArrayList<>(rolePool);
                GameContext.getInstance().getPlugin().getLogger().warning("[RoleSelectingPhase] 自动分配时无空闲角色, 强制随机分配!");
            }
            String roleId = candidates.get(random.nextInt(candidates.size()));
            if(roleBridge.setPlayerRole(uuid, roleId)){
                status.setSelectedRoleId(roleId);
            }
            else{
                GameContext.getInstance().getPlugin().getLogger().warning("[RoleSelectingPhase] 自动分配角色失败: " + uuid + " -> " + roleId);
            }
        }
    }

    /** 部署参战玩家：传送至阵营出生点并进入战斗状态 */
    private void deployCombatants(){
        ConfigManager config = ConfigManager.getInstance();
        if(config.getSelectedMapConfig() == null) return;
        World world = Bukkit.getWorld(config.getSelectedMapConfig().getWorld());
        if(world == null){
            GameContext.getInstance().getPlugin().getLogger().warning("[RoleSelectingPhase] 地图世界不存在, 无法部署参战玩家!");
            return;
        }
        TeamManager teamManager = TeamManager.getInstance();
        for(Player player : Bukkit.getOnlinePlayers()){
            UUID uuid = player.getUniqueId();
            ShdfTeam team = teamManager.getTeam(uuid);
            if(team == null || !team.isCombatant()) continue;

            Vector spawn = switch (team){
                case ATTACKER -> config.getRoleSelectionAttackerSpawnpoint();
                case DEFENDER -> config.getRoleSelectionDefenderSpawnpoint();
                default -> null;
            };
            if(spawn != null){
                player.teleport(spawn.toLocation(world));
            }
            player.setGameMode(GameMode.ADVENTURE);
            PlayerStatus status = teamManager.getPlayerStatus(uuid);
            if(status != null){
                status.setState(PlayerState.IN_BATTLE);
            }
        }
    }

    // ==================== 交互 ====================

    private void handleRightClickGameItem(RightClickGameItemEvent event){
        String itemId = event.getGameItemId();
        if(itemId.equals(attackerRoleSelectorId) || itemId.equals(defenderRoleSelectorId)){
            openRoleSelectionGui(event.getPlayer());
        }
    }

    private void handleInventoryClick(InventoryClickGameItemEvent event){
        String itemId = event.getGameItemId();
        //通过双向表反查按钮 id 对应的角色名；两张表都查不到说明不是本阶段按钮
        String roleId = registeredAttackerRoleButtonIds.getByValue(itemId);
        if(roleId == null){
            roleId = registeredDefenderRoleButtonIds.getByValue(itemId);
        }
        if(roleId == null) return;
        selectRole(event.getPlayer(), roleId);
    }

    private void openRoleSelectionGui(Player player){
        TeamManager teamManager = TeamManager.getInstance();
        ShdfTeam team = teamManager.getTeam(player.getUniqueId());
        if(team == null || !team.isCombatant()) return;

        List<String> roleIds;
        if(team == ShdfTeam.ATTACKER){
            roleIds = availableAttackerRoleIds;
        }
        else{
            roleIds = availableDefenderRoleIds;
        }
        //只展示有效（已实现）角色，与注册逻辑一致
        RoleBridge roleBridge = RoleBridge.getInstance();
        List<String> validRoleIds = roleIds.stream().filter(roleBridge::isValidRoleId).toList();
        int rows = Math.max(1, (validRoleIds.size() + 8) / 9);

        String sideName;
        if(team == ShdfTeam.ATTACKER){
            sideName = "进攻方";
        }
        else{
            sideName = "防守方";
        }
        ChestGui.Builder builder = ChestGui.Builder.create()
                .title(Component.text("选择角色 - " + sideName,
                        NamedTextColor.YELLOW).decorate(TextDecoration.BOLD))
                .rows(rows);

        int slot = 0;
        for(String roleId : validRoleIds){
            builder.setSlot(slot++, buildRoleButton(player, roleId, team));
        }
        builder.build().open(player);
    }

    private ItemStack buildRoleButton(Player player, String roleId, ShdfTeam team){
        RoleBridge roleBridge = RoleBridge.getInstance();

        //RoleAPI 对不存在的角色返回 Material.AIR 而非 null，AIR 同样视为缺失
        Material icon = roleBridge.getRoleIcon(roleId);
        if(icon == null || icon == Material.AIR){
            icon = Material.PAPER;
        }

        ItemStack button = new ItemStack(icon);
        ItemMeta meta = button.getItemMeta();

        Component displayName = roleBridge.getRoleDisplayName(roleId);
        if(displayName == null || displayName.equals(Component.empty())){
            displayName = Component.text(roleId, NamedTextColor.WHITE);
        }
        meta.displayName(displayName);

        List<Component> lore = new ArrayList<>();
        Component description = roleBridge.getRoleDescription(roleId);
        if(description != null && !description.equals(Component.empty())){
            lore.add(description);
        }
        //禁用重复时：被其他玩家占用的角色标记为不可选
        if(!roleBridge.isAllowDuplicateRoles()){
            String myRole = roleBridge.getPlayerRole(player.getUniqueId());
            boolean occupiedByOther = roleBridge.getOccupiedRoles(team).contains(roleId)
                    && (myRole == null || !myRole.equals(roleId));
            if(occupiedByOther){
                lore.add(Component.text("已被占用", NamedTextColor.RED, TextDecoration.BOLD));
            }
        }
        meta.lore(lore);

        String side;
        if(team == ShdfTeam.ATTACKER){
            side = "attacker";
        }
        else{
            side = "defender";
        }
        meta = GameItem.applyIdOnItemMeta(PREFIX + "role_" + side + "_" + roleId, meta);
        button.setItemMeta(meta);
        return button;
    }

    private void selectRole(Player player, String roleId){
        UUID uuid = player.getUniqueId();
        TeamManager teamManager = TeamManager.getInstance();
        ShdfTeam team = teamManager.getTeam(uuid);
        if(team == null || !team.isCombatant()){
            MessageUtil.sendMessageWithPrefix(player, Component.text("只有参战人员才能选择角色", NamedTextColor.GRAY));
            return;
        }

        boolean success = RoleBridge.getInstance().setPlayerRole(uuid, roleId);
        if(!success){
            MessageUtil.sendMessageWithPrefix(player, Component.text("该角色不可用或已被占用", NamedTextColor.RED));
            return;
        }

        PlayerStatus status = teamManager.getPlayerStatus(uuid);
        if(status != null){
            status.setSelectedRoleId(roleId);
        }
        player.closeInventory();
        MessageUtil.sendMessageWithPrefix(player, Component.text("你选择了角色: " + roleId, NamedTextColor.GREEN));

        //刷新其他玩家已打开的角色菜单（占用标记更新）
        refreshOpenRoleGuis();
    }

    private void refreshOpenRoleGuis(){
        for(Player player : Bukkit.getOnlinePlayers()){
            ChestGui gui = ChestGui.getOpenGui(player);
            if(gui == null) continue;
            ShdfTeam team = TeamManager.getInstance().getTeam(player.getUniqueId());
            if(team == null || !team.isCombatant()) continue;

            List<String> roleIds;
            if(team == ShdfTeam.ATTACKER){
                roleIds = availableAttackerRoleIds;
            }
            else{
                roleIds = availableDefenderRoleIds;
            }
            int slot = 0;
            for(String roleId : roleIds){
                gui.setSlot(slot++, buildRoleButton(player, roleId, team));
            }
            gui.refresh();
        }
    }

    // ==================== 玩家加入/退出 ====================

    private void handlePlayerJoin(ShdfPlayerJoinEvent event){
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        TeamManager teamManager = TeamManager.getInstance();

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

        MessageUtil.sendMessageWithPrefix(player, Component.text("你在对局中加入, 已经被自动设置为旁观者, 请等待对局结束"));
        player.getInventory().clear();
        player.setGameMode(GameMode.SPECTATOR);
    }

    private void handlePlayerQuit(ShdfPlayerQuitEvent event){
        UUID uuid = event.getPlayer().getUniqueId();

        if(Bukkit.getOnlinePlayers().isEmpty()){
            GameStateMachine.getInstance().transitionTo(GameState.IDLE);
            return;
        }

        //断线保护：保留 PlayerStatus 与角色占用，超时仍未重连才移除
        scheduleDisconnectRemoval(uuid);
    }

    /** 断线保护：一段时间后玩家仍未上线，则移除记录并释放角色 */
    private void scheduleDisconnectRemoval(UUID uuid){
        GameContext.getInstance().getPlugin().getServer().getGlobalRegionScheduler().runDelayed(
                GameContext.getInstance().getPlugin(),
                task -> {
                    if(Bukkit.getPlayer(uuid) == null){
                        TeamManager.getInstance().removePlayer(uuid);
                        RoleBridge.getInstance().clearPlayerRole(uuid);
                    }
                },
                DISCONNECT_PROTECTION_TICKS
        );
    }

    // ==================== 事件订阅 ====================

    private void subscribeEvents(){
        joinSubscription = GameEventBus.subscribe(ShdfPlayerJoinEvent.class, this::handlePlayerJoin);
        quitSubscription = GameEventBus.subscribe(ShdfPlayerQuitEvent.class, this::handlePlayerQuit);
        inventoryClickSubscription = GameEventBus.subscribe(InventoryClickGameItemEvent.class, this::handleInventoryClick);
        rightClickGameItemSubscription = GameEventBus.subscribe(RightClickGameItemEvent.class, this::handleRightClickGameItem);
    }

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
