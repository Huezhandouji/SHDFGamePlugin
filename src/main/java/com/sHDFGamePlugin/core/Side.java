package com.sHDFGamePlugin.core;


import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public enum Side {

    SHADOW,
    HUNTER,
    SPECTATOR;

    public Component getDisplayName(){
        switch (this){
            case SHADOW -> {
                return Component.text("[SHADOW]").color(NamedTextColor.LIGHT_PURPLE);
            }
            case HUNTER -> {
                return Component.text("[HUNTER]").color(NamedTextColor.YELLOW);
            }
            case SPECTATOR -> {
                return Component.text("[SPECTATOR]").color(NamedTextColor.GRAY);
            }
            default -> {
                return Component.empty();
            }
        }
    }


    public boolean isCombatant() {
        return this == SHADOW || this == HUNTER;
    }

    public Side getOpponent() {
        return switch (this) {
            case SHADOW -> HUNTER;
            case HUNTER -> SHADOW;
            default -> null;
        };
    }
}
