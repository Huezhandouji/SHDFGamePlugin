package com.sHDFGamePlugin.phase.playing;

import com.sHDFGamePlugin.core.GameContext;
import com.sHDFGamePlugin.domain.sector.ActiveBomb;
import com.sHDFGamePlugin.domain.sector.BombState;
import com.sHDFGamePlugin.domain.sector.SectorManager;
import com.sHDFGamePlugin.domain.team.PlayerState;
import com.sHDFGamePlugin.domain.team.PlayerStatus;
import com.sHDFGamePlugin.domain.team.ShdfTeam;
import com.sHDFGamePlugin.domain.team.TeamManager;
import com.sHDFGamePlugin.infrastructure.GameEventBus;
import com.sHDFGamePlugin.infrastructure.config.ConfigManager;
import com.sHDFGamePlugin.infrastructure.config.MapConfig;
import com.sHDFGamePlugin.infrastructure.event.BombExplodedEvent;
import com.sHDFGamePlugin.infrastructure.regionNotation.CubeRegion;
import com.sHDFGamePlugin.util.MessageUtil;
import com.sHDFGamePlugin.util.ParticleUtil;
import com.sHDFGamePlugin.util.SoundUtil;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.UUID;

/**
 * 装弹/拆弹交互模块：读条进度（{@link BombProgress} 内部类）、范围内定位、进度显示、打断校验、
 * 完成时回调 {@link SectorManager}、读条期间的移动冻结、已安放炸弹的粒子表现。
 * <p>
 * 入口：{@code PlayingPhase} 的右键路由在确认物品与阵营后调用
 * {@link #tryStartBombProgress(Player, boolean)}；再次右键同一物品 = 取消（见
 * {@link #cancelActiveProgress(Player)}）。读条每 tick 由 {@link #startBombProgressTickTask()} 驱动，
 * 失效（离线/状态或阵营变化/离开范围/炸弹状态变化/受伤/对局结束）即打断。
 * <p>
 * 共享状态：进度表存于 {@link MatchSessionState#getActiveProgresses()}；任务与监听器句柄存于
 * {@link MatchSessionState}，由 {@code PlayingPhase.onEnter/onExit} 随阶段注册/注销。
 */
public final class BombInteractionController {

    private static final BombInteractionController INSTANCE = new BombInteractionController();

    private BombInteractionController() {}

    public static BombInteractionController getInstance() {
        return INSTANCE;
    }

    /** 尝试开始装弹/拆弹：定位范围内的炸弹并校验其状态，通过则挂起进度；已有进度时再次右键 = 取消 */
    public void tryStartBombProgress(Player player, boolean isPlant){
        UUID uuid = player.getUniqueId();
        //已有进行中的操作：再次使用对应物品取消操作
        if(MatchSessionState.getInstance().getActiveProgresses().containsKey(uuid)){
            cancelActiveProgress(player);
            return;
        }

        //间歇期内新据点尚未开启：炸弹不可交互（与"进行中的读条被打断"共同构成本机制的门闩）
        if(IntermissionController.getInstance().isIntermissionActive()){
            MessageUtil.sendMessageWithPrefix(player,
                    Component.text("新据点尚未开启, 暂时无法安放/拆除炸弹", NamedTextColor.RED));
            return;
        }

        ActiveBomb target = findBombInRange(player);
        if(target == null){
            MessageUtil.sendMessageWithPrefix(player, Component.text("你不在任何炸弹范围内", NamedTextColor.RED));
            return;
        }

        BombState state = target.getState();
        if(isPlant){
            if(state != BombState.UNPLANTED){
                MessageUtil.sendMessageWithPrefix(player, Component.text("该炸弹当前无法装弹", NamedTextColor.RED));
                return;
            }
            startBombProgress(player, target.getId(), true, target.getConfig().getPlantTime());
        }
        else{
            if(state != BombState.PLANTED){
                MessageUtil.sendMessageWithPrefix(player, Component.text("该炸弹尚未安放, 无法拆弹", NamedTextColor.RED));
                return;
            }
            startBombProgress(player, target.getId(), false, target.getConfig().getDefuseTime());
        }
    }

    /** 主动取消进行中的装弹/拆弹（再次右键对应物品触发），移除进度后移动冻结自动解除 */
    public void cancelActiveProgress(Player player){
        BombProgress existing = MatchSessionState.getInstance().getActiveProgresses().remove(player.getUniqueId());
        if(existing == null) return;
        String action = existing.isPlant ? "装弹" : "拆弹";
        MessageUtil.sendMessageWithPrefix(player, Component.text(action + "已取消", NamedTextColor.GRAY));
        SoundUtil.playNoticeFailCombinedSound(player);
    }

