package com.danz.fabric.ipwl.commands;

import com.danz.fabric.ipwl.IPWLMod;
import com.danz.fabric.ipwl.config.IPWLConfig;
import com.danz.fabric.ipwl.manager.WhitelistManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Set;

public class WhitelistCommands {
    
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        // Main whitelist command
        dispatcher.register(CommandManager.literal("whitelist")
            .requires(IPWLMod::hasPermission)
            .then(CommandManager.literal("add")
                .then(CommandManager.argument("player", StringArgumentType.word())
                    .executes(context -> addPlayer(context, null))
                    .then(CommandManager.argument("ip", StringArgumentType.greedyString())
                        .executes(context -> addPlayer(context, 
                            StringArgumentType.getString(context, "ip"))))))
            .then(CommandManager.literal("remove")
                .then(CommandManager.argument("player", StringArgumentType.word())
                    .executes(WhitelistCommands::removePlayer)))
            .then(CommandManager.literal("list")
                .executes(WhitelistCommands::listWhitelist))
            .then(CommandManager.literal("reload")
                .executes(WhitelistCommands::reloadWhitelist))
            .then(CommandManager.literal("addip")
                .then(CommandManager.argument("player", StringArgumentType.word())
                    .then(CommandManager.argument("ip", StringArgumentType.greedyString())
                        .executes(WhitelistCommands::addIpToPlayer))))
            .then(CommandManager.literal("removeip")
                .then(CommandManager.argument("player", StringArgumentType.word())
                    .then(CommandManager.argument("ip", StringArgumentType.greedyString())
                        .executes(WhitelistCommands::removeIpFromPlayer)))));
        
