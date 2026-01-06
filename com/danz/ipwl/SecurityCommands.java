package com.danz.fabric.ipwl.commands;

import com.danz.fabric.ipwl.IPWLMod;
import com.danz.fabric.ipwl.manager.SecurityManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class SecurityCommands {
    
    private static final Map<String, PlayerSeenData> lastSeenData = new HashMap<>();
    private static final Map<String, Long> joinTimes = new HashMap<>();


public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {

        // security commands - UPDATED PERMISSION
        dispatcher.register(CommandManager.literal("security")
            .requires(IPWLMod::hasPermission) // Modified check
            .then(CommandManager.literal("stats")
                .executes(context -> {
                    SecurityManager security = IPWLMod.getSecurityManager();
                    for (Text line : security.getStatus()) {
                        context.getSource().sendFeedback(() -> line, false);
                        IPWLMod.LOGGER.info("[IPWL] " + line.getString().replaceAll("§[0-9a-fk-or]", ""));
                    }
                    return 1;
                }))
            .then(CommandManager.literal("resetstats")
                .executes(context -> {
                    SecurityManager security = IPWLMod.getSecurityManager();
                    security.resetStats();
                    IPWLMod.sendFeedback(context.getSource(), "§aSecurity statistics reset (all-time and session)");
                    return 1;
                }))
            .then(CommandManager.literal("resetsession")
                .executes(context -> {
                    SecurityManager security = IPWLMod.getSecurityManager();
                    security.resetSessionStats();
                    IPWLMod.sendFeedback(context.getSource(), "§aSession statistics reset");
                    return 1;
                })));

        // Lockdown command - UPDATED PERMISSION
        dispatcher.register(CommandManager.literal("lockdown")
            .requires(IPWLMod::hasPermission) // Modified check
            .then(CommandManager.literal("on")
                .executes(context -> setLockdown(context.getSource(), true))
            )
            .then(CommandManager.literal("off")
                .executes(context -> setLockdown(context.getSource(), false))
            )
            .then(CommandManager.literal("status")
                .executes(SecurityCommands::getLockdownStatus))
            .executes(SecurityCommands::getLockdownStatus));

        // Lockdown shortcut - UPDATED PERMISSION
        dispatcher.register(CommandManager.literal("lk")
            .requires(IPWLMod::hasPermission) // Modified check
            .then(CommandManager.literal("on")
                .executes(context -> setLockdown(context.getSource(), true))
            )
            .then(CommandManager.literal("off")
                .executes(context -> setLockdown(context.getSource(), false))
            )
            .then(CommandManager.literal("status")
                .executes(SecurityCommands::getLockdownStatus))
            .executes(SecurityCommands::getLockdownStatus));

        // Seen command - UPDATED PERMISSION
        dispatcher.register(CommandManager.literal("seen")
            .requires(IPWLMod::hasPermission) // Modified check, was level 2
            .then(CommandManager.argument("player", StringArgumentType.word())
                .executes(SecurityCommands::getPlayerSeen)));
        
        // Connections command - UPDATED PERMISSION
        dispatcher.register(CommandManager.literal("connections")
            .requires(IPWLMod::hasPermission) // Modified check
            .executes(SecurityCommands::showActiveConnections));
    }
    
    private static int setLockdown(ServerCommandSource source, boolean enabled) {
        SecurityManager security = IPWLMod.getSecurityManager();
        security.setLockdownMode(enabled);
        
        if (enabled) {
            IPWLMod.sendFeedback(source, "§c⚠ LOCKDOWN MODE ACTIVATED ⚠\n§eAll new connections will be rejected!");
            source.getServer().getPlayerManager().broadcast(
                Text.literal("§c[IPLW SECURITY] Server is now in lockdown mode. No new players can join."),
                false
            );
        } else {
            IPWLMod.sendFeedback(source, "§aLockdown mode deactivated. New connections are now allowed.");
            source.getServer().getPlayerManager().broadcast(
                Text.literal("§a[IPWL SECURITY] Lockdown mode has been lifted."),
                false
            );
        }
        return 1;
    }
    
    public static int getLockdownStatus(CommandContext<ServerCommandSource> context) {
        SecurityManager security = IPWLMod.getSecurityManager();
        boolean status = security.isLockdownMode();
        String statusText = status ? "§c⚠ ACTIVE ⚠" : "§aInactive";
        String description = status ? "§7New connections are being rejected" : "§7New connections are allowed";
        IPWLMod.sendFeedback(context.getSource(), String.format("§6Lockdown Mode: %s\n%s", statusText, description));
        return 1;
    }
    
    public static int getPlayerSeen(CommandContext<ServerCommandSource> context) {
        String playerName = StringArgumentType.getString(context, "player");
        ServerPlayerEntity onlinePlayer = context.getSource().getServer().getPlayerManager().getPlayer(playerName);
        
        if (onlinePlayer != null) {
            String ip = onlinePlayer.getIp();
            long joinTime = joinTimes.getOrDefault(playerName, System.currentTimeMillis());
            long uptimeMs = System.currentTimeMillis() - joinTime;
            String uptime = formatTimeAgo(uptimeMs);
            IPWLMod.sendFeedback(context.getSource(), 
                String.format("§a%s is currently §aONLINE\n§7IP: %s\n§7Online for: %s", playerName, ip, uptime));
            return 1;
        }
        
        PlayerSeenData seenData = lastSeenData.get(playerName.toLowerCase());
        if (seenData != null) {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String lastSeenFormatted = dateFormat.format(new Date(seenData.lastSeen));
            long timeDiff = System.currentTimeMillis() - seenData.lastSeen;
            String timeAgo = formatTimeAgo(timeDiff);
            IPWLMod.sendFeedback(context.getSource(), 
                String.format("§e%s was last seen §e%s ago\n§7Date: %s\n§7Last IP: %s", playerName, timeAgo, lastSeenFormatted, seenData.lastIp));
        } else {
            IPWLMod.sendFeedback(context.getSource(), String.format("§7No data found for player §f%s", playerName));
        }
        return 1;
    }
    
    public static int showActiveConnections(CommandContext<ServerCommandSource> context) {
        IPWLMod.sendFeedback(context.getSource(), "§6=== Active Connections ===");
        context.getSource().getServer().getPlayerManager().getPlayerList().forEach(player -> {
            String username = player.getName().getString();
            String ip = player.getIp();
            long joinTime = joinTimes.getOrDefault(username, System.currentTimeMillis());
            long uptimeMs = System.currentTimeMillis() - joinTime;
            String uptime = formatTimeAgo(uptimeMs);
            IPWLMod.sendFeedback(context.getSource(), 
                String.format("§a%s §7→ §f%s §7(online %s)", username, ip, uptime));
        });
        return 1;
    }
    
    private static String formatTimeAgo(long milliseconds) {
        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        
        if (days > 0) return days + " day" + (days != 1 ? "s" : "");
        else if (hours > 0) return hours + " hour" + (hours != 1 ? "s" : "");
        else if (minutes > 0) return minutes + " minute" + (minutes != 1 ? "s" : "");
        else return seconds + " second" + (seconds != 1 ? "s" : "");
    }
    
    public static void updatePlayerSeen(String username, String ip) {
        lastSeenData.put(username.toLowerCase(), new PlayerSeenData(System.currentTimeMillis(), ip));
        joinTimes.put(username, System.currentTimeMillis());
    }
    
    public static void updatePlayerLeft(String username, String ip) {
        lastSeenData.put(username.toLowerCase(), new PlayerSeenData(System.currentTimeMillis(), ip));
        joinTimes.remove(username);
    }
    
    private static class PlayerSeenData {
        public final long lastSeen;
        public final String lastIp;
        public PlayerSeenData(long lastSeen, String lastIp) {
            this.lastSeen = lastSeen;
            this.lastIp = lastIp;
        }
    }
}