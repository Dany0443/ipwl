package com.danz.ipwl.commands;

import com.danz.ipwl.IPWLMod;
import com.danz.ipwl.manager.WhitelistManager;
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

public class WhitelistCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {

        // /ipwl add <player> [ip]
        // /ipwl remove <player>
        // /ipwl list
        // /ipwl reload
        // /ipwl addip <player> <ip>
        // /ipwl removeip <player> <ip>
        // /ipwl admin add|remove|list
        // /ipwl logs verbose|silent
        dispatcher.register(Commands.literal("ipwl")
            .requires(IPWLMod::hasPermission)

            .then(Commands.literal("add")
                .then(Commands.argument("player", StringArgumentType.word())
                    // /ipwl add <player>  -> any IP (wildcard)
                    .executes(context -> addPlayer(context, "*"))
                    // /ipwl add <player> <ip>
                    .then(Commands.argument("ip", StringArgumentType.greedyString())
                        .executes(context -> addPlayer(context,
                            StringArgumentType.getString(context, "ip"))))))

            .then(Commands.literal("remove")
                .then(Commands.argument("player", StringArgumentType.word())
                    .executes(WhitelistCommands::removePlayer)))

            .then(Commands.literal("list")
                .executes(WhitelistCommands::listWhitelist))

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

            // /ipwl admin add|remove|list
            .then(Commands.literal("admin")
                .then(Commands.literal("add")
                    .then(Commands.argument("username", StringArgumentType.word())
                        .executes(context -> modifyAdmin(context, true))))
                .then(Commands.literal("remove")
                    .then(Commands.argument("username", StringArgumentType.word())
                        .executes(context -> modifyAdmin(context, false))))
                .then(Commands.literal("list")
                    .executes(WhitelistCommands::listAdmins)))

            // /ipwl logs verbose|silent
            .then(Commands.literal("logs")
                .then(Commands.literal("verbose")
                    .executes(context -> setLogging(context, true)))
                .then(Commands.literal("silent")
                    .executes(context -> setLogging(context, false))))
        );

        // /lockdown on|off  (top-level panic button)
        dispatcher.register(Commands.literal("lockdown")
            .requires(IPWLMod::hasPermission)
            .then(Commands.literal("on")
                .executes(context -> setLockdown(context, true)))
            .then(Commands.literal("off")
                .executes(context -> setLockdown(context, false)))
        );

        // /seen <player>
        dispatcher.register(Commands.literal("seen")
            .requires(IPWLMod::hasPermission)
            .then(Commands.argument("player", StringArgumentType.word())
                .executes(context -> {
                    String player = StringArgumentType.getString(context, "player");
                    return SecurityCommands.handleSeen(context.getSource(), player);
                }))
        );

