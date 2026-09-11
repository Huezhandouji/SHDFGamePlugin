package com.sHDFGamePlugin.infrastructure.gui;

import com.sHDFGamePlugin.domain.team.PlayerStatus;
import com.sHDFGamePlugin.domain.team.ShdfTeam;
import com.sHDFGamePlugin.domain.team.TeamManager;
import com.sHDFGamePlugin.infrastructure.RoleBridge;
import com.sHDFGamePlugin.infrastructure.item.GameItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 角色选择 GUI 组件（可复用）：把"角色按钮构建 + 占用名单渲染 + 清除角色按钮 + 菜单布局"从阶段里抽出来。
 * <p>
 * 用途：
 * - 角色选择阶段（{@code RoleSelectingPhase}）打开选角菜单、刷新已打开的菜单；
 * - 对局阶段的切角色入口（PLAYING 间歇期等）复用同一套按钮渲染，只需换一份
 *   {@code roleButtonIdPrefix} / {@code clearRoleButtonId} 并传入 {@code clickable=false}，
 *   即可得到"只读展示"版本（间歇期结束后切角色按钮变为不可用，见任务 t21）。
 * <p>
 * 设计约定：
 * - <b>只做展示</b>：组件不写任何业务状态（不写 {@code PlayerStatus.selectedRoleId}、不注册 GameItem），
 *   也不持有任何跨调用缓存；点击结果由调用方通过 {@code gameItemId → roleId} 反查后自行处理
 *   （选角阶段记录选择 + 刷新菜单，间歇期可能是拒绝切角色等）；
 * - <b>按钮 GameItem id 由组件统一生成</b>（{@code roleButtonIdPrefix + side + "_" + roleId}，side ∈ attacker/defender），
 *   调用方按 {@link RoleButtonEntry#gameItemId()} 注册 GameItem 并建立自己的反查表，
 *   保证"显示用的 id"与"点击回调的 id"永远同源；
 * - 布局与文本与抽取前完全一致：行数 = 角色所需行数 + 底部保留 1 行（上限 6 行 = MC 箱子菜单上限），
 *   清除按钮位于最下面一行中间（第 5 列），有人选择的角色按钮带附魔光效。
 */
public final class RoleSelectionGui {

    /** 按钮 GameItem id 前缀（阶段前缀 + "role_"） */
    private final String roleButtonIdPrefix;
    /** 清除角色按钮的 GameItem id；为 null 表示不显示清除按钮 */
    private final String clearRoleButtonId;
    /** 是否允许点击选择角色：false = 只读展示（用屏障替换按钮图标，避免"看起来能点却点不了"） */
    private final boolean clickable;

    private RoleSelectionGui(String roleButtonIdPrefix, String clearRoleButtonId, boolean clickable) {
        this.roleButtonIdPrefix = roleButtonIdPrefix;
        this.clearRoleButtonId = clearRoleButtonId;
        this.clickable = clickable;
    }

    /**
     * 创建组件实例（每次进入阶段创建一个新的，随阶段生命周期一起废弃，组件不持有任何跨阶段状态）。
     *
     * @param roleButtonIdPrefix 角色按钮 GameItem id 前缀，完整 id 为 prefix + side + "_" + roleId
     * @param clearRoleButtonId  清除角色按钮的 GameItem id；传 null 则不显示清除按钮
     * @param clickable          true = 正常选角菜单；false = 只读展示（角色按钮显示为不可用的屏障图标）
     */
    public static RoleSelectionGui create(String roleButtonIdPrefix, String clearRoleButtonId, boolean clickable) {
        return new RoleSelectionGui(roleButtonIdPrefix, clearRoleButtonId, clickable);
    }

    /** 是否可点击选择角色 */
    public boolean isClickable(){
        return clickable;
    }

    // ==================== 按钮构建 ====================

    /**
     * 构建某阵营的全部角色按钮（顺序与角色池一致，只含 RoleBridge 判定为有效的角色）。
     * <p>
     * 每个条目既提供可直接放入槽位的 {@link ItemStack}，也提供其 GameItem id，
     * 供调用方注册 GameItem 并建立"按钮 id → roleId"反查表。
     */
    public List<RoleButtonEntry> buildRoleButtons(ShdfTeam team, List<String> rolePool) {
        List<RoleButtonEntry> entries = new ArrayList<>();
        if(rolePool == null) return entries;
        RoleBridge roleBridge = RoleBridge.getInstance();
        for(String roleId : rolePool){
            //只展示有效（已实现）角色，与注册逻辑一致
            if(!roleBridge.isValidRoleId(roleId)) continue;
            String gameItemId = generateRoleButtonGameItemId(team, roleId);
            entries.add(new RoleButtonEntry(roleId, gameItemId, buildRoleButton(roleId, gameItemId, team)));
        }
        return entries;
    }

    /**
     * 生成角色按钮的 GameItem id：{@code roleButtonIdPrefix + side + "_" + roleId}（side ∈ attacker/defender）。
     * 调用方注册 GameItem 时必须用它，保证与按钮物品上写入的 id 完全一致。
     */
    public String generateRoleButtonGameItemId(ShdfTeam team, String roleId){
        return roleButtonIdPrefix + sideName(team) + "_" + roleId;
    }

    /** 构建单个角色按钮物品：名称=角色 DisplayName（缺失回退 roleId），Lore=已选该角色的玩家名单，有人选择时加附魔光效 */
    private ItemStack buildRoleButton(String roleId, String gameItemId, ShdfTeam team){
        RoleBridge roleBridge = RoleBridge.getInstance();

        //RoleAPI 对不存在的角色返回 Material.AIR 而非 null，AIR 同样视为缺失
        Material icon = roleBridge.getRoleIcon(roleId);
        if(icon == null || icon == Material.AIR){
            icon = Material.PAPER;
        }

        ItemStack button = new ItemStack(icon);
        ItemMeta meta = button.getItemMeta();

        //名称：角色的 DisplayName（缺失回退 roleId）
        Component displayName = roleBridge.getRoleDisplayName(roleId);
        if(displayName == null || displayName.equals(Component.empty())){
            displayName = Component.text(roleId, NamedTextColor.WHITE);
        }
        meta.displayName(displayName);

        //Lore：已选择该角色的玩家名单；有人选择时附加魔光效
        List<String> selectors = getRoleSelectors(team, roleId);
        List<Component> lore = new ArrayList<>();
        if(selectors.isEmpty()){
            lore.add(Component.text("尚未有人选择", NamedTextColor.GRAY));
        }
        else{
            for(String name : selectors){
                lore.add(Component.text("- " + name, NamedTextColor.GREEN));
            }
            //附魔光效（隐藏附魔描述）
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        meta.lore(lore);

        meta = GameItem.applyIdOnItemMeta(gameItemId, meta);
        button.setItemMeta(meta);
        return button;
    }

    /**
     * 构建放入菜单槽位的角色按钮物品：
     * - {@code clickable=true}：正常按钮（带 GameItem id，点击可被识别）；
     * - {@code clickable=false}：只读展示——保留显示名与占用名单，但图标换成屏障，
     *   并去掉 GameItem id，让点击被 InteractionManager 忽略（不会误触发已失效的选角）。
     */
    public ItemStack buildMenuRoleButton(RoleButtonEntry entry, boolean clickable){
        if(entry == null) return null;
        if(clickable) return entry.item();

        ItemStack display = new ItemStack(Material.BARRIER);
        ItemMeta sourceMeta = entry.item().getItemMeta();
        ItemMeta meta = display.getItemMeta();
        if(sourceMeta != null){
            meta.displayName(sourceMeta.displayName());
            meta.lore(sourceMeta.lore());
        }
        display.setItemMeta(meta);
        return display;
    }

    /** 构建"清除已选角色"按钮；未配置 clearRoleButtonId 或只读展示时返回 null */
    public ItemStack buildClearRoleButton(){
        if(clearRoleButtonId == null || !clickable) return null;
        ItemStack button = new ItemStack(Material.BARRIER);
        ItemMeta meta = button.getItemMeta();
        meta.displayName(Component.text("清除已选角色", NamedTextColor.RED, TextDecoration.BOLD));
        meta.lore(List.of(Component.text("点击清除自己当前选择的角色", NamedTextColor.GRAY)));
        meta = GameItem.applyIdOnItemMeta(clearRoleButtonId, meta);
        button.setItemMeta(meta);
        return button;
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

    // ==================== 布局与打开 ====================

    /**
     * 计算整个菜单的槽位内容：角色按钮从第 0 槽起顺序排列，清除按钮位于最下面一行中间。
     * <p>
     * 布局与抽取前一致：行数 = 角色所需行数 + 1（底部保留一行），上限 6 行（MC 箱子菜单上限）。
     *
     * @return 行数、清除按钮槽位、按槽位顺序排列的角色按钮物品
     */
    public Content buildContent(ShdfTeam team, List<String> rolePool){
        List<RoleButtonEntry> entries = buildRoleButtons(team, rolePool);
        int roleRows = Math.max(1, (entries.size() + 8) / 9);
        int rows = Math.min(6, roleRows + 1);
        //清除按钮位于最下面一行中间，不与角色按钮冲突
        int clearSlot = (rows - 1) * 9 + 4;

        List<ItemStack> roleItems = new ArrayList<>();
        for(RoleButtonEntry entry : entries){
            roleItems.add(buildMenuRoleButton(entry, clickable));
        }
        return new Content(rows, clearSlot, roleItems);
    }

    /**
     * 打开角色选择菜单（每次调用都按当前角色池与占用名单重建容器）。
     * <p>
     * 与抽取前 {@code RoleSelectingPhase#openRoleSelectionGui} 行为一致：标题为
     * "选择角色 - {阵营显示名}"，角色按钮从第 0 槽起，清除按钮在最下面一行中间。
     *
     * @param sideDisplayName 标题里的阵营显示名（如"进攻方"）
     * @return 已打开的 GUI，供调用方在需要时刷新
     */
    public ChestGui openMenu(Player player, ShdfTeam team, List<String> rolePool, String sideDisplayName){
        Content content = buildContent(team, rolePool);
        ChestGui.Builder builder = ChestGui.Builder.create()
                .title(Component.text("选择角色 - " + sideDisplayName,
                        NamedTextColor.YELLOW).decorate(TextDecoration.BOLD))
                .rows(content.rows());
        int slot = 0;
        for(ItemStack item : content.roleItems()){
            builder.setSlot(slot++, item);
        }
        if(clearRoleButtonId != null){
            ItemStack clearButton = buildClearRoleButton();
            if(clearButton != null){
                builder.setSlot(content.clearSlot(), clearButton);
            }
        }
        ChestGui gui = builder.build();
        gui.open(player);
        return gui;
    }

    /**
     * 把当前内容写入已打开的 GUI（刷新占用名单与光效）。
     * <p>
     * 与抽取前 {@code refreshOpenRoleGuis} 一致：只重建有效角色按钮，槽位数量不变，
     * 不调用 {@code ChestGui#refresh} 补空槽。
     */
    public void applyContent(ChestGui target, ShdfTeam team, List<String> rolePool){
        if(target == null) return;
        Content content = buildContent(team, rolePool);
        int slot = 0;
        for(ItemStack item : content.roleItems()){
            target.setSlot(slot++, item);
        }
        if(clearRoleButtonId != null){
            ItemStack clearButton = buildClearRoleButton();
            if(clearButton != null){
                target.setSlot(content.clearSlot(), clearButton);
            }
        }
    }

    // ==================== 点击解析 ====================

    /** 物品上的 GameItem id；空物品返回 null */
    public String getGameItemIdOf(ItemStack item){
        if(item == null || item.getType() == Material.AIR) return null;
        return GameItem.getGameItemId(item);
    }

    /** 判断物品是否为"清除角色"按钮 */
    public boolean isClearRoleButton(ItemStack item){
        if(clearRoleButtonId == null) return false;
        return clearRoleButtonId.equals(getGameItemIdOf(item));
    }

    private static String sideName(ShdfTeam team){
        if(team == ShdfTeam.ATTACKER) return "attacker";
        return "defender";
    }

    /** 单个角色按钮条目：roleId + 其 GameItem id + 已构建好的按钮物品 */
    public record RoleButtonEntry(String roleId, String gameItemId, ItemStack item){}

    /** 菜单内容：总行数、清除按钮槽位、按槽位顺序排列的角色按钮物品 */
    public record Content(int rows, int clearSlot, List<ItemStack> roleItems){}
}
