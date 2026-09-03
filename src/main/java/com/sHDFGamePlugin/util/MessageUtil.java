package com.sHDFGamePlugin.util;

import com.sHDFGamePlugin.SHDFGamePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** 消息工具：统一格式的聊天消息发送 */
public class MessageUtil {

    public static void sendMessageWithPrefix(Player player, Component message) {
        player.sendMessage(Component.text("SHDF>>", NamedTextColor.GRAY, TextDecoration.BOLD).append(Component.empty().decoration(TextDecoration.BOLD, false)
                .color(NamedTextColor.WHITE).append(message)));
    }

    public static void sendPrefixedMessageToAllPlayers(Component message) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            sendMessageWithPrefix(player, message);
        }
    }

    public static void broadcastPrefixedMessage(Component message) {
        SHDFGamePlugin.getInstance().getServer().broadcast(Component.text("SHDF>>", NamedTextColor.GRAY, TextDecoration.BOLD).append(Component.empty().decoration(TextDecoration.BOLD, false)
                .color(NamedTextColor.WHITE).append(message)));
    }
}
