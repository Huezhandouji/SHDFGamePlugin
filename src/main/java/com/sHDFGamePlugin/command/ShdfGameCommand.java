package com.sHDFGamePlugin.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 主指令 shdfgame（别名 sg）。
 * <p>
 * 将第一级参数路由到对应的 {@link SubCommand}，同时实现 Tab 补全。
 */
public class ShdfGameCommand implements CommandExecutor, TabCompleter {

    private final Map<String, SubCommand> subCommands = new TreeMap<>();

    public ShdfGameCommand() {
        registerSubCommand(new ConfigCommand());
    }

    private void registerSubCommand(SubCommand subCommand) {
        subCommands.put(subCommand.getName().toLowerCase(), subCommand);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("用法: /" + label + " <子指令>  可用子指令: " + String.join(", ", subCommands.keySet()));
            return true;
        }

        SubCommand subCommand = subCommands.get(args[0].toLowerCase());
        if (subCommand == null) {
            sender.sendMessage("未知子指令: " + args[0] + " (可用: " + String.join(", ", subCommands.keySet()) + ")");
            return true;
        }

        //剥离第一个参数，剩余参数传给子指令
        String[] subArgs = new String[args.length - 1];
        System.arraycopy(args, 1, subArgs, 0, subArgs.length);
        return subCommand.execute(sender, subArgs);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(subCommands.keySet(), args[0]);
        }

        SubCommand subCommand = subCommands.get(args[0].toLowerCase());
        if (subCommand == null) {
            return List.of();
        }

        String[] subArgs = new String[args.length - 1];
        System.arraycopy(args, 1, subArgs, 0, subArgs.length);
        return subCommand.onTabComplete(sender, subArgs);
    }

    private List<String> filter(Iterable<String> candidates, String prefix) {
        List<String> result = new ArrayList<>();
        String lowerPrefix = prefix.toLowerCase();
        for (String candidate : candidates) {
            if (candidate.toLowerCase().startsWith(lowerPrefix)) {
                result.add(candidate);
            }
        }
        return result;
    }
}
