package com.sHDFGamePlugin.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

public class MessageUtil {

    public static void sendMessageWithPostfix(Player player, Component message) {
        player.sendMessage(Component.text("SHDF>>", NamedTextColor.GRAY, TextDecoration.BOLD).append(Component.empty().decoration(TextDecoration.BOLD, false)
                .color(NamedTextColor.WHITE).append(message)));
    }
}
