package com.danz.ipwl.commands;

import com.danz.ipwl.IPWLMod;
import com.danz.ipwl.manager.SecurityManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class SecurityCommands {

    private static final Map<String, PlayerSeenData> lastSeenData = new HashMap<>();
    // username -> join timestamp (only present while online)
    private static final Map<String, Long> joinTimes = new HashMap<>();
    // ip -> join timestamp for /connections
    private static final Map<String, ConnectionData> activeConnections = new LinkedHashMap<>();

    // Called by WhitelistCommands — no dispatcher registration needed here
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // All commands are registered in WhitelistCommands.register().
        // This method is kept for compatibility with IPWLMod's registration call.
    }

    // -------------------------------------------------------------------------
    // /seen <player>
    // -------------------------------------------------------------------------
    public static int handleSeen(CommandSourceStack source, String username) {
        if (joinTimes.containsKey(username)) {
            long joinTime = joinTimes.get(username);
            String uptime = formatDuration(System.currentTimeMillis() - joinTime);
            PlayerSeenData data = lastSeenData.get(username.toLowerCase());
            String ip = data != null ? data.lastIp : "unknown";

            IPWLMod.sendFeedback(source,
                String.format("§a%s §7is currently §2online §7from §f%s §7(session: %s)", username, ip, uptime));
            return 1;
        }

        PlayerSeenData data = lastSeenData.get(username.toLowerCase());
        if (data == null) {
            IPWLMod.sendFeedback(source, "§cNo record found for §f" + username + "§c.");
            return 0;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String dateStr = sdf.format(new Date(data.lastSeen));
        String timeAgo = formatDuration(System.currentTimeMillis() - data.lastSeen);

        IPWLMod.sendFeedback(source,
            String.format("§f%s §7was last seen §e%s §7(%s ago) from IP §f%s",
                username, dateStr, timeAgo, data.lastIp));
        return 1;
    }

    // -------------------------------------------------------------------------
    // /connections
    // -------------------------------------------------------------------------
    public static int handleConnections(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        if (activeConnections.isEmpty()) {
            IPWLMod.sendFeedback(source, "§7No active connections tracked.");
            return 1;
        }

        IPWLMod.sendFeedback(source, "§6=== Active Connections ===");
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ConnectionData> entry : activeConnections.entrySet()) {
            String username = entry.getKey();
            ConnectionData conn = entry.getValue();
            String uptime = formatDuration(now - conn.joinTime);
            IPWLMod.sendFeedback(source,
                String.format("§a%s §7— §f%s §7(online %s)", username, conn.ip, uptime));
        }
        IPWLMod.sendFeedback(source,
            String.format("§7Total: §f%d §7player(s) online.", activeConnections.size()));
        return 1;
    }

    // -------------------------------------------------------------------------
    // Called from ConnectionEventHandler
    // -------------------------------------------------------------------------
    public static void updatePlayerSeen(String username, String ip) {
        lastSeenData.put(username.toLowerCase(), new PlayerSeenData(System.currentTimeMillis(), ip));
        joinTimes.put(username, System.currentTimeMillis());
        activeConnections.put(username, new ConnectionData(ip, System.currentTimeMillis()));
    }

    public static void updatePlayerLeft(String username, String ip) {
        lastSeenData.put(username.toLowerCase(), new PlayerSeenData(System.currentTimeMillis(), ip));
        joinTimes.remove(username);
        activeConnections.remove(username);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private static String formatDuration(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0)         return days    + " day"    + (days    != 1 ? "s" : "");
        else if (hours > 0)   return hours   + " hour"   + (hours   != 1 ? "s" : "");
        else if (minutes > 0) return minutes + " minute" + (minutes != 1 ? "s" : "");
        else                  return seconds + " second" + (seconds != 1 ? "s" : "");
    }

    private static class PlayerSeenData {
        public final long lastSeen;
        public final String lastIp;
        PlayerSeenData(long lastSeen, String lastIp) {
            this.lastSeen = lastSeen;
            this.lastIp = lastIp;
        }
    }

    private static class ConnectionData {
        public final String ip;
        public final long joinTime;
        ConnectionData(String ip, long joinTime) {
            this.ip = ip;
            this.joinTime = joinTime;
        }
    }
}