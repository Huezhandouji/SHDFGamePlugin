package com.sHDFGamePlugin.infrastructure.display;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * 表现层炸弹三态（与 {@link com.sHDFGamePlugin.domain.sector.BombState} 一一对应）。
 * <p>
 * 已拍板配色（本枚举是唯一实现点，渲染器一律经 {@link #statusComponent()} 取文案与样式）：
 * <ul>
 *   <li>{@link #UNPLANTED} 绿色</li>
 *   <li>{@link #PLANTED} 红色 + 加粗</li>
 *   <li>{@link #EXPLODED} 灰色 + 删除线</li>
 * </ul>
 * 由 domain 的 {@link com.sHDFGamePlugin.domain.sector.BombState} 映射而来，见
 * {@link BattleBombInfo#of(com.sHDFGamePlugin.domain.sector.ActiveBomb)}。
 */
public enum BattleBombState {

    /** 未安放：绿色 */
    UNPLANTED("未安放", NamedTextColor.GREEN, false, false),

    /** 已安放（引信中）：红色 + 加粗 */
    PLANTED("已安放", NamedTextColor.RED, true, false),

    /** 已爆炸（据点推进完成）：灰色 + 删除线 */
    EXPLODED("已爆炸", NamedTextColor.GRAY, false, true);

    private final String label;
    private final NamedTextColor color;
    private final boolean bold;
    private final boolean strikethrough;

    BattleBombState(String label, NamedTextColor color, boolean bold, boolean strikethrough) {
        this.label = label;
        this.color = color;
        this.bold = bold;
        this.strikethrough = strikethrough;
    }

    /** 中文状态名（已拍板文案） */
    public String label() {
        return label;
    }

    public NamedTextColor color() {
        return color;
    }

    /** 状态文案组件：绿色 / 红色加粗 / 灰色删除线 */
    public Component statusComponent() {
        Component component = Component.text(label, color);
        if(bold){
            component = component.decorate(TextDecoration.BOLD);
        }
        if(strikethrough){
            component = component.decorate(TextDecoration.STRIKETHROUGH);
        }
        return component;
    }

    /** 状态内联短文案（用于据点链：[B1 未安放]），样式与 {@link #statusComponent()} 一致 */
    public Component inlineComponent(String bombDisplayName) {
        Component name = Component.text(bombDisplayName, color);
        if(bold){
            name = name.decorate(TextDecoration.BOLD);
        }
        if(strikethrough){
            name = name.decorate(TextDecoration.STRIKETHROUGH);
        }
        return Component.text("[", NamedTextColor.DARK_GRAY)
                .append(name)
                .append(Component.text(" ", NamedTextColor.DARK_GRAY))
                .append(statusComponent())
                .append(Component.text("]", NamedTextColor.DARK_GRAY));
    }
}