    /** 找到玩家当前所处的炸弹（区域包含玩家位置） */
    private ActiveBomb findBombInRange(Player player){
        Vector playerPos = player.getLocation().toVector();
        for(ActiveBomb bomb : SectorManager.getInstance().getActiveBombs()){
            if(bomb.getConfig().getRegion().contains(playerPos)){
                return bomb;
            }
        }
        return null;
    }

    private void startBombProgress(Player player, String bombId, boolean isPlant, int totalTicks){
        UUID uuid = player.getUniqueId();
        MatchSessionState.getInstance().getActiveProgresses()
                .put(uuid, new BombProgress(uuid, bombId, isPlant, totalTicks, player));
        String action = isPlant ? "装弹" : "拆弹";
        MessageUtil.sendMessageWithPrefix(player, Component.text("开始" + action + ", 保持站立并留在范围内", NamedTextColor.YELLOW));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
    }

    public void startBombProgressTickTask(){
        ScheduledTask task = GameContext.getInstance().getPlugin().getServer().getGlobalRegionScheduler()
                .runAtFixedRate(GameContext.getInstance().getPlugin(),
                        scheduledTask -> tickBombProgresses(),
                        1L, 1L);
        MatchSessionState.getInstance().setBombProgressTickTask(task);
    }

    public void stopBombProgressTickTask(){
        ScheduledTask bombProgressTickTask = MatchSessionState.getInstance().getBombProgressTickTask();
        if(bombProgressTickTask != null){
            bombProgressTickTask.cancel();
            MatchSessionState.getInstance().setBombProgressTickTask(null);
        }
    }

    private void tickBombProgresses(){
        MatchSessionState state = MatchSessionState.getInstance();
        if(state.getActiveProgresses().isEmpty()) return;
        for(UUID uuid : new ArrayList<>(state.getActiveProgresses().keySet())){
            BombProgress progress = state.getActiveProgresses().get(uuid);
            if(progress == null) continue;

            Player player = Bukkit.getPlayer(uuid);
            if(player == null || !player.isOnline()){
                state.getActiveProgresses().remove(uuid);
                continue;
            }

            if(!isBombProgressValid(player, progress)){
                cancelBombProgress(uuid, player, progress);
                continue;
            }

            progress.remainingTicks -= 1;
            if(progress.remainingTicks <= 0){
                completeBombProgress(player, progress);
                continue;
            }
            showBombProgress(player, progress);
        }
    }

    /** 进度是否仍有效：在线参战、阵营匹配、仍在同一炸弹范围、炸弹状态未变、未受伤（移动由冻结守卫阻止） */
    private boolean isBombProgressValid(Player player, BombProgress progress){
        if(MatchSessionState.getInstance().isMatchEnded()) return false;
        //间歇期内新据点尚未开启：进行中的读条同样失效（会被 cancelBombProgress 打断并给出反馈）
        if(IntermissionController.getInstance().isIntermissionActive()) return false;

        PlayerStatus status = TeamManager.getInstance().getPlayerStatus(player.getUniqueId());
        if(status == null || status.getState() != PlayerState.IN_BATTLE || !status.getTeam().isCombatant()) return false;
        if(progress.isPlant && status.getTeam() != ShdfTeam.ATTACKER) return false;
        if(!progress.isPlant && status.getTeam() != ShdfTeam.DEFENDER) return false;

        ActiveBomb bomb = SectorManager.getInstance().getActiveBomb(progress.bombId);
        if(bomb == null) return false;
        if(!bomb.getConfig().getRegion().contains(player.getLocation().toVector())) return false;
        if(progress.isPlant){
            if(bomb.getState() != BombState.UNPLANTED) return false;
        }
        else{
            if(bomb.getState() != BombState.PLANTED) return false;
        }

        //玩家受伤（生命值下降）即打断
        if(player.getHealth() < progress.startHealth) return false;
        return true;
    }

    private void showBombProgress(Player player, BombProgress progress){
        double percent = (progress.totalTicks - progress.remainingTicks) / (double) progress.totalTicks;
        String action = progress.isPlant ? "装弹" : "拆弹";
        player.sendActionBar(Component.text(action + "中... " + (int)(percent * 100) + "%", NamedTextColor.GOLD));
    }

