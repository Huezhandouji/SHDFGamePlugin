package com.sHDFGamePlugin.phase.playing;

import com.sHDFGamePlugin.domain.team.ShdfTeam;
import com.sHDFGamePlugin.infrastructure.GameEventBus;
import com.sHDFGamePlugin.infrastructure.display.CompassItemFactory;
import com.sHDFGamePlugin.infrastructure.event.InventoryClickGameItemEvent;
import com.sHDFGamePlugin.infrastructure.event.RightClickGameItemEvent;
import com.sHDFGamePlugin.infrastructure.item.GameItem;
import com.sHDFGamePlugin.infrastructure.item.GameItemRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
/**
 * 战斗物品构建与发放：对局物品的注册/注销、物品栈构建、以及发放到快捷栏。
 * <p>
 * 物品清单（GameItem id 采用阶段前缀风格，见 docs/06）：
 * <ul>
 *     <li>slot 7：进攻方 = 装弹装置（{@link #PLANT_ITEM_ID}，TNT 矿车）、防守方 = 拆弹工具
 *     （{@link #DEFUSE_ITEM_ID}，剪刀）；右键经 {@link RightClickGameItemEvent} 由 PlayingPhase 路由；</li>
 *     <li>slot 8：<b>战斗指南针</b>（{@link #COMPASS_ITEM_ID}，材质 COMPASS）——<b>左键</b>在当前据点
 *     各炸弹之间切换指向（并 {@code setCompassTarget} 到选中炸弹区域中心，ActionBar 反馈），
 *     <b>右键</b>打开战斗菜单；物品构建与回调由 {@link com.sHDFGamePlugin.infrastructure.display.CompassItemFactory}
 *     承担（{@link MatchDisplayBridge} 持有实例）；战斗/等待重生等状态都保留（
 *     {@link #clearInventoryKeepBattleMenu(Player)} 跳过 slot 8）；</li>
 *     <li>战斗菜单内的"切换角色"入口（{@link #createSwitchRoleMenuItem(boolean)}）由
 *     {@code IntermissionController} 在间歇期内启用、间歇期结束后变为不可点击的屏障。</li>
 * </ul>
 * 除"切换角色"入口外均 {@code canDrop(false).canMove(false)}；入口自身也登记了
 * "不可丢弃/不可移动"（禁用态无点击回调）。
 * <p>
 * 调用方：{@code PlayingPhase.onEnter/onExit}（注册/注销）、{@link DeploymentController}
 * （等待态与部署/重连时发放）、{@code PlayingPhase} 退出清理（{@link #clearInventoryKeepBattleMenu(Player)} 的
 * slot 8 例外口径）。
 */
public final class PlayingItemFactory {

    //对局 GameItem id（阶段前缀风格）
    public static final String PLANT_ITEM_ID = "gameItem_playingPhase_plantBomb";
    public static final String DEFUSE_ITEM_ID = "gameItem_playingPhase_defuseBomb";
    /**
     * 战斗菜单里的"切换角色"入口（仅间歇期可点击；间歇期结束后该槽位变为无 id 的屏障）
     */
    public static final String BATTLE_MENU_SWITCH_ROLE_ITEM_ID = "gameItem_playingPhase_battleMenuSwitchRole";
    /** "切换角色"入口不可用态（间歇期结束/非间歇期）：无点击回调但同样"不可丢弃/不可移动"，防止被玩家拿走 */
    public static final String BATTLE_MENU_SWITCH_ROLE_DISABLED_ITEM_ID = "gameItem_playingPhase_battleMenuSwitchRoleDisabled";
    /** "切换角色"入口在战斗菜单中的槽位（3 行菜单的正中） */
    public static final int BATTLE_MENU_SWITCH_ROLE_SLOT = 13;
    /**
     * slot 8 战斗指南针的 GameItem id。
     * <p>与 {@link CompassItemFactory#DEFAULT_ITEM_ID} 同源（同一字符串常量）；
     * 门面的右键路由以本常量判断"打开战斗菜单"。</p>
     */
    public static final String COMPASS_ITEM_ID = CompassItemFactory.DEFAULT_ITEM_ID;

    private static final PlayingItemFactory INSTANCE = new PlayingItemFactory();

    private PlayingItemFactory() {}

    public static PlayingItemFactory getInstance() {
        return INSTANCE;
    }

    /**
     * 注册对局物品：装弹（TNT 矿车）、拆弹（剪刀）、以及 slot 8 指南针。
     * <p>指南针由 {@link MatchDisplayBridge}（表现层桥接）构建与注册：它同时承载
     * "左键切换指向 / 右键打开战斗菜单"，因此这里不再单独注册旧的战斗菜单物品。</p>
     */
    public void registerPlayingItems(){
        GameItemRegistry.createAndRegister(PLANT_ITEM_ID, builder ->
                builder.canDrop(false).canMove(false)
                        .rightClickHandler(event ->
                                GameEventBus.publish(new RightClickGameItemEvent(event.getPlayer(), PLANT_ITEM_ID))));
        GameItemRegistry.createAndRegister(DEFUSE_ITEM_ID, builder ->
                builder.canDrop(false).canMove(false)
                        .rightClickHandler(event ->
                                GameEventBus.publish(new RightClickGameItemEvent(event.getPlayer(), DEFUSE_ITEM_ID))));
        //slot 8 指南针：注册在表现层桥接内（左键切换指向 + 右键发布 RightClickGameItemEvent）
        MatchDisplayBridge.getInstance().displayService().compassFactory().register();
        //战斗菜单里的"切换角色"入口：点击（左键/Shift 左键）经库存点击事件交给 IntermissionController 处理
        GameItemRegistry.createAndRegister(BATTLE_MENU_SWITCH_ROLE_ITEM_ID, builder ->
                builder.canDrop(false).canMove(false)
                        .inventoryClickHandler(event ->
                                GameEventBus.publish(new InventoryClickGameItemEvent(
                                        (Player) event.getWhoClicked(), BATTLE_MENU_SWITCH_ROLE_ITEM_ID))));
        //不可用态：只登记"不可丢弃/不可移动"，不挂点击回调——点击被忽略，也无法被玩家从菜单里拿走
        GameItemRegistry.createAndRegister(BATTLE_MENU_SWITCH_ROLE_DISABLED_ITEM_ID, builder ->
                builder.canDrop(false).canMove(false));
    }

