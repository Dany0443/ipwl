package com.danz.ipwl.commands;

import com.danz.ipwl.IPWLMod;
import com.danz.ipwl.config.IPWLMessages;
import com.danz.ipwl.manager.SecurityManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Registers all /ipwl sub-commands and /lockdown.
 *
 * <p>/seen, /connections, and /security are registered separately by
 * {@link SecurityCommands#register}.
 */
public class WhitelistCommands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {

        dispatcher.register(CommandManager.literal("ipwl")
            .requires(IPWLMod::hasPermission)

            // /ipwl add <player> [ip]
            .then(CommandManager.literal("add")
                .then(CommandManager.argument("player", StringArgumentType.word())
                    .executes(ctx -> addPlayer(ctx, "*"))
                    .then(CommandManager.argument("ip", StringArgumentType.greedyString())
                        .executes(ctx -> addPlayer(ctx,
                            StringArgumentType.getString(ctx, "ip"))))))

            // /ipwl remove <player>
            .then(CommandManager.literal("remove")
                .then(CommandManager.argument("player", StringArgumentType.word())
                    .executes(WhitelistCommands::removePlayer)))

            // /ipwl list
            .then(CommandManager.literal("list")
                .executes(WhitelistCommands::listWhitelist))

            // /ipwl reload
            .then(CommandManager.literal("reload")
                .executes(WhitelistCommands::reloadConfig))

            // /ipwl addip <player> <ip>
            .then(CommandManager.literal("addip")
                .then(CommandManager.argument("player", StringArgumentType.word())
                    .then(CommandManager.argument("ip", StringArgumentType.string())
                        .executes(WhitelistCommands::addIpToPlayer))))

            // /ipwl removeip <player> <ip>
            .then(CommandManager.literal("removeip")
                .then(CommandManager.argument("player", StringArgumentType.word())
                    .then(CommandManager.argument("ip", StringArgumentType.string())
                        .executes(WhitelistCommands::removeIpFromPlayer))))

            // /ipwl tempadd <player> <ip> <duration>
            // duration examples: 30m  2h  1d  3600s
            .then(CommandManager.literal("tempadd")
                .then(CommandManager.argument("player", StringArgumentType.word())
                    .then(CommandManager.argument("ip", StringArgumentType.string())
                        .then(CommandManager.argument("duration", StringArgumentType.word())
                            .executes(WhitelistCommands::tempAddPlayer)))))

            // /ipwl banip <ip>  |  /ipwl banip list
            .then(CommandManager.literal("banip")
                .then(CommandManager.literal("list")
                    .executes(WhitelistCommands::listBannedIps))
                .then(CommandManager.argument("ip", StringArgumentType.word())
                    .executes(WhitelistCommands::banIp)))

            // /ipwl unbanip <ip>
            .then(CommandManager.literal("unbanip")
                .then(CommandManager.argument("ip", StringArgumentType.word())
                    .executes(WhitelistCommands::unbanIp)))

            // /ipwl admin add|remove|list
            .then(CommandManager.literal("admin")
                .then(CommandManager.literal("add")
                    .then(CommandManager.argument("username", StringArgumentType.word())
                        .executes(ctx -> modifyAdmin(ctx, true))))
                .then(CommandManager.literal("remove")
                    .then(CommandManager.argument("username", StringArgumentType.word())
                        .executes(ctx -> modifyAdmin(ctx, false))))
                .then(CommandManager.literal("list")
                    .executes(WhitelistCommands::listAdmins)))

            // /ipwl logs verbose|silent
            .then(CommandManager.literal("logs")
                .then(CommandManager.literal("verbose")
                    .executes(ctx -> setLogging(ctx, true)))
                .then(CommandManager.literal("silent")
                    .executes(ctx -> setLogging(ctx, false))))
        );

        // /lockdown on|off
        dispatcher.register(CommandManager.literal("lockdown")
            .requires(IPWLMod::hasPermission)
            .then(CommandManager.literal("on")
                .executes(ctx -> setLockdown(ctx, true)))
            .then(CommandManager.literal("off")
                .executes(ctx -> setLockdown(ctx, false)))
        );

        // NOTE: /seen, /connections, /security are registered by SecurityCommands.register()
    }

    // -------------------------------------------------------------------------
    // Whitelist mutation
    // -------------------------------------------------------------------------

    private static int addPlayer(CommandContext<ServerCommandSource> ctx, String ip) {
        String playerName = StringArgumentType.getString(ctx, "player");
        if (ip == null || ip.isBlank()) ip = "*";
        IPWLMod.getWhitelistManager().addPlayer(playerName, ip);
        if (ip.equals("*")) {
            IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.add_any", playerName));
            broadcastToAdmins(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.add_broadcast_any", playerName));
        } else {
            IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.add_ip", playerName, ip));
            broadcastToAdmins(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.add_broadcast_ip", playerName, ip));
        }
        return 1;
    }

    private static int removePlayer(CommandContext<ServerCommandSource> ctx) {
        String playerName = StringArgumentType.getString(ctx, "player");
        if (!IPWLMod.getWhitelistManager().hasPlayer(playerName)) {
            IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.not_whitelisted", playerName));
            return 0;
        }
        IPWLMod.getWhitelistManager().removePlayer(playerName);
        IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.remove", playerName));
        broadcastToAdmins(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.remove_broadcast", playerName, ctx.getSource().getName()));
        // Always kick immediately
        var playerManager = ctx.getSource().getServer().getPlayerManager();
        if (playerManager != null) {
            ServerPlayerEntity target = playerManager.getPlayer(playerName);
            if (target != null) {
                target.networkHandler.disconnect(Text.literal(IPWLMessages.get("ipwl.disconnect.removed")));
            }
        }
        return 1;
    }

    private static int listWhitelist(CommandContext<ServerCommandSource> ctx) {
        for (Text line : IPWLMod.getWhitelistManager().getFormattedList()) {
            ctx.getSource().sendFeedback(() -> line, false);
        }
        return 1;
    }

    private static int reloadConfig(CommandContext<ServerCommandSource> ctx) {
        IPWLMod.reloadConfig();
        IPWLMod.getWhitelistManager().reload();
        IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.get("ipwl.cmd.reload"));
        return 1;
    }

    private static int addIpToPlayer(CommandContext<ServerCommandSource> ctx) {
        String playerName = StringArgumentType.getString(ctx, "player");
        String ip         = StringArgumentType.getString(ctx, "ip");
        if (!IPWLMod.getWhitelistManager().hasPlayer(playerName)) {
            IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.not_whitelisted", playerName));
            return 0;
        }
        IPWLMod.getWhitelistManager().addIpToPlayer(playerName, ip);
        IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.addip", ip, playerName));
        return 1;
    }

    private static int removeIpFromPlayer(CommandContext<ServerCommandSource> ctx) {
        String playerName = StringArgumentType.getString(ctx, "player");
        String ip         = StringArgumentType.getString(ctx, "ip");
        if (!IPWLMod.getWhitelistManager().getPlayerIps(playerName).contains(ip)) {
            IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.removeip_notfound", playerName, ip));
            return 0;
        }
        IPWLMod.getWhitelistManager().removeIpFromPlayer(playerName, ip);
        IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.removeip", ip, playerName));
        return 1;
    }

    // -------------------------------------------------------------------------
    // Temporary approval
    // -------------------------------------------------------------------------

    private static int tempAddPlayer(CommandContext<ServerCommandSource> ctx) {
        String playerName  = StringArgumentType.getString(ctx, "player");
        String ip          = StringArgumentType.getString(ctx, "ip");
        String durationStr = StringArgumentType.getString(ctx, "duration");

        long durationMs;
        try {
            durationMs = parseDuration(durationStr);
        } catch (IllegalArgumentException e) {
            IPWLMod.sendFeedback(ctx.getSource(),
                IPWLMessages.fmt("ipwl.cmd.tempadd_bad_duration", durationStr));
            return 0;
        }

        IPWLMod.getWhitelistManager().addTempApproval(playerName, ip, durationMs);
        IPWLMod.sendFeedback(ctx.getSource(),
            IPWLMessages.fmt("ipwl.cmd.tempadd", playerName, ip, durationStr));
        return 1;
    }

    /**
     * Parse a human-friendly duration string into milliseconds.
     * Supported suffixes: {@code s} seconds, {@code m} minutes, {@code h} hours, {@code d} days.
     */
    public static long parseDuration(String s) {
        s = s.trim().toLowerCase();
        if (s.isEmpty()) throw new IllegalArgumentException("empty duration");
        char suffix = s.charAt(s.length() - 1);
        long value;
        try {
            value = Long.parseLong(s.substring(0, s.length() - 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad number in duration: " + s);
        }
        return switch (suffix) {
            case 's' -> TimeUnit.SECONDS.toMillis(value);
            case 'm' -> TimeUnit.MINUTES.toMillis(value);
            case 'h' -> TimeUnit.HOURS.toMillis(value);
            case 'd' -> TimeUnit.DAYS.toMillis(value);
            default  -> throw new IllegalArgumentException("unknown suffix: " + suffix);
        };
    }

    // -------------------------------------------------------------------------
    // Permanent IP bans
    // -------------------------------------------------------------------------

    private static int banIp(CommandContext<ServerCommandSource> ctx) {
        String ip = StringArgumentType.getString(ctx, "ip");
        if (IPWLMod.getConfig().isBannedIp(ip)) {
            IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.banip_already", ip));
            return 0;
        }
        IPWLMod.getConfig().banIp(ip);
        kickByIp(ip);
        IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.banip", ip));
        broadcastToAdmins(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.ban_broadcast", ip, ctx.getSource().getName()));
        return 1;
    }

    private static int unbanIp(CommandContext<ServerCommandSource> ctx) {
        String ip = StringArgumentType.getString(ctx, "ip");
        if (!IPWLMod.getConfig().isBannedIp(ip)) {
            IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.unbanip_notfound", ip));
            return 0;
        }
        IPWLMod.getConfig().unbanIp(ip);
        IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.unbanip", ip));
        return 1;
    }

    private static int listBannedIps(CommandContext<ServerCommandSource> ctx) {
        Set<String> banned = IPWLMod.getConfig().getBannedIps();
        if (banned.isEmpty()) {
            IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.get("ipwl.cmd.banip_list_empty"));
            return 1;
        }
        IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.get("ipwl.cmd.banip_list_header"));
        for (String ip : banned) {
            IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.banip_list_entry", ip));
        }
        return 1;
    }

    private static void kickByIp(String targetIp) {
        var server = IPWLMod.getServer();
        if (server == null) return;
        var playerManager = server.getPlayerManager();
        if (playerManager == null) return;
        for (ServerPlayerEntity player : playerManager.getPlayerList()) {
            if (targetIp.equals(getPlayerIp(player))) {
                player.networkHandler.disconnect(
                    Text.literal(IPWLMessages.get("ipwl.disconnect.temp_banned")));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Admin / logging / lockdown
    // -------------------------------------------------------------------------

    private static int modifyAdmin(CommandContext<ServerCommandSource> ctx, boolean add) {
        String username = StringArgumentType.getString(ctx, "username");
        if (add) {
            IPWLMod.getConfig().addAdmin(username);
            IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.admin_add", username));
        } else {
            IPWLMod.getConfig().removeAdmin(username);
            IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.admin_remove", username));
            // hasPermission() reads config live so commands are blocked immediately.
            // Kick them only if they have no whitelist entry - if they are whitelisted
            // they stay connected, they just lose admin commands.
            var server = IPWLMod.getServer();
            if (server != null) {
                var playerManager = server.getPlayerManager();
                if (playerManager == null) return 1;
                ServerPlayerEntity target = playerManager.getPlayer(username);
                if (target != null) {
                    if (!IPWLMod.getWhitelistManager().hasPlayer(username)) {
                        // Not whitelisted either - kick them off entirely
                        target.networkHandler.disconnect(
                            Text.literal(IPWLMessages.get("ipwl.disconnect.removed")));
                    } else {
                        // Still whitelisted - stay connected but push a refreshed command
                        // tree so /ipwl disappears from their tab-complete immediately.
                        playerManager.sendCommandTree(target);
                    }
                }
            }
        }
        return 1;
    }

    private static int listAdmins(CommandContext<ServerCommandSource> ctx) {
        Set<String> admins = IPWLMod.getConfig().getAdmins();
        if (admins.isEmpty()) {
            IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.get("ipwl.cmd.admin_none"));
            return 1;
        }
        IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.get("ipwl.cmd.admin_header"));
        for (String admin : admins) {
            IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.admin_entry", admin));
        }
        return 1;
    }

    private static int setLogging(CommandContext<ServerCommandSource> ctx, boolean verbose) {
        IPWLMod.getConfig().setVerboseLogging(verbose);
        IPWLMod.sendFeedback(ctx.getSource(),
            IPWLMessages.get(verbose ? "ipwl.cmd.logs_verbose" : "ipwl.cmd.logs_silent"));
        return 1;
    }

    private static int setLockdown(CommandContext<ServerCommandSource> ctx, boolean enable) {
        SecurityManager security = IPWLMod.getSecurityManager();
        if (security == null) {
            IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.get("ipwl.cmd.security_not_ready"));
            return 0;
        }
        security.setLockdownMode(enable);
        IPWLMod.sendFeedback(ctx.getSource(),
            IPWLMessages.get(enable ? "ipwl.cmd.lockdown_on" : "ipwl.cmd.lockdown_off"));
        return 1;
    }

    // -------------------------------------------------------------------------

    /**
     * Notify all online admins of a whitelist change.
     * Skips the source itself (they already got direct feedback).
     */
    private static void broadcastToAdmins(ServerCommandSource source, String message) {
        var server = IPWLMod.getServer();
        if (server == null) return;
        var playerManager = server.getPlayerManager();
        if (playerManager == null) return;
        var adminNames = IPWLMod.getConfig().getAdmins();
        String sourceName = source.getName();
        for (net.minecraft.server.network.ServerPlayerEntity p : playerManager.getPlayerList()) {
            String name = p.getName().getString();
            if (adminNames.contains(name) && !name.equals(sourceName)) {
                p.sendMessage(net.minecraft.text.Text.literal(message));
            }
        }
        IPWLMod.LOGGER.info("[IPWL] {}", message);
    }

    static String getPlayerIp(ServerPlayerEntity player) {
        if (player.networkHandler.getConnectionAddress() instanceof InetSocketAddress addr) {
            return addr.getAddress().getHostAddress();
        }
        return "unknown";
    }
}