    private void cancelBombProgress(UUID uuid, Player player, BombProgress progress){
        MatchSessionState.getInstance().getActiveProgresses().remove(uuid);
        if(player.isOnline()){
            String action = progress.isPlant ? "装弹" : "拆弹";
            MessageUtil.sendMessageWithPrefix(player, Component.text(action + "被打断", NamedTextColor.RED));
            SoundUtil.playNoticeFailCombinedSound(player);
        }
    }

    private void completeBombProgress(Player player, BombProgress progress){
        MatchSessionState.getInstance().getActiveProgresses().remove(progress.uuid);

        boolean success;
        if(progress.isPlant){
            success = SectorManager.getInstance().onBombPlantSuccess(progress.bombId);
        }
        else{
            success = SectorManager.getInstance().onBombDefuseSuccess(progress.bombId);
        }

        if(success){
            //本局战绩：安放/拆除成功计入对应玩家的内存态统计（失败不计）
            PlayerStatus actorStatus = TeamManager.getInstance().getPlayerStatus(player.getUniqueId());
            if(actorStatus != null){
                if(progress.isPlant){
                    actorStatus.addBombPlanted();
                }
                else{
                    actorStatus.addBombDefused();
                }
            }
            if(progress.isPlant){
                MessageUtil.sendMessageWithPrefix(player, Component.text("装弹成功!", NamedTextColor.GREEN));
                MessageUtil.broadcastPrefixedMessage(Component.text(player.getName() + " 安放了炸弹", NamedTextColor.RED));
            }
            else{
                MessageUtil.sendMessageWithPrefix(player, Component.text("拆弹成功!", NamedTextColor.GREEN));
                MessageUtil.broadcastPrefixedMessage(Component.text(player.getName() + " 拆除了炸弹", NamedTextColor.AQUA));
            }
            SoundUtil.playNoticeSuccessCombinedSound(player);
        }
        else{
            MessageUtil.sendMessageWithPrefix(player, Component.text("操作失败, 炸弹状态已改变", NamedTextColor.RED));
        }
    }

    // ==================== 读条期间的移动冻结 ====================

    /** 装弹/拆弹进行期间冻结玩家位置移动（保留视角转动），进度移除后自动恢复移动 */
    private static class BombProgressFreezeListener implements Listener {

        @EventHandler(ignoreCancelled = true)
        public void onPlayerMove(PlayerMoveEvent event){
            UUID uuid = event.getPlayer().getUniqueId();
            if(!MatchSessionState.getInstance().getActiveProgresses().containsKey(uuid)) return;

            Location from = event.getFrom();
            Location to = event.getTo();
            if(to == null) return;

            //仅冻结位置变化（x/y/z），保留 yaw/pitch 视角转动
            if(from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()){
                Location frozen = to.clone();
                frozen.setX(from.getX());
                frozen.setY(from.getY());
                frozen.setZ(from.getZ());
                event.setTo(frozen);
            }
        }
    }

    public void registerFreezeGuard(){
        MatchSessionState state = MatchSessionState.getInstance();
        Listener freezeListener = new BombProgressFreezeListener();
        Bukkit.getPluginManager().registerEvents(freezeListener, GameContext.getInstance().getPlugin());
        state.setFreezeListener(freezeListener);
    }

    public void unregisterFreezeGuard(){
        MatchSessionState state = MatchSessionState.getInstance();
        Listener freezeListener = state.getFreezeListener();
        if(freezeListener != null){
            for(HandlerList handlerList : HandlerList.getHandlerLists()){
                handlerList.unregister(freezeListener);
            }
            state.setFreezeListener(null);
        }
    }

    // ==================== 炸弹区域线框 / 激活粒子 / 爆炸表现 ====================

    /** 炸弹区域线框 DUST 粒子尺寸（立方体棱边） */
    private static final float BOMB_OUTLINE_DUST_SIZE = 1.2f;
    /** 已安放（激活）炸弹的红色云 DUST 粒子尺寸 */
    private static final float ACTIVATED_DUST_SIZE = 2.2f;
    /** 每 1 立方格生成的激活粒子数（数量 = clamp(体积 × 本值, 40, 160)） */
    private static final double ACTIVATED_PER_BLOCK = 0.35d;
    /** 激活粒子数下限/上限（避免小区域看不清、大区域打爆客户端） */
    private static final int ACTIVATED_MIN_COUNT = 40;
    private static final int ACTIVATED_MAX_COUNT = 160;

    /** 爆炸表现的事件订阅（随阶段 onEnter/onExit 成对注册/退订） */
    private GameEventBus.Subscription bombExplodedSubscription;

