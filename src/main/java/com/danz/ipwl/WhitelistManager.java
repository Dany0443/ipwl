package com.danz.ipwl.manager;

import com.google.gson.*;
import com.danz.ipwl.IPWLMod;
import com.danz.ipwl.config.IPWLMessages;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class WhitelistManager {

    // username (lower) -> set of IP patterns (exact / wildcard-suffix / CIDR)
    private final Map<String, Set<String>> whitelist = new ConcurrentHashMap<>();

    // username (lower) -> list of temp entries
    private final Map<String, List<TempEntry>> tempApprovals = new ConcurrentHashMap<>();

    private final File whitelistFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /** Background scheduler that cleans up expired temp approvals. */
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ipwl-temp-cleaner");
                t.setDaemon(true);
                return t;
            });

    public WhitelistManager() {
        whitelistFile = FabricLoader.getInstance().getConfigDir()
                .resolve("ipwl_whitelist.json").toFile();

        // Run cleanup every 30 seconds
        scheduler.scheduleAtFixedRate(this::cleanExpiredTempApprovals, 30, 30, TimeUnit.SECONDS);
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    public void load() {
        if (!whitelistFile.exists()) { save(); return; }

        try (FileReader reader = new FileReader(whitelistFile)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            whitelist.clear();

            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                Set<String> ips = new HashSet<>();
                for (JsonElement el : entry.getValue().getAsJsonArray()) {
                    ips.add(el.getAsString());
                }
                whitelist.put(entry.getKey(), ips);
            }
        } catch (IOException e) {
            IPWLMod.LOGGER.error("Failed to load whitelist", e);
        }
    }

    public void save() {
        try (FileWriter writer = new FileWriter(whitelistFile)) {
            JsonObject json = new JsonObject();
            for (Map.Entry<String, Set<String>> entry : whitelist.entrySet()) {
                JsonArray arr = new JsonArray();
                entry.getValue().forEach(arr::add);
                json.add(entry.getKey(), arr);
            }
            gson.toJson(json, writer);
        } catch (IOException e) {
            IPWLMod.LOGGER.error("Failed to save whitelist", e);
        }
    }

    public void reload() {
        load();
        IPWLMod.LOGGER.info("Whitelist reloaded from disk");
    }

    // -------------------------------------------------------------------------
    // Mutation
    // -------------------------------------------------------------------------

    public void addPlayer(String username, String ip) {
        whitelist.computeIfAbsent(username.trim(), k -> new HashSet<>()).add(ip.trim());
        save();
    }

    public void addIpToPlayer(String username, String ip) {
        if (whitelist.containsKey(username.trim())) {
            whitelist.get(username.trim()).add(ip.trim());
            save();
        }
    }

    public void removeIpFromPlayer(String username, String ip) {
        if (whitelist.containsKey(username.trim())) {
            whitelist.get(username.trim()).remove(ip.trim());
            save();
        }
    }

    public void removePlayer(String username) {
        whitelist.remove(username.trim());
        save();
    }

    public boolean hasPlayer(String username) {
        return whitelist.containsKey(username.trim());
    }

    public Set<String> getPlayerIps(String username) {
        return whitelist.getOrDefault(username.trim(), new HashSet<>());
    }

    // -------------------------------------------------------------------------
    // Temporary approvals
    // -------------------------------------------------------------------------

    /**
     * Grant {@code username} access from {@code ip} for {@code durationMs} milliseconds.
     * Does NOT persist to disk — temp approvals live only for the current session
     * (or until they expire).
     */
    public void addTempApproval(String username, String ip, long durationMs) {
        long expiresAt = System.currentTimeMillis() + durationMs;
        tempApprovals.computeIfAbsent(username.toLowerCase(), k -> new ArrayList<>())
                     .add(new TempEntry(ip, expiresAt));

        // Schedule precise removal as well (belt-and-suspenders with the periodic cleanup)
        scheduler.schedule(() -> {
            cleanExpiredTempApprovals();
            // Notify admins
            String msg = IPWLMessages.fmt("ipwl.cmd.tempadd_expired", username, ip);
            notifyAdmins(msg);
        }, durationMs, TimeUnit.MILLISECONDS);

        IPWLMod.LOGGER.info("[IPWL] Temp approval granted: {} from {} for {}ms", username, ip, durationMs);
    }

    private void cleanExpiredTempApprovals() {
        long now = System.currentTimeMillis();
        tempApprovals.forEach((user, list) -> list.removeIf(e -> e.expiresAt <= now));
        tempApprovals.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    private boolean hasTempApproval(String username, String ip) {
        List<TempEntry> entries = tempApprovals.get(username.toLowerCase());
        if (entries == null) return false;
        long now = System.currentTimeMillis();
        return entries.stream().anyMatch(e -> e.expiresAt > now && matchesPattern(ip, e.ip));
    }

    // -------------------------------------------------------------------------
    // IP check — exact / wildcard-suffix / CIDR / temp
    // -------------------------------------------------------------------------

    public WhitelistResult checkPlayerIp(String username, String ip) {
        // Temp approval takes priority (useful for admins granting on-the-fly access)
        if (hasTempApproval(username, ip)) {
            return new WhitelistResult(true, "Temporary approval");
        }

        Set<String> allowedIps = whitelist.get(username);
        if (allowedIps == null) {
            return new WhitelistResult(false, "Player not in whitelist");
        }

        // Wildcard — any IP
        if (allowedIps.contains("*") && IPWLMod.getConfig().isAllowWildcardIps()) {
            return new WhitelistResult(true, "Allowed by wildcard");
        }

        for (String pattern : allowedIps) {
            if (matchesPattern(ip, pattern)) {
                return new WhitelistResult(true, "Matched pattern: " + pattern);
            }
        }

        return new WhitelistResult(false, "IP mismatch. Connection attempted from: " + ip);
    }

    /**
     * Test whether {@code ip} satisfies {@code pattern}.
     * Supported formats:
     * <ul>
     *   <li>Exact:           {@code 203.0.113.5}</li>
     *   <li>Wildcard suffix: {@code 192.168.1.*}</li>
     *   <li>CIDR (v4/v6):   {@code 192.168.1.0/24}, {@code 2001:db8::/32}</li>
     * </ul>
     */
    private boolean matchesPattern(String ip, String pattern) {
        if (pattern.equals(ip)) return true;

        // Legacy wildcard suffix  e.g. "192.168.1.*"
        if (IPWLMod.getConfig().isAllowSubnetPatterns() && pattern.endsWith(".*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            if (ip.startsWith(prefix + ".") || ip.equals(prefix)) return true;
        }

        // CIDR notation
        if (pattern.contains("/")) {
            try {
                return cidrContains(pattern, ip);
            } catch (Exception e) {
                IPWLMod.LOGGER.warn("[IPWL] Invalid CIDR pattern '{}': {}", pattern, e.getMessage());
            }
        }

        return false;
    }

    /**
     * Pure-Java CIDR check — no external libraries required.
     * Works for both IPv4 ({@code x.x.x.x/n}) and IPv6 ({@code x:x:.../n}).
     */
    private static boolean cidrContains(String cidr, String ipStr) throws Exception {
        int slash = cidr.lastIndexOf('/');
        int prefixLen = Integer.parseInt(cidr.substring(slash + 1));
        InetAddress network = InetAddress.getByName(cidr.substring(0, slash));
        InetAddress target  = InetAddress.getByName(ipStr);

        byte[] netBytes = network.getAddress();
        byte[] tgtBytes = target.getAddress();

        if (netBytes.length != tgtBytes.length) return false; // v4 vs v6 mismatch

        int fullBytes = prefixLen / 8;
        int remainder = prefixLen % 8;

        for (int i = 0; i < fullBytes; i++) {
            if (netBytes[i] != tgtBytes[i]) return false;
        }
        if (remainder > 0 && fullBytes < netBytes.length) {
            int mask = (0xFF << (8 - remainder)) & 0xFF;
            if ((netBytes[fullBytes] & mask) != (tgtBytes[fullBytes] & mask)) return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Formatted list
    // -------------------------------------------------------------------------

    public List<Component> getFormattedList() {
        List<Component> lines = new ArrayList<>();
        if (whitelist.isEmpty()) {
            lines.add(Component.literal(IPWLMessages.get("ipwl.cmd.whitelist_empty")));
            return lines;
        }
        lines.add(Component.literal(IPWLMessages.get("ipwl.cmd.whitelist_header")));
        for (Map.Entry<String, Set<String>> entry : whitelist.entrySet()) {
            String username = entry.getKey();
            Set<String> ips = entry.getValue();
            if (ips.contains("*")) {
                lines.add(Component.literal(IPWLMessages.fmt("ipwl.cmd.whitelist_entry_any", username)));
            } else {
                lines.add(Component.literal(
                    IPWLMessages.fmt("ipwl.cmd.whitelist_entry_ips", username, String.join(", ", ips))));
            }
        }
        // Show active temp approvals
        if (!tempApprovals.isEmpty()) {
            lines.add(Component.literal(IPWLMessages.get("ipwl.cmd.tempadd_list_header")));
            long now = System.currentTimeMillis();
            tempApprovals.forEach((user, list) -> {
                for (TempEntry e : list) {
                    if (e.expiresAt > now) {
                        long remaining = (e.expiresAt - now) / 1000;
                        lines.add(Component.literal(
                            IPWLMessages.fmt("ipwl.cmd.tempadd_list_entry", user, e.ip, remaining)));
                    }
                }
            });
        }
        return lines;
    }

    // -------------------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------------------

    public void shutdown() {
        scheduler.shutdownNow();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void notifyAdmins(String message) {
    var server = IPWLMod.getServer();
    if (server == null) return;
    var admins = IPWLMod.getConfig().getAdmins();
    server.getPlayerList().getPlayers().forEach(p -> {
        if (admins.contains(p.getName().getString())) {
            p.sendSystemMessage(Component.literal(message));
        }
    });
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    private record TempEntry(String ip, long expiresAt) {}

    public static class WhitelistResult {
        public final boolean allowed;
        public final String  reason;

        public WhitelistResult(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason  = reason;
        }
    }
}