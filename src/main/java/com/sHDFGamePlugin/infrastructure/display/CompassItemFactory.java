package com.sHDFGamePlugin.infrastructure.display;

import com.sHDFGamePlugin.infrastructure.item.GameItem;
import com.sHDFGamePlugin.infrastructure.item.GameItemRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * slot 8 战斗指南针工厂：左键在当前据点各炸弹之间切换指向，右键打开战斗菜单。
 *
 * <h2>接口约定</h2>
 * <ul>
 *   <li>{@link #createItem()} 只造物品，<b>不</b>抢占快捷栏槽位（槽位由集成方决定，约定 slot 8）；</li>
 *   <li>左键 / 右键行为都以参数注入（{@link Builder#leftClickHandler} / {@link Builder#rightClickHandler}），
 *       工厂只负责"切换指向 + 把玩家罗盘目标设到炸弹中心 + 发物品"这三件事，
 *       不知道战斗菜单、事件总线与阶段逻辑；</li>
 *   <li>{@link #register()} / {@link #unregister()} 幂等，由集成方随阶段生命周期调用。</li>
 * </ul>
 *
 * <h2>已核实的 API</h2>
 * {@code Player#setCompassTarget(Location)} / {@code getCompassTarget()} 存在于 paper-api 1.21.11；
 * 物品交互经既有的 {@code InteractionManager} → {@code GameItem#handleLeftClick/handleRightClick}。
 *
 * <h2>已知限制（实测时注意）</h2>
 * 左键派发链上有<b>两条</b>独立的限制，共同构成"左键点实体 / 空挥可能失效"，实测时需一并核对：
 * <ol>
 *     <li><b>点空气不发方块交互包</b>：左键"点击空气"不会发出方块交互包，故仅"对着方块左键"
 *     （{@code LEFT_CLICK_BLOCK}）时才会触发 {@link org.bukkit.event.player.PlayerInteractEvent}；
 *     引擎本身不触发该事件时本工厂无能为力。</li>
 *     <li><b>空挥取不到物品 → 直接 return</b>：既有派发链 {@code InteractionManager.java:86-87}
 *     在有左键动作时会先取 {@code event.getItem()}，为 {@code null} 即 {@code return}——空挥/未持有物品的
 *     左键不会走到 {@code GameItem#handleLeftClick}，因此本工厂的切换也不会发生。</li>
 * </ol>
 * 两条叠加的后果：<b>左键点实体（原版攻击，不发 {@code PlayerInteractEvent}）与空挥都可能不触发切换</b>。
 * 集成方若需要"左键点实体也能切换"的语义，必须自行补充 {@code PlayerInteractEntityEvent}
 * （不在本任务范围，需另开任务）。
 */
public final class CompassItemFactory {

    /** 默认 GameItem id（沿用工程"阶段前缀"命名约定） */
    public static final String DEFAULT_ITEM_ID = "gameItem_playingPhase_battleCompass";

    private final String itemId;
    private final Consumer<PlayerInteractEvent> rightClickHandler;
    private final BiConsumer<Player, BattleBombInfo> leftClickListener;

    //玩家 -> 当前指向的炸弹 id
    private final Map<UUID, String> targetedBombIds = new HashMap<>();
    //玩家 -> 最近一次收到的炸弹列表（左键切换时按 id 重新定位，避免持有过期对象）
    private final Map<UUID, List<BattleBombInfo>> knownBombs = new HashMap<>();

    private CompassItemFactory(String itemId, Consumer<PlayerInteractEvent> rightClickHandler,
                               BiConsumer<Player, BattleBombInfo> leftClickListener) {
        this.itemId = itemId;
        this.rightClickHandler = rightClickHandler;
        this.leftClickListener = leftClickListener;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String itemId() {
        return itemId;
    }

    // ==================== 物品 ====================

    /** 构建 slot 8 指南针物品（带 GameItem id，材质 COMPASS） */
    public ItemStack createItem() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("战斗指南针", NamedTextColor.AQUA, TextDecoration.BOLD));
        meta.lore(List.of(
                Component.text("左键: 切换指向当前据点的炸弹", NamedTextColor.GRAY),
                Component.text("右键: 打开战斗菜单", NamedTextColor.GRAY)
        ));
        meta = GameItem.applyIdOnItemMeta(itemId, meta);
        item.setItemMeta(meta);
        return item;
    }

    /** 注册 GameItem（含左键/右键回调）；幂等 */
    public void register() {
        GameItemRegistry.createAndRegister(itemId, builder -> builder
                .canDrop(false)
                .canMove(false)
                .rightClickHandler(event -> handleRightClick(event))
                .leftClickHandler(event -> handleLeftClick(event)));
    }

    /** 注销 GameItem 并清空指向记录；幂等 */
    public void unregister() {
        GameItemRegistry.unregister(itemId);
        clearAll();
    }

    // ==================== 交互 ====================

    /**
     * 左键入口：在当前据点的炸弹列表里循环切换指向。
     * <p>列表为空时也回调监听器（{@code bomb == null}），便于集成方给出"当前据点没有可指向的炸弹"提示。</p>
     */
    public void handleLeftClick(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if(player == null || !player.isOnline()){
            return;
        }
        List<BattleBombInfo> bombs = knownBombs.getOrDefault(player.getUniqueId(), List.of());
        BattleBombInfo target = cycleTarget(player, bombs);
        if(target != null && target.center() != null){
            //把玩家罗盘的目标点设到炸弹中心，客户端指针随之转动
            player.setCompassTarget(target.center());
        }
        if(leftClickListener != null){
            leftClickListener.accept(player, target);
        }
    }

    /** 右键入口：交给注入的回调（通常是发布既有的 RightClickGameItemEvent 打开战斗菜单） */
    public void handleRightClick(PlayerInteractEvent event) {
        if(rightClickHandler != null){
            rightClickHandler.accept(event);
        }
    }

    // ==================== 指向状态 ====================

    /** 注入当前据点的炸弹列表（同一帧可反复调用；列表保持配置顺序） */
    public void updateBombs(Player player, List<BattleBombInfo> bombs) {
        if(player == null){
            return;
        }
        List<BattleBombInfo> copy = bombs == null ? List.of() : List.copyOf(bombs);
        knownBombs.put(player.getUniqueId(), copy);
        if(copy.isEmpty()){
            targetedBombIds.remove(player.getUniqueId());
        }
    }

    /** 当前指向的炸弹 id；未指向返回 null */
    public String bombTargetId(Player player) {
        if(player == null){
            return null;
        }
        return bombTargetId(player.getUniqueId());
    }

    public String bombTargetId(UUID uuid) {
        if(uuid == null){
            return null;
        }
        return targetedBombIds.get(uuid);
    }

    /** 按 id 在当前炸弹列表里解析指向对象；找不到返回 null */
    public BattleBombInfo bombTarget(Player player) {
        if(player == null){
            return null;
        }
        String bombId = targetedBombIds.get(player.getUniqueId());
        if(bombId == null){
            return null;
        }
        for(BattleBombInfo bomb : knownBombs.getOrDefault(player.getUniqueId(), List.of())){
            if(bomb.bombId().equals(bombId)){
                return bomb;
            }
        }
        return null;
    }

    /** 清除某玩家的指向与缓存（玩家退出 / 死亡 / 对局结束时调用） */
    public void clearTarget(Player player) {
        if(player == null){
            return;
        }
        clearTarget(player.getUniqueId());
    }

    public void clearTarget(UUID uuid) {
        if(uuid == null){
            return;
        }
        targetedBombIds.remove(uuid);
        knownBombs.remove(uuid);
    }

    /** 清空全部指向记录（阶段 onExit 调用） */
    public void clearAll() {
        targetedBombIds.clear();
        knownBombs.clear();
    }

    // ==================== 内部 ====================

    /**
     * 在当前列表中循环取下一个"待处理"的炸弹（跳过已爆炸）。
     * <p>找不到任何待处理炸弹时返回 null 并清空指向。</p>
     */
    private BattleBombInfo cycleTarget(Player player, List<BattleBombInfo> bombs) {
        UUID uuid = player.getUniqueId();
        List<BattleBombInfo> candidates = new ArrayList<>();
        for(BattleBombInfo bomb : bombs){
            if(bomb.state() != BattleBombState.EXPLODED){
                candidates.add(bomb);
            }
        }
        if(candidates.isEmpty()){
            targetedBombIds.remove(uuid);
            return null;
        }

        String current = targetedBombIds.get(uuid);
        int currentIndex = -1;
        for(int i = 0; i < candidates.size(); i++){
            if(candidates.get(i).bombId().equals(current)){
                currentIndex = i;
                break;
            }
        }
        BattleBombInfo next = candidates.get((currentIndex + 1) % candidates.size());
        targetedBombIds.put(uuid, next.bombId());
        return next;
    }

    public static final class Builder {

        private String itemId = DEFAULT_ITEM_ID;
        private Consumer<PlayerInteractEvent> rightClickHandler;
        private BiConsumer<Player, BattleBombInfo> leftClickListener;

        private Builder() {}

        /** GameItem id（默认 {@link #DEFAULT_ITEM_ID}） */
        public Builder itemId(String itemId) {
            this.itemId = itemId;
            return this;
        }

        /** 右键回调（必填；集成方通常发布既有的 RightClickGameItemEvent） */
        public Builder rightClickHandler(Consumer<PlayerInteractEvent> rightClickHandler) {
            this.rightClickHandler = rightClickHandler;
            return this;
        }

        /** 左键切换指向后的监听（可选；用于播报与音效，{@code bomb} 可能为 null） */
        public Builder leftClickListener(BiConsumer<Player, BattleBombInfo> leftClickListener) {
            this.leftClickListener = leftClickListener;
            return this;
        }

        public CompassItemFactory build() {
            if(itemId == null || itemId.isBlank()){
                throw new IllegalStateException("itemId must not be blank");
            }
            if(rightClickHandler == null){
                throw new IllegalStateException("rightClickHandler is required");
            }
            return new CompassItemFactory(itemId, rightClickHandler, leftClickListener);
        }
    }

    /** 供集成方复用的罗盘目标点（未持有 Location 时返回 null） */
    public static Location targetLocation(BattleBombInfo bomb) {
        if(bomb == null){
            return null;
        }
        return bomb.center();
    }
}
