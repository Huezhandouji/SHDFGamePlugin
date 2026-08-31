package com.sHDFGamePlugin.command;

import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * 子指令接口：主指令 {@link ShdfGameCommand} 将第一级参数路由到对应的子指令。
 * <p>
 * 实现类：{@link ConfigCommand}（后续可继续扩展）。
 */
public interface SubCommand {

    /** 子指令名称（主指令的第一个参数，不区分大小写） */
    String getName();

    /** 子指令用法说明（不含主指令前缀，用于帮助信息） */
    String getUsage();

    /** 执行子指令；返回 true 表示已处理 */
    boolean execute(CommandSender sender, String[] args);

    /** 子指令的 Tab 补全（args 为剥离主指令后的剩余参数） */
    default List<String> onTabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
