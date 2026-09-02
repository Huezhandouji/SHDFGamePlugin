package com.sHDFGamePlugin.phase;

import com.sHDFGamePlugin.SHDFGamePlugin;
import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.core.GameState;
import com.sHDFGamePlugin.core.GameStateMachine;
import com.sHDFGamePlugin.domain.spawn.SpawnManager;
import com.sHDFGamePlugin.domain.team.PlayerState;
import com.sHDFGamePlugin.domain.team.ShdfTeam;
import com.sHDFGamePlugin.domain.team.TeamManager;
import com.sHDFGamePlugin.infrastructure.GameEventBus;
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
import com.sHDFGamePlugin.util.SoundUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scoreboard.*;


import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class WaitingPhase implements GamePhase {

    private static final WaitingPhase INSTANCE = new WaitingPhase();

    //GameItem的id
    private static final String teamSelectorId = "gameItem_waitingPhase_teamSelector";
    private static final String readyToggleId = "gameItem_waitingPhase_readyToggle";
    private static final String mapVoteId = "gameItem_waitingPhase_mapVote";

    private static final String teamButtonAttackerId = "gameItem_waitingPhase_teamButtonAttacker";
    private static final String teamButtonDefenderId = "gameItem_waitingPhase_teamButtonDefender";
    private static final String teamButtonSpectatorId = "gameItem_waitingPhase_teamButtonSpectator";
    private static final String teamButtonUnknownId = "gameItem_waitingPhase_teamButtonUnknown";
    //事件订阅
    private GameEventBus.Subscription joinSubscription;
    private GameEventBus.Subscription rightClickSubscription;
    private GameEventBus.Subscription inventoryClickSubscription;
    private GameEventBus.Subscription quitSubscription;

    //侧边栏计分板
    private Objective sidebarObjective;

    private GameCountdown countdown;

    //玩家名队伍前缀
    private Team attackerTeam;
    private Team defenderTeam;
    private Team spectatorTeam;
    private Team unknownTeam;


    private WaitingPhase(){
    }

    public static WaitingPhase getInstance(){
        return INSTANCE;
    }



    @Override
    public void onEnter() {
        TeamManager.getInstance().removeAllPlayers();

        registerGameItems();

        initScoreboardTeam();

        joinSubscription = GameEventBus.subscribe(ShdfPlayerJoinEvent.class, event -> {
            handlePlayerJoin(event.getPlayer());
        });
        rightClickSubscription = GameEventBus.subscribe(RightClickGameItemEvent.class, this::handleRightClickGameItem);
        inventoryClickSubscription = GameEventBus.subscribe(InventoryClickGameItemEvent.class, this::handleInventoryClickGameItem);
        quitSubscription = GameEventBus.subscribe(ShdfPlayerQuitEvent.class, event -> handlePlayerQuit(event.getPlayer()));

        createSidebarObjective();
        updateSidebarObjective();

        for(Player player : Bukkit.getOnlinePlayers()){
            handlePlayerJoin(player);
        }
        checkStartConditions();
    }

    @Override
    public void onExit() {
        if(joinSubscription != null){
            joinSubscription.unsubscribe();
            joinSubscription = null;
        }
        if(rightClickSubscription != null){
            rightClickSubscription.unsubscribe();
            rightClickSubscription = null;
        }
        if(inventoryClickSubscription != null){
            inventoryClickSubscription.unsubscribe();
            inventoryClickSubscription = null;
        }
        if(quitSubscription != null){
            quitSubscription.unsubscribe();
            quitSubscription = null;
        }
        clearScoreboardTeam();
        cancelCountdown("等待阶段结束");
        resetExperienceBar();
        //阶段切换清理：关闭所有打开的游戏 GUI
        ChestGui.closeAllGuis();
    }

    private void initScoreboardTeam(){
        Scoreboard sb = SHDFGamePlugin.getInstance().getTempScoreboard();
        if(attackerTeam == null){
            attackerTeam = sb.registerNewTeam("attacker");
            attackerTeam.prefix(Component.text("#进攻方SHADOW#", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
        }
        if(defenderTeam == null){
            defenderTeam = sb.registerNewTeam("defender");
            defenderTeam.prefix(Component.text("#防守方HUNTER#", NamedTextColor.YELLOW, TextDecoration.BOLD));
        }
        if(spectatorTeam == null){
            spectatorTeam = sb.registerNewTeam("spectator");
            spectatorTeam.prefix(Component.text("#旁观者#", NamedTextColor.GRAY, TextDecoration.BOLD));
        }
        if(unknownTeam == null){
            unknownTeam = sb.registerNewTeam("unknown");
            unknownTeam.prefix(Component.text("#随机分配#", NamedTextColor.BLUE, TextDecoration.BOLD));
        }
    }

    private void clearScoreboardTeam(){
        attackerTeam.unregister();
        attackerTeam = null;
        defenderTeam.unregister();
        defenderTeam = null;
        spectatorTeam.unregister();
        spectatorTeam = null;
        unknownTeam.unregister();
        unknownTeam = null;
    }

    private void startCountdown(){
        //自动 +1 补偿首 tick，配置里无需自行加 1
        int durationTicks = ConfigManager.getInstance().getCountdownTime() + 1;
        countdown = new GameCountdown(GameContext.getInstance().getPlugin(), durationTicks);
        countdown.setOnTick(tick -> {
            //标题倒计时：仅在最后 10 秒（200 tick）内显示
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
            //里程碑提醒：剩余 60秒 / 30秒 / 10秒 时发送消息
            if(tick == 1200 || tick == 600 || tick == 400 || tick == 200){
                int seconds = tick / 20;
                for(Player player : Bukkit.getOnlinePlayers()){
                    MessageUtil.sendMessageWithPrefix(player,
                            Component.text("对局在 " + seconds + " 秒后开启", NamedTextColor.GOLD));
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                }
            }
            //经验条显示剩余秒数（每秒更新一次）
            if(tick % 20 == 0){
                updateExperienceBar(tick);
            }
        });
        countdown.setOnCancel(reason -> {
            //最后10秒（≤200 tick）内取消：照常 title 提示（含原音效）
            if(countdown != null && countdown.getRemainingTicks() <= 200){
                for(Player player : Bukkit.getOnlinePlayers()){
                    Title title = Title.title(
                            Component.text("-", NamedTextColor.RED, TextDecoration.BOLD),
                            Component.text("倒计时取消", NamedTextColor.RED, TextDecoration.BOLD),
                            Title.Times.times(
                                    Duration.ZERO,
                                    Duration.ofMillis(3000),
                                    Duration.ZERO
                            )
                    );
                    player.showTitle(title);
                    MessageUtil.sendMessageWithPrefix(player, Component.text("倒计时取消, 原因: " + reason, NamedTextColor.RED));
                    player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 1f, 1f);
                }
            }
            //其他时候取消：仅聊天栏消息，不播放音效
            else{
                for(Player player : Bukkit.getOnlinePlayers()){
                    MessageUtil.sendMessageWithPrefix(player, Component.text("倒计时取消, 原因: " + reason, NamedTextColor.RED));
                }
            }
            resetExperienceBar();
        });
        countdown.setOnFinish(() -> {
            resetExperienceBar();
            GameStateMachine.getInstance().transitionTo(GameState.ROLE_SELECTING);
        });

        countdown.start();
    }

    /** 经验条数字显示剩余倒计时秒数，经验值比例显示当前秒内的进度 */
    private void updateExperienceBar(int remainingTicks){
        int seconds = (int) Math.ceil(remainingTicks / 20.0);
        float progress = (remainingTicks % 20) / 20f;
        for(Player player : Bukkit.getOnlinePlayers()){
            player.setLevel(seconds);
            player.setExp(progress);
        }
    }

    /** 清除经验条倒计时显示 */
    private void resetExperienceBar(){
        for(Player player : Bukkit.getOnlinePlayers()){
            player.setLevel(0);
            player.setExp(0f);
        }
    }

    private void cancelCountdown(String reason){
        if(countdown != null && countdown.isRunning()){
            countdown.cancel(reason);
        }
        countdown = null;
    }

    private void createSidebarObjective(){
        sidebarObjective = SHDFGamePlugin.getInstance().getTempScoreboard().registerNewObjective("waiting_phase_sidebar", Criteria.DUMMY,
                Component.text("DECAYING FRONTLINE", NamedTextColor.GOLD, TextDecoration.BOLD));
        sidebarObjective.setDisplaySlot(DisplaySlot.SIDEBAR);
    }

    @SuppressWarnings("deprecated")
    private void updateSidebarObjective(){
        ConfigManager config = ConfigManager.getInstance();
        sidebarObjective.unregister();
        createSidebarObjective();

        sidebarObjective.getScore(ChatColor.WHITE + "" + ChatColor.BOLD + "准备阶段").setScore(-1);
        sidebarObjective.getScore("  ").setScore(-2);
        sidebarObjective.getScore(ChatColor.WHITE + "" + ChatColor.BOLD + "对局开始需要:").setScore(-3);
        if(isConformMinPlayerPerSide()){
            sidebarObjective.getScore(ChatColor.GREEN + "- 双方各至少 " + config.getMinPopulationPerSide() + ChatColor.WHITE + " 名玩家").setScore(-4);
        }
        else{
            sidebarObjective.getScore(ChatColor.GRAY + "- 双方各至少 " + config.getMinPopulationPerSide() + " 名玩家").setScore(-4);
        }

        if(isConformMaxSideDiff()){
            sidebarObjective.getScore(ChatColor.GREEN + "- 阵营人数差不超过 " + config.getMaxSideDiff()).setScore(-5);
        }
        else{
            sidebarObjective.getScore(ChatColor.GRAY + "- 阵营人数差不超过 " + config.getMaxSideDiff()).setScore(-5);
        }

        if(config.isRequireReadyMode()){
            if(isAllPlayersReady()){
                sidebarObjective.getScore(ChatColor.GREEN + "- 除观战者外的参战人员准备").setScore(-6);
            }
            else{
                sidebarObjective.getScore(ChatColor.GRAY + "- 除观战者外的参战人员准备").setScore(-6);
            }
        }
        sidebarObjective.getScore("   ").setScore(-7);
    }

    private void registerGameItems(){
        //快捷栏物品：右键发布事件
        registerRightClickItem(teamSelectorId);
        registerRightClickItem(readyToggleId);
        registerRightClickItem(mapVoteId);
        //选队菜单物品：库存点击发布事件
        registerInventoryClickItem(teamButtonAttackerId);
        registerInventoryClickItem(teamButtonDefenderId);
        registerInventoryClickItem(teamButtonSpectatorId);
        registerInventoryClickItem(teamButtonUnknownId);
    }

    /** 注册一个"右键即发布事件"的快捷栏物品 */
    private void registerRightClickItem(String itemId){
        GameItemRegistry.createAndRegister(itemId, builder ->
                builder.canDrop(false).canMove(false)
                        .rightClickHandler(event ->
                                GameEventBus.publish(new RightClickGameItemEvent(event.getPlayer(), itemId))));
    }

    /** 注册一个"库存点击即发布事件"的菜单物品 */
    private void registerInventoryClickItem(String itemId){
        GameItemRegistry.createAndRegister(itemId, builder ->
                builder.canDrop(false).canMove(false)
                        .inventoryClickHandler(event ->
                                GameEventBus.publish(new InventoryClickGameItemEvent((Player) event.getWhoClicked(), itemId))));
    }

    private void handlePlayerJoin(Player player){
        //传送玩家到大厅位置
        ConfigManager config = ConfigManager.getInstance();
        World world = Bukkit.getWorld(config.getWaitingWorld());
        if(world == null){
            player.kick(Component.text("SHDF插件发生错误, 您已被踢出游戏!", NamedTextColor.RED, TextDecoration.BOLD));
            return;
        }
        Location location = config.getLobbySpawnpoint().toLocation(world);
        player.teleport(location);

        TeamManager.getInstance().removePlayer(player.getUniqueId());
        registerPlayer(player);
        player.setScoreboard(SHDFGamePlugin.getInstance().getTempScoreboard());
        updateSidebarObjective();
        checkStartConditions();


    }

    private void handlePlayerQuit(Player player){
        TeamManager.getInstance().removePlayer(player.getUniqueId());
        SpawnManager.getInstance().removePlayer(player.getUniqueId());
        RoleBridge.getInstance().clearPlayerRole(player.getUniqueId());

        if(Bukkit.getOnlinePlayers().isEmpty()){
            GameStateMachine.getInstance().transitionTo(GameState.IDLE);
            return;
        }

        updateSidebarObjective();
        checkStartConditions();
    }

    private void registerPlayer(Player player){
        TeamManager teamManager = TeamManager.getInstance();
        if(teamManager.getTeam(player.getUniqueId()) == null){
            ConfigManager config =  ConfigManager.getInstance();
            ShdfTeam initialShdfTeam = config.isDefaultSpectatorOrUnknown() ? ShdfTeam.SPECTATOR : ShdfTeam.UNKNOWN;
            teamManager.addPlayer(player.getUniqueId(), initialShdfTeam, PlayerState.WAITING);
            handleTeamSelect(player, initialShdfTeam);
        }
        updateWaitingGameItems(player);
    }

    private void checkStartConditions(){
        ConfigManager config = ConfigManager.getInstance();
        TeamManager teamManager = TeamManager.getInstance();

        boolean isMetConditions = true;
        String countdownCancelReason = "未知的取消原因, 检查插件逻辑!";

        //双方人数达到最低要求
        if(!isConformMinPlayerPerSide()){
            isMetConditions = false;
            countdownCancelReason = "双方最低人数要求不再达标";
        }
        //人数差不超过限制
        if(!isConformMaxSideDiff()){
            isMetConditions = false;
            countdownCancelReason = "阵营人数差超出限制, 这个原因通常不会触发, 检查插件逻辑!";
        }
        //如果要求准备
        if(!isAllPlayersReady()){
            isMetConditions = false;
            countdownCancelReason = "新加入玩家或者有玩家取消了准备";
        }
        //至少有一张可用地图
        if(config.getSelectedMapConfig() == null) isMetConditions = false;

        if(isMetConditions){
            if(countdown == null || !countdown.isRunning()){
                startCountdown();
            }
        }else {
            if(countdown != null && countdown.isRunning()){
                cancelCountdown(countdownCancelReason);
            }
        }


    }
    //检查各项开启游戏指标
    private boolean isConformMinPlayerPerSide(){
        ConfigManager config = ConfigManager.getInstance();
        TeamManager teamManager = TeamManager.getInstance();
        int attackers = teamManager.getPlayerPopulationOnTeam(ShdfTeam.ATTACKER);
        int defenders = teamManager.getPlayerPopulationOnTeam(ShdfTeam.DEFENDER);
        int unknowns = teamManager.getPlayerPopulationOnTeam(ShdfTeam.UNKNOWN);
        int min = config.getMinPopulationPerSide();

        //双方各自还缺多少人，缺口只能由随机阵营（UNKNOWN）玩家补齐
        int attackerShortage = Math.max(0, min - attackers);
        int defenderShortage = Math.max(0, min - defenders);

        //随机玩家总数必须能同时填满两个缺口（分配后双方都达到最低人数）
        return unknowns >= attackerShortage + defenderShortage;
    }

    private boolean isConformMaxSideDiff(){
        return TeamManager.getInstance().getSideDiff() <= ConfigManager.getInstance().getMaxSideDiff();
    }

    private boolean isAllPlayersReady(){
        ConfigManager config = ConfigManager.getInstance();
        TeamManager teamManager = TeamManager.getInstance();
        if(!config.isRequireReadyMode()) return true;
        if(teamManager.getPlayerPopulation() == 0) return false;
        //除观战者外的参战人员（进攻/防守/随机）都需准备
        for(UUID uuid : teamManager.getAllPlayersUuidsInTeam(ShdfTeam.ATTACKER)){
            if(!teamManager.isReady(uuid)) return false;
        }
        for(UUID uuid : teamManager.getAllPlayersUuidsInTeam(ShdfTeam.DEFENDER)){
            if(!teamManager.isReady(uuid)) return false;
        }
        for(UUID uuid : teamManager.getAllPlayersUuidsInTeam(ShdfTeam.UNKNOWN)){
            if(!teamManager.isReady(uuid)) return false;
        }
        return true;
    }

    private void updateWaitingGameItems(Player player){
        TeamManager teamManager = TeamManager.getInstance();
        UUID playerId = player.getUniqueId();
        ShdfTeam shdfTeam = teamManager.getTeam(playerId);
        if(shdfTeam == null) return;

        Inventory inv = player.getInventory();
        inv.setItem(0, createTeamSelectorItem());

        //准备物品发给非观战者
        if(shdfTeam.isCombatant() && ConfigManager.getInstance().isRequireReadyMode()){
            inv.setItem(1, createReadyItem(teamManager.isReady(playerId)));
        }
        else{
            inv.setItem(1, null);
        }

        inv.setItem(2, createMapVoteItem());
    }

    private ItemStack createTeamSelectorItem(){
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("选择阵营", NamedTextColor.GOLD, TextDecoration.BOLD));
        meta = GameItem.applyIdOnItemMeta(teamSelectorId, meta);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createReadyItem(boolean isReady){
        ItemStack item;
        if(isReady){
            item = new ItemStack(Material.DIAMOND_SWORD);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.text("已经准备", NamedTextColor.GREEN, TextDecoration.BOLD));
            item.setItemMeta(meta);
        }
        else{
            item = new ItemStack(Material.WOODEN_SWORD);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.text("尚未准备", NamedTextColor.RED, TextDecoration.BOLD));
            item.setItemMeta(meta);
        }
        ItemMeta meta = item.getItemMeta();
        meta.lore(List.of(
                Component.text("除观战者外的所有参战人员准备后, 游戏才会开始", NamedTextColor.GRAY)
        ));
        meta = GameItem.applyIdOnItemMeta(readyToggleId, meta);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createMapVoteItem(){
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("票选地图", NamedTextColor.BLUE, TextDecoration.BOLD));
        meta.lore(List.of(
                Component.text("尚未实现", NamedTextColor.GRAY)
        ));
        meta = GameItem.applyIdOnItemMeta(mapVoteId, meta);
        item.setItemMeta(meta);
        return item;
    }

    private void handleRightClickGameItem(RightClickGameItemEvent event){
        String itemId = event.getGameItemId();
        Player player = event.getPlayer();

        switch (itemId){
            case teamSelectorId -> openTeamSelectionGui(player);
            case readyToggleId-> toggleReady(player);
            case mapVoteId -> useMapSelector(player);
            default -> {
                GameContext.getInstance().getPlugin().getLogger().warning("Player " + player.getName() + " try to use a GameItem not belonging to WaitingPhase!");
            }
        }
    }

    private void handleInventoryClickGameItem(InventoryClickGameItemEvent event){
        String itemId = event.getGameItemId();
        Player player = event.getPlayer();

        switch (itemId){
            case teamButtonAttackerId -> handleTeamSelect(player, ShdfTeam.ATTACKER);
            case teamButtonDefenderId -> handleTeamSelect(player, ShdfTeam.DEFENDER);
            case teamButtonSpectatorId -> handleTeamSelect(player, ShdfTeam.SPECTATOR);
            case teamButtonUnknownId -> handleTeamSelect(player, ShdfTeam.UNKNOWN);
            default -> {
                //忽略其他物品
            }
        }
    }

    private void handleTeamSelect(Player player, ShdfTeam targetShdfTeam){
        TeamManager teamManager = TeamManager.getInstance();
        UUID uuid = player.getUniqueId();
        ShdfTeam currentShdfTeam = teamManager.getTeam(uuid);

        //检查人数差是否可接受（仅对战斗阵营生效）
        if(targetShdfTeam.isCombatant()){
            int attackers = teamManager.getPlayerPopulationOnTeam(ShdfTeam.ATTACKER);
            int defenders = teamManager.getPlayerPopulationOnTeam(ShdfTeam.DEFENDER);
            if(currentShdfTeam == ShdfTeam.ATTACKER) attackers--;
            if(currentShdfTeam == ShdfTeam.DEFENDER) defenders--;
            if(targetShdfTeam == ShdfTeam.ATTACKER) attackers++;
            if(targetShdfTeam == ShdfTeam.DEFENDER) defenders++;

            int diff = Math.abs(attackers - defenders);
            if(diff > ConfigManager.getInstance().getMaxSideDiff()){
                player.sendMessage(Component.text("SHDF>>无法加入该阵营, 因为切换后人数差过大"));
                return;
            }
        }

        teamManager.setTeam(uuid, targetShdfTeam);
        player.closeInventory();

        //切换准备状态
        if(targetShdfTeam.isCombatant()){
            TeamManager.getInstance().setReady(uuid, false);
        }
        else{
            TeamManager.getInstance().setReady(uuid, true);
        }
        updateSidebarObjective();

        player.sendMessage(Component.text("SHDF>>你加入了" + targetShdfTeam.name()));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1);
        updateWaitingGameItems(player);

        switch (targetShdfTeam){
            case ATTACKER -> attackerTeam.addPlayer(player);
            case DEFENDER -> defenderTeam.addPlayer(player);
            case SPECTATOR -> spectatorTeam.addPlayer(player);
            case UNKNOWN -> unknownTeam.addPlayer(player);
        }

        checkStartConditions();
    }

    private void openTeamSelectionGui(Player player){
        ItemStack buttonAttacker = buildTeamButton(Material.NETHERITE_SWORD, "进攻方-SHADOW", NamedTextColor.LIGHT_PURPLE,
                teamButtonAttackerId, ShdfTeam.ATTACKER, null);
        ItemStack buttonDefender = buildTeamButton(Material.BEDROCK, "防守方-SHADOW", NamedTextColor.YELLOW,
                teamButtonDefenderId, ShdfTeam.DEFENDER, null);
        ItemStack buttonSpectator = buildTeamButton(Material.PLAYER_HEAD, "观众", NamedTextColor.BLUE,
                teamButtonSpectatorId, ShdfTeam.SPECTATOR,
                List.of(Component.text("对局开始后, 你将以观战者加入!", NamedTextColor.YELLOW, TextDecoration.BOLD)));
        ItemStack buttonUnknown = buildTeamButton(Material.STRUCTURE_VOID, "随机阵营", NamedTextColor.GREEN,
                teamButtonUnknownId, ShdfTeam.UNKNOWN,
                List.of(Component.text("你将会在游戏开始时被随机分配到进攻方或者防守方!", NamedTextColor.YELLOW, TextDecoration.BOLD)));

        ChestGui gui = ChestGui.Builder.create()
                .title(Component.text("选择阵营", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD))
                .rows(1)
                .setSlot(0, buttonAttacker)
                .setSlot(1, buttonDefender)
                .setSlot(2, buttonSpectator)
                .setSlot(3, buttonUnknown)
                .build();
        gui.open(player);
    }

    /** 构建选队按钮：标题 + 队伍人数 + 在线成员列表（附带 GameItem id） */
    private ItemStack buildTeamButton(Material material, String title, NamedTextColor color,
                                      String gameItemId, ShdfTeam team, List<Component> extraLore){
        ItemStack button = new ItemStack(material);
        ItemMeta meta = button.getItemMeta();
        meta.displayName(Component.text(title, color, TextDecoration.BOLD));

        List<Component> lore = new ArrayList<>();
        if(extraLore != null){
            lore.addAll(extraLore);
        }
        lore.add(Component.text("这个队伍有 " + TeamManager.getInstance().getPlayerPopulationOnTeam(team) + " 名玩家:", NamedTextColor.GRAY));
        for(UUID pid : TeamManager.getInstance().getAllPlayersUuidsInTeam(team)){
            Player p = Bukkit.getPlayer(pid);
            if(p != null){
                lore.add(Component.text(p.getName(), NamedTextColor.GRAY));
            }
            else{
                lore.add(Component.text("#无法通过uuid获取玩家", NamedTextColor.RED));
            }
        }

        meta.lore(lore);
        meta = GameItem.applyIdOnItemMeta(gameItemId, meta);
        button.setItemMeta(meta);
        return button;
    }

    private void toggleReady(Player player) {

        TeamManager teamManager = TeamManager.getInstance();
        UUID uuid = player.getUniqueId();

        //只有战斗人员才能准备
        ShdfTeam shdfTeam = teamManager.getTeam(uuid);
        if (shdfTeam == null || !shdfTeam.isCombatant()) {
            player.sendMessage(Component.text("SHDF>>只有战斗人员才能准备").color(NamedTextColor.GRAY));
            return;
        }

        boolean current = teamManager.isReady(uuid);
        boolean newReady = !current;
        teamManager.setReady(uuid, newReady);

        if(newReady) SoundUtil.playNoticeSuccessCombinedSound(player);
        else SoundUtil.playNoticeFailCombinedSound(player);

        updateWaitingGameItems(player);

        updateSidebarObjective();

        checkStartConditions();

    }
    private void useMapSelector(Player player){
    }


}
