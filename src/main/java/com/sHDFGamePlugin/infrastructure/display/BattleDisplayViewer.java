package com.sHDFGamePlugin.infrastructure.display;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.entity.Player;

/**
 * BossBar 宿主回调：把渲染好的 {@link BossBar} 实例挂到玩家身上 / 从玩家身上摘下。
 * <p>
 * 单独抽出接口的目的：
 * <ul>
 *   <li>渲染器本身可独立编译、可独立测试（不强制依赖 Bukkit Player 在线状态）；</li>
 *   <li>集成层可以自由决定是"每玩家一份 BossBar 实例"（默认实现见
 *       {@link BattleSectorBarRenderer#defaultViewer()}）还是别的挂载方式。</li>
 * </ul>
 */
@FunctionalInterface
public interface BattleDisplayViewer {

    /**
     * @param player 目标玩家
     * @param bar    已渲染的 BossBar
     * @param show   true = showBossBar（挂载），false = hideBossBar（摘除）
     */
    void apply(Player player, BossBar bar, boolean show);
}