    /** 爆炸时额外生成的大团黑烟粒子数量 */
    private static final int EXPLOSION_SMOKE_COUNT = 36;

    /**
     * 启动炸弹粒子周期任务（20 tick）并订阅爆炸事件。
     * <p>
     * 周期任务负责：① 为当前据点<b>每一颗</b>炸弹画其区域立方体线框（三态配色）；
     * ② 为已安放炸弹生成覆盖整个区域体积的红色激活云。
     * 爆炸表现是<b>事件驱动</b>的（订阅 {@link BombExplodedEvent}），不在这里新增每 tick 任务。
     * </p>
     */
    public void startBombParticleTask(){
        ScheduledTask task = GameContext.getInstance().getPlugin().getServer().getGlobalRegionScheduler()
                .runAtFixedRate(GameContext.getInstance().getPlugin(),
                        scheduledTask -> spawnActivatedBombParticles(),
                        1L, 20L);
        MatchSessionState.getInstance().setBombParticleTask(task);
        subscribeBombExploded();
    }

    public void stopBombParticleTask(){
        ScheduledTask bombParticleTask = MatchSessionState.getInstance().getBombParticleTask();
        if(bombParticleTask != null){
            bombParticleTask.cancel();
            MatchSessionState.getInstance().setBombParticleTask(null);
        }
        unsubscribeBombExploded();
    }

    /** 订阅炸弹爆炸事件（幂等：已有订阅时不重复注册） */
    private void subscribeBombExploded(){
        if(bombExplodedSubscription != null){
            return;
        }
        bombExplodedSubscription = GameEventBus.subscribe(BombExplodedEvent.class, this::handleBombExplodedVisuals);
    }

    /** 退订炸弹爆炸事件（幂等、空判） */
    private void unsubscribeBombExploded(){
        if(bombExplodedSubscription != null){
            bombExplodedSubscription.unsubscribe();
            bombExplodedSubscription = null;
        }
    }

    /**
     * 每 20 tick 的炸弹区域表现：对当前据点<b>全部</b>炸弹画出区域立方体线框，并放大激活炸弹的红色云。
     * <p>
     * 线框配色与三态口径一致：UNPLANTED=绿、PLANTED=红、EXPLODED=灰（点更稀，表示区域已失效）。
     * 线框必须经 {@link ParticleUtil#drawRegionEdges(CubeRegion, World, Particle, double, Object)} 绘制
     * （带 data 重载，DUST 需要 {@code Particle.DustOptions}）。
     * </p>
     */
    private void spawnActivatedBombParticles(){
        if(MatchSessionState.getInstance().isMatchEnded()) return;
        MapConfig mapConfig = ConfigManager.getInstance().getSelectedMapConfig();
        if(mapConfig == null) return;
        World world = Bukkit.getWorld(mapConfig.getWorld());
        if(world == null) return;

        for(ActiveBomb bomb : SectorManager.getInstance().getActiveBombs()){
            CubeRegion region = bomb.getConfig().getRegion();
            BombState state = bomb.getState();

            //① 区域立方体线框（三态都画；EXPLODED 用更大 step 降低密度，表示"区域已失效"）
            double step = state == BombState.EXPLODED ? 1.0d : 0.5d;
            ParticleUtil.drawRegionEdges(region, world, Particle.DUST, step,
                    new Particle.DustOptions(outlineColor(state), BOMB_OUTLINE_DUST_SIZE));

            //② 红色激活云：只对 PLANTED 炸弹，按区域体积覆盖整个炸弹范围
            if(state == BombState.PLANTED){
                spawnActivatedCloud(world, region);
            }
        }
    }

