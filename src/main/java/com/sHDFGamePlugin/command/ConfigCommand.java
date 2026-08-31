package com.sHDFGamePlugin.command;

import com.sHDFGamePlugin.infrastructure.config.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * 子指令 config：配置管理。
 * <p>
 * 用法：sg config reload —— 重新加载 config.yml 与 maps.yml
 */
public class ConfigCommand implements SubCommand {

    @Override
    public String getName() {
        return "config";
    }

    @Override
    public String getUsage() {
        return "config <reload>";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("用法: /sg " + getUsage(), NamedTextColor.YELLOW));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                reloadConfig(sender);
                return true;
            }
            default -> {
                sender.sendMessage(Component.text("未知的 config 子指令: " + args[0] + " (可用: reload)", NamedTextColor.RED));
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String lower = args[0].toLowerCase();
            return List.of("reload").stream()
                    .filter(c -> c.startsWith(lower))
                    .toList();
        }
        return List.of();
    }

    private void reloadConfig(CommandSender sender) {
        try {
            ConfigManager.getInstance().reload();
            sender.sendMessage(Component.text("配置已重新加载 (config.yml / maps.yml)", NamedTextColor.GREEN));
        } catch (Exception e) {
            sender.sendMessage(Component.text("配置重载失败: " + e.getMessage(), NamedTextColor.RED));
        }
    }
}