    public void unregisterPlayingItems(){
        GameItemRegistry.unregister(PLANT_ITEM_ID);
        GameItemRegistry.unregister(DEFUSE_ITEM_ID);
        //slot 8 指南针（id 与 display 包同一常量）
        GameItemRegistry.unregister(COMPASS_ITEM_ID);
        GameItemRegistry.unregister(BATTLE_MENU_SWITCH_ROLE_ITEM_ID);
        GameItemRegistry.unregister(BATTLE_MENU_SWITCH_ROLE_DISABLED_ITEM_ID);
    }

    /**
     * 构建战斗菜单里的"切换角色"入口物品。
     * <p>
     * {@code enabled=true}（间歇期内）：正常按钮，带 GameItem id，点击打开角色选择子菜单；
     * {@code enabled=false}（间歇期结束/非间歇期）：屏障 + 只登记"不可丢弃/不可移动"、<b>不挂点击回调</b>，
     * 因此既不能被点击触发，也不会被玩家从菜单里拿走。
     */
    public ItemStack createSwitchRoleMenuItem(boolean enabled){
        if(!enabled){
            ItemStack barrier = new ItemStack(Material.BARRIER);
            ItemMeta barrierMeta = barrier.getItemMeta();
            barrierMeta.displayName(Component.text("切换角色（不可用）", NamedTextColor.RED, TextDecoration.BOLD));
            barrierMeta.lore(List.of(Component.text("仅在据点间歇期内可切换角色", NamedTextColor.GRAY)));
            barrierMeta = GameItem.applyIdOnItemMeta(BATTLE_MENU_SWITCH_ROLE_DISABLED_ITEM_ID, barrierMeta);
            barrier.setItemMeta(barrierMeta);
            return barrier;
        }

        ItemStack item = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("切换角色", NamedTextColor.GOLD, TextDecoration.BOLD));
        meta.lore(List.of(
                Component.text("点击选择下次部署使用的角色", NamedTextColor.GRAY),
                Component.text("间歇期结束后失效", NamedTextColor.DARK_GRAY)));
        meta = GameItem.applyIdOnItemMeta(BATTLE_MENU_SWITCH_ROLE_ITEM_ID, meta);
        item.setItemMeta(meta);
        return item;
    }

    /** 给参战玩家发放 slot 7 的阵营交互物品：进攻方=装弹（TNT 矿车），防守方=拆弹（剪刀） */
    public void giveBombInteractionItem(Player player, ShdfTeam team){
        if(team == ShdfTeam.ATTACKER){
            player.getInventory().setItem(7, createPlantItem());
        }
        else if(team == ShdfTeam.DEFENDER){
            player.getInventory().setItem(7, createDefuseItem());
        }
    }

    /**
     * 给玩家发放 slot 8 的战斗指南针（材质 COMPASS），在战斗/等待重生等状态下都保留。
     * <p>物品本体与交互回调由 {@link com.sHDFGamePlugin.infrastructure.display.CompassItemFactory} 构建：
     * 左键在当前据点各炸弹之间切换指向，右键打开战斗菜单。</p>
     */
    public void giveBattleMenuItem(Player player){
        player.getInventory().setItem(8, MatchDisplayBridge.getInstance().createCompassItem());
    }

    /** 清空玩家背包但保留 slot 8 的战斗指南针 */
    public void clearInventoryKeepBattleMenu(Player player){
        for(int i = 0; i < player.getInventory().getSize(); i++){
            if(i != 8){
                player.getInventory().setItem(i, null);
            }
        }
    }

    private ItemStack createPlantItem(){
        ItemStack item = new ItemStack(Material.TNT_MINECART);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("装弹装置", NamedTextColor.RED, TextDecoration.BOLD));
        meta.lore(List.of(Component.text("在炸弹范围内右键开始装弹", NamedTextColor.GRAY)));
        meta = GameItem.applyIdOnItemMeta(PLANT_ITEM_ID, meta);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createDefuseItem(){
        ItemStack item = new ItemStack(Material.SHEARS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("拆弹工具", NamedTextColor.AQUA, TextDecoration.BOLD));
        meta.lore(List.of(Component.text("在炸弹范围内右键开始拆弹", NamedTextColor.GRAY)));
        meta = GameItem.applyIdOnItemMeta(DEFUSE_ITEM_ID, meta);
        item.setItemMeta(meta);
        return item;
    }
}
