package com.danz.ipwl.commands;

import com.danz.ipwl.IPWLMod;
import com.danz.ipwl.config.IPWLMessages;
import com.google.gson.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.io.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers /seen, /connections, and /security status commands, and tracks
 * per-player last-seen data (written to config/ipwl-seen.json).
 *
 * <p>Called from:
 * <ul>
 *   <li>{@link IPWLMod} — {@code SecurityCommands.register(dispatcher)}</li>
 *   <li>{@link com.danz.ipwl.events.ConnectionEventHandler} —
 *       {@code updatePlayerSeen} / {@code updatePlayerLeft}</li>
 * </ul>
 */
public class SecurityCommands {

    /** username (lowercase) → last-seen record */
    private static final Map<String, PlayerRecord> seenData = new ConcurrentHashMap<>();

    private static final File SEEN_FILE =
            FabricLoader.getInstance().getConfigDir().resolve("ipwl-seen.json").toFile();

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        loadSeenData();

        // /seen <player>
        dispatcher.register(Commands.literal("seen")
            .requires(IPWLMod::hasPermission)
            .then(Commands.argument("player", StringArgumentType.word())
                .executes(ctx -> handleSeen(
                    ctx.getSource(),
                    StringArgumentType.getString(ctx, "player"))))
        );

        // /connections
        dispatcher.register(Commands.literal("connections")
            .requires(IPWLMod::hasPermission)
            .executes(SecurityCommands::handleConnections)
        );

        // /security status
        dispatcher.register(Commands.literal("security")
            .requires(IPWLMod::hasPermission)
            .then(Commands.literal("status")
                .executes(SecurityCommands::handleSecurityStatus))
        );
    }

    // -------------------------------------------------------------------------
    // Command handlers
    // -------------------------------------------------------------------------

    public static int handleSeen(CommandSourceStack source, String playerName) {
        PlayerRecord record = seenData.get(playerName.toLowerCase());
        if (record == null) {
            IPWLMod.sendFeedback(source,
                IPWLMessages.fmt("ipwl.seen.never", playerName));
            return 0;
        }
        String timestamp = FORMATTER.format(Instant.ofEpochMilli(record.lastSeen()));
        String status = record.online()
            ? IPWLMessages.get("ipwl.seen.currently_online")
            : IPWLMessages.fmt("ipwl.seen.last_seen", timestamp);
        IPWLMod.sendFeedback(source,
            IPWLMessages.fmt("ipwl.seen.result", playerName, record.ip(), status));
        return 1;
    }

    public static int handleConnections(CommandContext<CommandSourceStack> ctx) {
        var server = IPWLMod.getServer();
        if (server == null) {
            IPWLMod.sendFeedback(ctx.getSource(),
                IPWLMessages.get("ipwl.cmd.security_not_ready"));
            return 0;
        }
        var players = server.getPlayerList().getPlayers();
        IPWLMod.sendFeedback(ctx.getSource(),
            IPWLMessages.fmt("ipwl.connections.count", players.size()));
        players.forEach(p -> {
            String ip = WhitelistCommands.getPlayerIp(p);
            IPWLMod.sendFeedback(ctx.getSource(),
                IPWLMessages.fmt("ipwl.connections.entry", p.getName().getString(), ip));
        });
        return 1;
    }

    public static int handleSecurityStatus(CommandContext<CommandSourceStack> ctx) {
        var security = IPWLMod.getSecurityManager();
        if (security == null) {
            IPWLMod.sendFeedback(ctx.getSource(),
                IPWLMessages.get("ipwl.cmd.security_not_ready"));
            return 0;
        }
        for (Component line : security.getStatus()) {
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return 1;
    }

    // -------------------------------------------------------------------------
    // Player tracking — called by ConnectionEventHandler
    // -------------------------------------------------------------------------

    public static void updatePlayerSeen(String username, String ip) {
        seenData.put(username.toLowerCase(),
            new PlayerRecord(username, ip, System.currentTimeMillis(), true));
        saveSeenData();
    }

    public static void updatePlayerLeft(String username, String ip) {
        // Update last-seen time and mark offline
        seenData.put(username.toLowerCase(),
            new PlayerRecord(username, ip, System.currentTimeMillis(), false));
        saveSeenData();
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private static void loadSeenData() {
        if (!SEEN_FILE.exists()) return;
        try (FileReader reader = new FileReader(SEEN_FILE)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                JsonObject obj = entry.getValue().getAsJsonObject();
                seenData.put(entry.getKey(), new PlayerRecord(
                    obj.get("username").getAsString(),
                    obj.get("ip").getAsString(),
                    obj.get("lastSeen").getAsLong(),
                    false // treat as offline on startup
                ));
            }
            IPWLMod.LOGGER.info("[IPWL] Loaded seen data for {} players", seenData.size());
        } catch (Exception e) {
            IPWLMod.LOGGER.error("[IPWL] Failed to load seen data: {}", e.getMessage());
        }
    }

    private static void saveSeenData() {
        try (FileWriter writer = new FileWriter(SEEN_FILE)) {
            JsonObject json = new JsonObject();
            seenData.forEach((key, r) -> {
                JsonObject obj = new JsonObject();
                obj.addProperty("username", r.username());
                obj.addProperty("ip",       r.ip());
                obj.addProperty("lastSeen", r.lastSeen());
                obj.addProperty("online",   r.online());
                json.add(key, obj);
            });
            new GsonBuilder().setPrettyPrinting().create().toJson(json, writer);
        } catch (Exception e) {
            IPWLMod.LOGGER.error("[IPWL] Failed to save seen data: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------

    private record PlayerRecord(String username, String ip, long lastSeen, boolean online) {}
}