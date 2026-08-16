package com.sHDFGamePlugin.command;

import com.sHDFGamePlugin.SHDFGamePlugin;
import com.sHDFGamePlugin.infrastructure.gui.ChestGui;
import com.sHDFGamePlugin.infrastructure.item.GameItem;
import com.sHDFGamePlugin.infrastructure.item.InteractionManager;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.security.cert.PKIXRevocationChecker;
import java.util.ArrayList;
import java.util.List;

public class ACommand implements CommandExecutor, Listener {
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {

        if(!(sender instanceof Player p)) return true;

        ChestGui.Builder guiBuilder = ChestGui.Builder.create()
                .title(Component.text("一个gui"));

        GameItem item1 = new GameItem.Builder("btn1", Material.COMMAND_BLOCK)
                .displayName(Component.text("button1"))
                .addLineOfLore(Component.text("lore1"))
                .inventoryClickHandler(event -> {
                    if(!(event.getWhoClicked() instanceof Player player)) return;
                    player.sendMessage(Component.text("你点了btn1"));
                })
                .build();

        InteractionManager.getInstance().registerGameItem("btn1", item1);

        guiBuilder.setSlot(0, item1);

        ChestGui gui = guiBuilder.build();

        gui.open(p);

        return true;
    }




}
