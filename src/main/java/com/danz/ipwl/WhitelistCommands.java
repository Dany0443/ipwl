package com.danz.ipwl.commands;

import com.danz.ipwl.IPWLMod;
import com.danz.ipwl.config.IPWLMessages;
import com.danz.ipwl.manager.SecurityManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

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

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {

        dispatcher.register(Commands.literal("ipwl")
            .requires(IPWLMod::hasPermission)

            // /ipwl add <player> [ip]
            .then(Commands.literal("add")
                .then(Commands.argument("player", StringArgumentType.word())
                    .executes(ctx -> addPlayer(ctx, "*"))
                    .then(Commands.argument("ip", StringArgumentType.greedyString())
                        .executes(ctx -> addPlayer(ctx,
                            StringArgumentType.getString(ctx, "ip"))))))

            // /ipwl remove <player>
            .then(Commands.literal("remove")
                .then(Commands.argument("player", StringArgumentType.word())
                    .executes(WhitelistCommands::removePlayer)))

            // /ipwl list
            .then(Commands.literal("list")
                .executes(WhitelistCommands::listWhitelist))

            // /ipwl reload
            .then(Commands.literal("reload")
                .executes(WhitelistCommands::reloadConfig))

            // /ipwl addip <player> <ip>
            .then(Commands.literal("addip")
                .then(Commands.argument("player", StringArgumentType.word())
                    .then(Commands.argument("ip", StringArgumentType.string())
                        .executes(WhitelistCommands::addIpToPlayer))))

            // /ipwl removeip <player> <ip>
            .then(Commands.literal("removeip")
                .then(Commands.argument("player", StringArgumentType.word())
                    .then(Commands.argument("ip", StringArgumentType.string())
                        .executes(WhitelistCommands::removeIpFromPlayer))))

            // /ipwl tempadd <player> <ip> <duration>
            // duration examples: 30m  2h  1d  3600s
            .then(Commands.literal("tempadd")
                .then(Commands.argument("player", StringArgumentType.word())
                    .then(Commands.argument("ip", StringArgumentType.string())
                        .then(Commands.argument("duration", StringArgumentType.word())
                            .executes(WhitelistCommands::tempAddPlayer)))))

            // /ipwl banip <ip>  |  /ipwl banip list
            .then(Commands.literal("banip")
                .then(Commands.literal("list")
                    .executes(WhitelistCommands::listBannedIps))
                .then(Commands.argument("ip", StringArgumentType.word())
                    .executes(WhitelistCommands::banIp)))

            // /ipwl unbanip <ip>
            .then(Commands.literal("unbanip")
                .then(Commands.argument("ip", StringArgumentType.word())
                    .executes(WhitelistCommands::unbanIp)))

            // /ipwl admin add|remove|list
            .then(Commands.literal("admin")
                .then(Commands.literal("add")
                    .then(Commands.argument("username", StringArgumentType.word())
                        .executes(ctx -> modifyAdmin(ctx, true))))
                .then(Commands.literal("remove")
                    .then(Commands.argument("username", StringArgumentType.word())
                        .executes(ctx -> modifyAdmin(ctx, false))))
                .then(Commands.literal("list")
                    .executes(WhitelistCommands::listAdmins)))

            // /ipwl logs verbose|silent
            .then(Commands.literal("logs")
                .then(Commands.literal("verbose")
                    .executes(ctx -> setLogging(ctx, true)))
                .then(Commands.literal("silent")
                    .executes(ctx -> setLogging(ctx, false))))
        );

        // /lockdown on|off
        dispatcher.register(Commands.literal("lockdown")
            .requires(IPWLMod::hasPermission)
            .then(Commands.literal("on")
                .executes(ctx -> setLockdown(ctx, true)))
            .then(Commands.literal("off")
                .executes(ctx -> setLockdown(ctx, false)))
        );

        // NOTE: /seen, /connections, /security are registered by SecurityCommands.register()
    }

    // -------------------------------------------------------------------------
    // Whitelist mutation
    // -------------------------------------------------------------------------

    private static int addPlayer(CommandContext<CommandSourceStack> ctx, String ip) {
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

    private static int removePlayer(CommandContext<CommandSourceStack> ctx) {
        String playerName = StringArgumentType.getString(ctx, "player");
        if (!IPWLMod.getWhitelistManager().hasPlayer(playerName)) {
            IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.not_whitelisted", playerName));
            return 0;
        }
        IPWLMod.getWhitelistManager().removePlayer(playerName);
        IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.remove", playerName));
        broadcastToAdmins(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.remove_broadcast", playerName, ctx.getSource().getTextName()));
        // Always kick immediately
        ServerPlayer target = ctx.getSource().getServer().getPlayerList().getPlayerByName(playerName);
        if (target != null) {
            target.connection.disconnect(Component.literal(IPWLMessages.get("ipwl.disconnect.removed")));
        }
        return 1;
    }

    private static int listWhitelist(CommandContext<CommandSourceStack> ctx) {
        for (Component line : IPWLMod.getWhitelistManager().getFormattedList()) {
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> ctx) {
        IPWLMod.reloadConfig();
        IPWLMod.getWhitelistManager().reload();
        IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.get("ipwl.cmd.reload"));
        return 1;
    }

    private static int addIpToPlayer(CommandContext<CommandSourceStack> ctx) {
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

    private static int removeIpFromPlayer(CommandContext<CommandSourceStack> ctx) {
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

    private static int tempAddPlayer(CommandContext<CommandSourceStack> ctx) {
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

    private static int banIp(CommandContext<CommandSourceStack> ctx) {
        String ip = StringArgumentType.getString(ctx, "ip");
        if (IPWLMod.getConfig().isBannedIp(ip)) {
            IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.banip_already", ip));
            return 0;
        }
        IPWLMod.getConfig().banIp(ip);
        kickByIp(ip);
        IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.banip", ip));
        broadcastToAdmins(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.ban_broadcast", ip, ctx.getSource().getTextName()));
        return 1;
    }

    private static int unbanIp(CommandContext<CommandSourceStack> ctx) {
        String ip = StringArgumentType.getString(ctx, "ip");
        if (!IPWLMod.getConfig().isBannedIp(ip)) {
            IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.unbanip_notfound", ip));
            return 0;
        }
        IPWLMod.getConfig().unbanIp(ip);
        IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.unbanip", ip));
        return 1;
    }

    private static int listBannedIps(CommandContext<CommandSourceStack> ctx) {
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
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (targetIp.equals(getPlayerIp(player))) {
                player.connection.disconnect(
                    Component.literal(IPWLMessages.get("ipwl.disconnect.temp_banned")));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Admin / logging / lockdown
    // -------------------------------------------------------------------------

    private static int modifyAdmin(CommandContext<CommandSourceStack> ctx, boolean add) {
        String username = StringArgumentType.getString(ctx, "username");
        if (add) {
            IPWLMod.getConfig().addAdmin(username);
            IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.admin_add", username));
        } else {
            IPWLMod.getConfig().removeAdmin(username);
            IPWLMod.sendFeedback(ctx.getSource(), IPWLMessages.fmt("ipwl.cmd.admin_remove", username));
            // hasPermission() reads config live so commands are blocked immediately.
            // Kick them only if they have no whitelist entry — if they are whitelisted
            // they stay connected, they just lose admin commands.
            var server = IPWLMod.getServer();
            if (server != null) {
                ServerPlayer target = server.getPlayerList().getPlayerByName(username);
                if (target != null) {
                    if (!IPWLMod.getWhitelistManager().hasPlayer(username)) {
                        // Not whitelisted either — kick them off entirely
                        target.connection.disconnect(
                            Component.literal(IPWLMessages.get("ipwl.disconnect.removed")));
                    } else {
                        // Still whitelisted — stay connected but push a refreshed command
                        // tree so /ipwl disappears from their tab-complete immediately.
                        server.getCommands().sendCommands(target);
                    }
                }
            }
        }
        return 1;
    }

    private static int listAdmins(CommandContext<CommandSourceStack> ctx) {
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

    private static int setLogging(CommandContext<CommandSourceStack> ctx, boolean verbose) {
        IPWLMod.getConfig().setVerboseLogging(verbose);
        IPWLMod.sendFeedback(ctx.getSource(),
            IPWLMessages.get(verbose ? "ipwl.cmd.logs_verbose" : "ipwl.cmd.logs_silent"));
        return 1;
    }

    private static int setLockdown(CommandContext<CommandSourceStack> ctx, boolean enable) {
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
    private static void broadcastToAdmins(CommandSourceStack source, String message) {
        var server = IPWLMod.getServer();
        if (server == null) return;
        var adminNames = IPWLMod.getConfig().getAdmins();
        String sourceName = source.getTextName();
        for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
            String name = p.getName().getString();
            if (adminNames.contains(name) && !name.equals(sourceName)) {
                p.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
            }
        }
        IPWLMod.LOGGER.info("[IPWL] {}", message);
    }

    static String getPlayerIp(ServerPlayer player) {
        if (player.connection.getRemoteAddress() instanceof InetSocketAddress addr) {
            return addr.getAddress().getHostAddress();
        }
        return "unknown";
    }
}