        // Shortcut /ipwl command
        dispatcher.register(CommandManager.literal("ipwl")
            .requires(IPWLMod::hasPermission)
            .then(CommandManager.literal("help")
                .executes(WhitelistCommands::sendHelp))
            .then(CommandManager.literal("add")
                .then(CommandManager.argument("player", StringArgumentType.word())
                    .executes(context -> addPlayer(context, null))
                    .then(CommandManager.argument("ip", StringArgumentType.greedyString())
                        .executes(context -> addPlayer(context, 
                            StringArgumentType.getString(context, "ip"))))))
            .then(CommandManager.literal("remove")
                .then(CommandManager.argument("player", StringArgumentType.word())
                    .executes(WhitelistCommands::removePlayer)))
            .then(CommandManager.literal("list")
                .executes(WhitelistCommands::listWhitelist))
            .then(CommandManager.literal("reload")
                .executes(WhitelistCommands::reloadWhitelist))
            .then(CommandManager.literal("addip")
                .then(CommandManager.argument("player", StringArgumentType.word())
                    .then(CommandManager.argument("ip", StringArgumentType.greedyString())
                        .executes(WhitelistCommands::addIpToPlayer))))
            .then(CommandManager.literal("removeip")
                .then(CommandManager.argument("player", StringArgumentType.word())
                    .then(CommandManager.argument("ip", StringArgumentType.greedyString())
                        .executes(WhitelistCommands::removeIpFromPlayer))))
            
            // Log Toggle Command
            .then(CommandManager.literal("logs")
                .requires(source -> source.hasPermissionLevel(3))
                .then(CommandManager.literal("verbose")
                    .executes(context -> setLogLevel(context, true)))
                .then(CommandManager.literal("silent")
                    .executes(context -> setLogLevel(context, false))))
            
            // Admin Management Command (Requires OP Level 3)
            .then(CommandManager.literal("admin")
                .requires(source -> source.hasPermissionLevel(3))
                .then(CommandManager.literal("add")
                    .then(CommandManager.argument("player", StringArgumentType.word())
                        .executes(WhitelistCommands::addAdmin)))
                .then(CommandManager.literal("remove")
                    .then(CommandManager.argument("player", StringArgumentType.word())
                        .executes(WhitelistCommands::removeAdmin)))
                .then(CommandManager.literal("list")
                    .executes(WhitelistCommands::listAdmins)))
        );
    }

    private static int setLogLevel(CommandContext<ServerCommandSource> context, boolean verbose) {
        IPWLConfig config = IPWLMod.getConfig();
        config.setVerboseLogging(verbose);
        String state = verbose ? "§eVERBOSE" : "§aSILENT";
        IPWLMod.sendFeedback(context.getSource(), 
            String.format("§6[IPWL] Logging set to %s. %s", state, 
                verbose ? "All details will be shown." : "Only key events will be shown."));
        return 1;
    }

    private static int sendHelp(CommandContext<ServerCommandSource> context) {
        String helpMessage = 
            "§6=== IPWL Commands Help ===\n" +
            "§e/ipwl add <player> [ip] §7- Whitelist a player\n" +
            "§e/ipwl remove <player> §7- Remove a player\n" +
            "§e/ipwl list §7- List whitelisted players\n" +
            "§e/ipwl reload §7- Reload configuration\n" +
            "§e/ipwl addip/removeip §7- Manage specific IPs\n" +
            "§c/ipwl admin add/remove <player> §7- Manage Mod Admins\n" +
            "§c/ipwl logs <verbose|silent> §7- Toggle log detail\n" +
            "§b/security stats §7- View security statistics\n" +
            "§b/lockdown <on|off|status> §7- Control lockdown\n" +
            "§b/seen <player> §7- Check player activity";
        
        IPWLMod.sendFeedback(context.getSource(), helpMessage);
        return 1;
    }

    private static int addAdmin(CommandContext<ServerCommandSource> context) {
        String playerName = StringArgumentType.getString(context, "player");
        IPWLConfig config = IPWLMod.getConfig();
        
        if (config.isAdmin(playerName)) {
            IPWLMod.sendFeedback(context.getSource(), String.format("§e%s is already an IPWL Admin", playerName));
            return 0;
        }
        
        config.addAdmin(playerName);
        IPWLMod.sendFeedback(context.getSource(), String.format("§a%s added to IPWL Admins", playerName));
        
        // Refresh commands for the player immediately if they are online
        ServerPlayerEntity player = context.getSource().getServer().getPlayerManager().getPlayer(playerName);
        if (player != null) {
            context.getSource().getServer().getPlayerManager().sendCommandTree(player);
            player.sendMessage(Text.literal("§6[IPWL] You have been granted Admin permissions."), false);
        }
        
        return 1;
    }
    
    private static int removeAdmin(CommandContext<ServerCommandSource> context) {
        String playerName = StringArgumentType.getString(context, "player");
        IPWLConfig config = IPWLMod.getConfig();
        
        if (!config.isAdmin(playerName)) {
            IPWLMod.sendFeedback(context.getSource(), String.format("§c%s is not an IPWL Admin", playerName));
            return 0;
        }
        
        config.removeAdmin(playerName);
        IPWLMod.sendFeedback(context.getSource(), String.format("§a%s removed from IPWL Admins", playerName));
        
        // Refresh commands for the player immediately to revoke access
        ServerPlayerEntity player = context.getSource().getServer().getPlayerManager().getPlayer(playerName);
        if (player != null) {
            context.getSource().getServer().getPlayerManager().sendCommandTree(player);
            player.sendMessage(Text.literal("§c[IPWL] Your Admin permissions have been revoked."), false);
        }
        
        return 1;
    }
    
    private static int listAdmins(CommandContext<ServerCommandSource> context) {
        Set<String> admins = IPWLMod.getConfig().getAdmins();
        
        if (admins.isEmpty()) {
            IPWLMod.sendFeedback(context.getSource(), "§7No IPWL Admins defined.");
            return 1;
        }
        
        // Clean feedback loop for Console/Chat separation
        IPWLMod.sendFeedback(context.getSource(), "§6=== IPWL Admins ===");
        IPWLMod.sendFeedback(context.getSource(), "§7(These players have access to IPWL commands without OP)");
        
        for (String admin : admins) {
            IPWLMod.sendFeedback(context.getSource(), "§a- " + admin);
        }
        return 1;
    }
    
    // --- [Standard Whitelist Methods] ---
    
    private static int addPlayer(CommandContext<ServerCommandSource> context, String ip) {
        String playerName = StringArgumentType.getString(context, "player");
        WhitelistManager manager = IPWLMod.getWhitelistManager();
        if (ip == null || ip.isEmpty()) ip = "*";
        manager.addPlayer(playerName, ip);
        String message = ip.equals("*") 
            ? String.format("§aAdded %s to whitelist (any IP)", playerName)
            : String.format("§aAdded %s to whitelist with IP %s", playerName, ip);
        IPWLMod.sendFeedback(context.getSource(), message);
        return 1;
    }
    
    private static int removePlayer(CommandContext<ServerCommandSource> context) {
        String playerName = StringArgumentType.getString(context, "player");
        WhitelistManager manager = IPWLMod.getWhitelistManager();
        if (!manager.hasPlayer(playerName)) {
            IPWLMod.sendFeedback(context.getSource(), String.format("§c%s is not whitelisted", playerName));
            return 0;
        }
        manager.removePlayer(playerName);
        IPWLMod.sendFeedback(context.getSource(), String.format("§aRemoved %s from whitelist", playerName));
        if (IPWLMod.getConfig().isKickOnWhitelistRemoval()) {
            context.getSource().getServer().getPlayerManager().getPlayerList().stream()
                .filter(player -> player.getName().getString().equals(playerName))
                .findFirst()
                .ifPresent(player -> player.networkHandler.disconnect(
                    Text.literal("§cYou have been removed from the whitelist")));
        }
        return 1;
    }
    
    private static int listWhitelist(CommandContext<ServerCommandSource> context) {
        WhitelistManager manager = IPWLMod.getWhitelistManager();
        for (Text line : manager.getFormattedList()) {
            // Using sendFeedback helper for each line handles colors/logging correctly
            IPWLMod.sendFeedback(context.getSource(), line.getString());
        }
        return 1;
    }
    
    private static int reloadWhitelist(CommandContext<ServerCommandSource> context) {
        WhitelistManager manager = IPWLMod.getWhitelistManager();
        manager.reload();
        IPWLMod.sendFeedback(context.getSource(), "§aWhitelist reloaded successfully");
        return 1;
    }
    
    private static int addIpToPlayer(CommandContext<ServerCommandSource> context) {
        String playerName = StringArgumentType.getString(context, "player");
        String ip = StringArgumentType.getString(context, "ip");
        WhitelistManager manager = IPWLMod.getWhitelistManager();
        if (!manager.hasPlayer(playerName)) {
            IPWLMod.sendFeedback(context.getSource(), String.format("§c%s is not whitelisted", playerName));
            return 0;
        }
        manager.addIpToPlayer(playerName, ip);
        IPWLMod.sendFeedback(context.getSource(), String.format("§aAdded IP %s to %s's whitelist", ip, playerName));
        return 1;
    }
    
    private static int removeIpFromPlayer(CommandContext<ServerCommandSource> context) {
        String playerName = StringArgumentType.getString(context, "player");
        String ip = StringArgumentType.getString(context, "ip");
        WhitelistManager manager = IPWLMod.getWhitelistManager();
        Set<String> ips = manager.getPlayerIps(playerName);
        if (!ips.contains(ip)) {
            IPWLMod.sendFeedback(context.getSource(), String.format("§c%s doesn't have IP %s whitelisted", playerName, ip));
            return 0;
        }
        manager.removeIpFromPlayer(playerName, ip);
        IPWLMod.sendFeedback(context.getSource(), String.format("§aRemoved IP %s from %s's whitelist", ip, playerName));
        return 1;
    }
}