    /**
     * 已安放炸弹的红色激活云：按区域体积计算数量与偏移，使粒子云覆盖整个炸弹区域。
     * <p>
     * 计算式（file: BombInteractionController.spawnActivatedCloud）：
     * <pre>
     * volume = |size.x| * |size.y| * |size.z|                    // 区域体积（格）
     * count  = clamp((int)(volume * ACTIVATED_PER_BLOCK), 40, 160) // 每格 0.35 粒，夹在 [40,160]
     * offset = (size.x / 2, size.y / 2, size.z / 2)               // 各轴扩散到区域半边长
     * dust   = DustOptions(Color.RED, 2.2f)                       // 原为 1.5f
     * </pre>
     * 对比原实现（固定 30 粒 / 偏移 0.4 / dust 1.5f）：覆盖范围由 0.8 格立方扩到整个炸弹区域，
     * 数量随体积放大。另补少量 {@link Particle#FLAME} 与 {@link Particle#LARGE_SMOKE} 增强"燃烧"观感。
     * </p>
     */
    private void spawnActivatedCloud(World world, CubeRegion region){
        Vector size = region.getSize();
        Vector center = region.getCenter();

        double volume = Math.abs(size.getX()) * Math.abs(size.getY()) * Math.abs(size.getZ());
        int count = (int) Math.round(volume * ACTIVATED_PER_BLOCK);
        count = Math.max(ACTIVATED_MIN_COUNT, Math.min(ACTIVATED_MAX_COUNT, count));

        double offsetX = Math.abs(size.getX()) / 2.0d;
        double offsetY = Math.abs(size.getY()) / 2.0d;
        double offsetZ = Math.abs(size.getZ()) / 2.0d;

        world.spawnParticle(Particle.DUST, center.getX(), center.getY(), center.getZ(),
                count, offsetX, offsetY, offsetZ, 0,
                new Particle.DustOptions(Color.RED, ACTIVATED_DUST_SIZE));
        //"燃烧"感：少量火焰 + 黑烟（同样按区域体积铺开）
        world.spawnParticle(Particle.FLAME, center.getX(), center.getY(), center.getZ(),
                12, offsetX, offsetY, offsetZ, 0);
        world.spawnParticle(Particle.LARGE_SMOKE, center.getX(), center.getY(), center.getZ(),
                8, offsetX, offsetY, offsetZ, 0);
    }

    /** 炸弹区域线框的三态配色：UNPLANTED=绿、PLANTED=红、EXPLODED=灰 */
    private static Color outlineColor(BombState state){
        if(state == BombState.PLANTED){
            return Color.RED;
        }
        if(state == BombState.EXPLODED){
            return Color.GRAY;
        }
        return Color.LIME;
    }

    /**
     * 炸弹爆炸表现（事件驱动，覆盖真实路径：引信归零与 {@code /sg debug bomb set ... EXPLODED} 都经
     * {@link GameEventBus#publish(BombExplodedEvent)} 走到这里）。
     * <p>
     * 位置 = 该炸弹 {@code BombConfig.getRegion().getCenter()}（世界取自当前地图配置）；
     * 粒子 = {@link Particle#EXPLOSION_EMITTER} 1 粒强视觉 + {@link Particle#EXPLOSION} 若干带偏移 +
     * {@link Particle#LARGE_SMOKE} 约 36 粒；音效 = 对<b>所有在线玩家</b>播放
     * {@link Sound#ENTITY_GENERIC_EXPLODE}（音量 1.8），不只附近玩家。
     * 本方法不注册任何调度任务。
     * </p>
     */
    private void handleBombExplodedVisuals(BombExplodedEvent event){
        if(event == null || event.getBomb() == null){
            return;
        }
        MapConfig mapConfig = ConfigManager.getInstance().getSelectedMapConfig();
        if(mapConfig == null){
            return;
        }
        World world = Bukkit.getWorld(mapConfig.getWorld());
        if(world == null){
            return;
        }
        Vector center = event.getBomb().getRegion().getCenter();
        if(center == null){
            return;
        }

        Location centerLocation = center.toLocation(world);
        //强视觉：无偏移的单发爆炸发射器
        world.spawnParticle(Particle.EXPLOSION_EMITTER, centerLocation, 1, 0, 0, 0, 0);
        //带偏移的爆炸 + 黑烟，形成一片爆炸云
        world.spawnParticle(Particle.EXPLOSION, centerLocation, 6, 1.5, 1.0, 1.5, 0);
        world.spawnParticle(Particle.LARGE_SMOKE, centerLocation, EXPLOSION_SMOKE_COUNT, 2.0, 1.5, 2.0, 0.01);

        //全体在线玩家都能听见（不只附近玩家）
        for(Player online : Bukkit.getOnlinePlayers()){
            online.playSound(online.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.8f, 1.0f);
        }
    }

    /** 单个装弹/拆弹进度（本类内部类；进度表由 {@link MatchSessionState} 持有） */
    public static class BombProgress {
        final UUID uuid;
        final String bombId;
        final boolean isPlant;
        final int totalTicks;
        int remainingTicks;
        final double startHealth;

        BombProgress(UUID uuid, String bombId, boolean isPlant, int totalTicks, Player player){
            this.uuid = uuid;
            this.bombId = bombId;
            this.isPlant = isPlant;
            this.totalTicks = totalTicks;
            this.remainingTicks = totalTicks;
            this.startHealth = player.getHealth();
        }
    }
}
