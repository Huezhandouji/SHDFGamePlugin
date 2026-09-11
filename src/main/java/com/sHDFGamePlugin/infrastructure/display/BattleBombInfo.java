package com.sHDFGamePlugin.infrastructure.display;

import com.sHDFGamePlugin.domain.sector.ActiveBomb;
import com.sHDFGamePlugin.domain.sector.BombState;
import com.sHDFGamePlugin.infrastructure.config.BombConfig;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

/**
 * 表现层的炸弹快照（不可变值对象）：只描述"要显示什么"，不持有 domain 对象。
 * <p>
 * 由调用方（集成层）按当前据点收集后交给渲染器；
 * 数据源是 {@link com.sHDFGamePlugin.domain.sector.SectorManager#getActiveBombs()}。
 */
public final class BattleBombInfo {

    private final String bombId;
    private final Component name;
    private final BattleBombState state;
    private final int fuseRemainingTicks;
    private final int plantTotalTicks;
    private final Location center;

    private BattleBombInfo(String bombId, Component name, BattleBombState state,
                           int fuseRemainingTicks, int plantTotalTicks, Location center) {
        this.bombId = bombId;
        this.name = name;
        this.state = state;
        this.fuseRemainingTicks = fuseRemainingTicks;
        this.plantTotalTicks = plantTotalTicks;
        this.center = center;
    }

    /** 由运行时炸弹与所在世界构建（中心点取自 {@link BombConfig#getRegion()}） */
    public static BattleBombInfo of(ActiveBomb bomb, World world) {
        BombConfig config = bomb.getConfig();
        Location center = null;
        Vector regionCenter = config.getRegion().getCenter();
        if(world != null && regionCenter != null){
            center = regionCenter.toLocation(world);
        }
        return new BattleBombInfo(config.getId(), config.getName(), of(bomb.getState()),
                bomb.getFuseRemaining(), config.getPlantTime(), center);
    }

    /** domain 炸弹状态 → 表现三态 */
    public static BattleBombState of(BombState state) {
        if(state == null){
            return BattleBombState.UNPLANTED;
        }
        return switch (state){
            case UNPLANTED -> BattleBombState.UNPLANTED;
            case PLANTED -> BattleBombState.PLANTED;
            case EXPLODED -> BattleBombState.EXPLODED;
        };
    }

    /** 便捷工厂：供测试 或 非 ActiveBomb 来源构造 */
    public static BattleBombInfo create(String bombId, Component name, BattleBombState state,
                                        int fuseRemainingTicks, int plantTotalTicks, Location center) {
        return new BattleBombInfo(bombId, name, state, fuseRemainingTicks, plantTotalTicks, center);
    }

    public String bombId() {
        return bombId;
    }

    public Component name() {
        return name;
    }

    public BattleBombState state() {
        return state;
    }

    /** 引信剩余 tick；仅 PLANTED 有意义，其余为 0 */
    public int fuseRemainingTicks() {
        return fuseRemainingTicks;
    }

    /** 安放所需总 tick（UNPLANTED 时展示"安放进度"用） */
    public int plantTotalTicks() {
        return plantTotalTicks;
    }

    /** 炸弹操作范围中心点（指南针指向用）；世界缺失时为 null */
    public Location center() {
        return center;
    }

    /** 引信剩余秒数（向上取整） */
    public int fuseRemainingSeconds() {
        return (int) Math.ceil(fuseRemainingTicks / 20.0);
    }

    /** 安放所需秒数（向上取整） */
    public int plantTotalSeconds() {
        return (int) Math.ceil(plantTotalTicks / 20.0);
    }

    /** 显示名（缺失时退回炸弹 id） */
    public Component displayName() {
        if(name == null){
            return Component.text(bombId);
        }
        return name;
    }
}