        // /connections
        dispatcher.register(Commands.literal("connections")
            .requires(IPWLMod::hasPermission)
            .executes(SecurityCommands::handleConnections)
        );
    }

    private static int addPlayer(CommandContext<CommandSourceStack> context, String ip) {
        String playerName = StringArgumentType.getString(context, "player");

        if (ip == null || ip.isEmpty()) {
            ip = "*";
        }

        IPWLMod.getWhitelistManager().addPlayer(playerName, ip);

        if (ip.equals("*")) {
            IPWLMod.sendFeedback(context.getSource(),
                String.format("§aAdded §f%s §ato the whitelist §7(any IP allowed)", playerName));
        } else {
            IPWLMod.sendFeedback(context.getSource(),
                String.format("§aAdded §f%s §ato the whitelist with IP §f%s", playerName, ip));
        }
        return 1;
    }

    private static int removePlayer(CommandContext<CommandSourceStack> context) {
        String playerName = StringArgumentType.getString(context, "player");

        if (!IPWLMod.getWhitelistManager().hasPlayer(playerName)) {
            IPWLMod.sendFeedback(context.getSource(), "§c" + playerName + " is not on the whitelist.");
            return 0;
        }

        IPWLMod.getWhitelistManager().removePlayer(playerName);
        IPWLMod.sendFeedback(context.getSource(), "§aRemoved §f" + playerName + " §afrom the whitelist.");

        if (IPWLMod.getConfig().isKickOnWhitelistRemoval()) {
            ServerPlayer target = context.getSource().getServer().getPlayerList().getPlayerByName(playerName);
            if (target != null) {
                target.connection.disconnect(IPWLMod.disconnectMessage("§cYou have been removed from the whitelist."));
            }
        }
        return 1;
    }

    private static int listWhitelist(CommandContext<CommandSourceStack> context) {
        for (Component line : IPWLMod.getWhitelistManager().getFormattedList()) {
            context.getSource().sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> context) {
        IPWLMod.reloadConfig();
        IPWLMod.getWhitelistManager().reload();
        IPWLMod.sendFeedback(context.getSource(), "§aConfiguration and whitelist reloaded.");
        return 1;
    }

    private static int addIpToPlayer(CommandContext<CommandSourceStack> context) {
        String playerName = StringArgumentType.getString(context, "player");
        String ip = StringArgumentType.getString(context, "ip");

        if (!IPWLMod.getWhitelistManager().hasPlayer(playerName)) {
            IPWLMod.sendFeedback(context.getSource(), "§c" + playerName + " is not on the whitelist.");
            return 0;
        }

        IPWLMod.getWhitelistManager().addIpToPlayer(playerName, ip);
        IPWLMod.sendFeedback(context.getSource(),
            String.format("§aAdded IP §f%s §ato §f%s§a's allowed IPs.", ip, playerName));
        return 1;
    }

    private static int removeIpFromPlayer(CommandContext<CommandSourceStack> context) {
        String playerName = StringArgumentType.getString(context, "player");
        String ip = StringArgumentType.getString(context, "ip");

        Set<String> ips = IPWLMod.getWhitelistManager().getPlayerIps(playerName);
        if (!ips.contains(ip)) {
            IPWLMod.sendFeedback(context.getSource(),
                String.format("§c%s doesn't have IP %s whitelisted.", playerName, ip));
            return 0;
        }

        IPWLMod.getWhitelistManager().removeIpFromPlayer(playerName, ip);
        IPWLMod.sendFeedback(context.getSource(),
            String.format("§aRemoved IP §f%s §afrom §f%s§a's allowed IPs.", ip, playerName));
        return 1;
    }

    private static int modifyAdmin(CommandContext<CommandSourceStack> context, boolean add) {
        String username = StringArgumentType.getString(context, "username");
        if (add) {
            IPWLMod.getConfig().addAdmin(username);
            IPWLMod.sendFeedback(context.getSource(),
                "§f" + username + " §acan now use IPWL commands without OP.");
        } else {
            IPWLMod.getConfig().removeAdmin(username);
            IPWLMod.sendFeedback(context.getSource(),
                "§aRevoked IPWL access from §f" + username + "§a.");
        }
        return 1;
    }

    private static int listAdmins(CommandContext<CommandSourceStack> context) {
        Set<String> admins = IPWLMod.getConfig().getAdmins();
        if (admins.isEmpty()) {
            IPWLMod.sendFeedback(context.getSource(), "§7No IPWL admins configured.");
            return 1;
        }
        IPWLMod.sendFeedback(context.getSource(), "§6=== IPWL Admins ===");
        for (String admin : admins) {
            IPWLMod.sendFeedback(context.getSource(), "§a- " + admin);
        }
        return 1;
    }

    private static int setLogging(CommandContext<CommandSourceStack> context, boolean verbose) {
        IPWLMod.getConfig().setVerboseLogging(verbose);
        if (verbose) {
            IPWLMod.sendFeedback(context.getSource(),
                "§aVerbose logging §2ON §7— all attempts, retries, and debug info will be shown.");
        } else {
            IPWLMod.sendFeedback(context.getSource(),
                "§aSilent logging §2ON §7— only verified logins and kicks will be shown.");
        }
        return 1;
    }

    private static int setLockdown(CommandContext<CommandSourceStack> context, boolean enable) {
        SecurityManager security = IPWLMod.getSecurityManager();
        if (security == null) {
            IPWLMod.sendFeedback(context.getSource(), "§cSecurity manager not ready.");
            return 0;
        }
        security.setLockdownMode(enable);
        if (enable) {
            IPWLMod.sendFeedback(context.getSource(),
                "§c⚠ LOCKDOWN ENABLED §7— no new players can join until you run §f/lockdown off§7.");
        } else {
            IPWLMod.sendFeedback(context.getSource(),
                "§aLockdown lifted §7— server is accepting connections normally.");
        }
        return 1;
    }

    static String getPlayerIp(ServerPlayer player) {
        if (player.connection.getRemoteAddress() instanceof InetSocketAddress addr) {
            return addr.getAddress().getHostAddress();
        }
        return "unknown";
    }
}