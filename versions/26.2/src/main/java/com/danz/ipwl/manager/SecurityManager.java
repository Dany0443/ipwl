package com.danz.ipwl.manager;

import com.danz.ipwl.IPWLMod;
import com.danz.ipwl.config.IPWLMessages;
import com.google.gson.*;
import net.minecraft.network.chat.Component;

import java.io.File;
import net.fabricmc.loader.api.FabricLoader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class SecurityManager {
    private final Map<String, RateLimitData>      rateLimitMap      = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger>       connectionCounts  = new ConcurrentHashMap<>();
    private final Set<String>                      tempBannedIps     = ConcurrentHashMap.newKeySet();

    /**
     * Bruteforce tracking: ip -> (username_lower -> first-attempt-timestamp).
     * If an IP tries {@link #BRUTEFORCE_NAME_THRESHOLD} or more distinct names within
     * {@link #BRUTEFORCE_WINDOW_MS} it is banned for {@link #BRUTEFORCE_BAN_MS}.
     */
    private final Map<String, Map<String, Long>> ipUsernameWindow = new ConcurrentHashMap<>();

    private static final long BRUTEFORCE_WINDOW_MS      = 60_000L;    // 1 minute sliding window
    private static final int  BRUTEFORCE_NAME_THRESHOLD = 3;           // 3 distinct names = ban
    private static final long BRUTEFORCE_BAN_MS         = 3_600_000L;  // 1 hour

    private final File statsFile;
    private SecurityStats stats = new SecurityStats();
    private volatile boolean lockdownMode = false;

    public SecurityManager() {
        statsFile = FabricLoader.getInstance().getConfigDir().resolve("ipwl-stats.json").toFile();
        loadStats();
    }

    // -------------------------------------------------------------------------
    // Stats
    // -------------------------------------------------------------------------

    public void recordAllowedConnection()  { stats.sessionAllowed.incrementAndGet();      stats.totalAllowed.incrementAndGet(); }
    public void recordBlockedConnection()  { stats.sessionBlocked.incrementAndGet();      stats.totalBlocked.incrementAndGet(); }
    public void recordDuplicateAttempt()   { stats.sessionDuplicates.incrementAndGet();   stats.totalDuplicates.incrementAndGet(); }

    // -------------------------------------------------------------------------
    // Bruteforce detection
    // -------------------------------------------------------------------------

    /**
     * Call once per login attempt (before whitelist check).
     * Returns {@code true} if the attempt is clean.
     * Returns {@code false} and applies a 1-hour ban if the IP has tried
     * {@link #BRUTEFORCE_NAME_THRESHOLD}+ distinct usernames within the last minute.
     */
    public boolean checkBruteForce(String ip, String username) {
        Map<String, Long> attempts = ipUsernameWindow.computeIfAbsent(ip, k -> new ConcurrentHashMap<>());
        long now = System.currentTimeMillis();

        // Evict entries outside the sliding window
        attempts.entrySet().removeIf(e -> now - e.getValue() > BRUTEFORCE_WINDOW_MS);

        // Record this name (overwriting keeps the latest timestamp, count stays stable)
        attempts.put(username.toLowerCase(), now);

        if (attempts.size() >= BRUTEFORCE_NAME_THRESHOLD) {
            String names = String.join(", ", attempts.keySet());
            IPWLMod.LOGGER.warn(
                "[IPWL SECURITY] !! BRUTEFORCE DETECTED !! IP {} tried {} different names in {}s: [{}] — banned for 1 hour",
                ip, attempts.size(), BRUTEFORCE_WINDOW_MS / 1000, names);
            applyTempBan(ip, BRUTEFORCE_BAN_MS, "bruteforce (" + attempts.size() + " names in 1 min)");
            ipUsernameWindow.remove(ip); // clear so the ban-expiry is a clean slate
            return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Rate limiting  (single-name hammering → 5-min ban)
    // -------------------------------------------------------------------------

    public boolean checkRateLimit(String ip) {
        if (!IPWLMod.getConfig().isEnableRateLimit()) return true;

        RateLimitData data = rateLimitMap.computeIfAbsent(ip, k -> new RateLimitData());
        if (!data.allowConnection()) {
            stats.sessionRateLimitHits.incrementAndGet();
            stats.totalRateLimitHits.incrementAndGet();

            if (data.failures.incrementAndGet() >= IPWLMod.getConfig().getMaxFailuresBeforeTempBan()) {
                // Single-name repeated hammering → short ban (default 5 min, configurable)
                applyTempBan(ip, IPWLMod.getConfig().getTempBanDurationMs(),
                    "rate limit (" + data.failures.get() + " rapid attempts)");
            }
            return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Connection counting
    // -------------------------------------------------------------------------

    public boolean addConnection(String ip) {
        int max = IPWLMod.getConfig().getMaxConnectionsPerIp();
        if (max <= 0) return true;
        AtomicInteger count = connectionCounts.computeIfAbsent(ip, k -> new AtomicInteger(0));
        if (count.incrementAndGet() > max) { count.decrementAndGet(); return false; }
        return true;
    }

    public void removeConnection(String ip) {
        AtomicInteger count = connectionCounts.get(ip);
        if (count != null && count.decrementAndGet() <= 0) connectionCounts.remove(ip);
    }

    // -------------------------------------------------------------------------
    // Temp bans
    // -------------------------------------------------------------------------

    /**
     * Apply a temp ban for {@code durationMs} milliseconds.
     * Logs a clear reason string so the console admin knows why the ban was applied.
     */
    private void applyTempBan(String ip, long durationMs, String reason) {
        if (tempBannedIps.add(ip)) { // only log + schedule once if not already banned
            long minutes = durationMs / 60_000;
            IPWLMod.LOGGER.warn("[IPWL SECURITY] IP {} temp-banned for {}min — reason: {}", ip, minutes, reason);
            new Timer(true).schedule(new TimerTask() {
                @Override public void run() {
                    tempBannedIps.remove(ip);
                    rateLimitMap.remove(ip);
                    ipUsernameWindow.remove(ip);
                    IPWLMod.LOGGER.info("[IPWL SECURITY] Temp ban expired for {}", ip);
                }
            }, durationMs);
        }
    }

    public boolean isTempBanned(String ip) { return tempBannedIps.contains(ip); }

    // -------------------------------------------------------------------------
    // Lockdown
    // -------------------------------------------------------------------------

    public boolean isLockdownMode() { return lockdownMode; }

    public void setLockdownMode(boolean enabled) {
        this.lockdownMode = enabled;
        if (enabled) IPWLMod.LOGGER.warn("[IPWL SECURITY] LOCKDOWN MODE ENABLED — only admins can join.");
        else         IPWLMod.LOGGER.info("[IPWL SECURITY] Lockdown mode disabled. Normal joining resumed.");
    }

    // -------------------------------------------------------------------------
    // Status report
    // -------------------------------------------------------------------------

    public List<Component> getStatus() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(IPWLMessages.get("ipwl.status.header")));
        lines.add(Component.literal(IPWLMessages.fmt("ipwl.status.lockdown",
            lockdownMode ? "§cENABLED" : "§aDISABLED")));
        lines.add(Component.literal(IPWLMessages.fmt("ipwl.status.connections",  connectionCounts.size())));
        lines.add(Component.literal(IPWLMessages.fmt("ipwl.status.temp_bans",    tempBannedIps.size())));
        lines.add(Component.literal(IPWLMessages.get("ipwl.status.session_header")));
        lines.add(Component.literal(IPWLMessages.fmt("ipwl.status.allowed",      stats.sessionAllowed.get())));
        lines.add(Component.literal(IPWLMessages.fmt("ipwl.status.blocked",      stats.sessionBlocked.get())));
        lines.add(Component.literal(IPWLMessages.fmt("ipwl.status.dupes",        stats.sessionDuplicates.get())));
        lines.add(Component.literal(IPWLMessages.fmt("ipwl.status.ratelimit",    stats.sessionRateLimitHits.get())));
        lines.add(Component.literal(IPWLMessages.get("ipwl.status.alltime_header")));
        lines.add(Component.literal(IPWLMessages.fmt("ipwl.status.allowed",      stats.totalAllowed.get())));
        lines.add(Component.literal(IPWLMessages.fmt("ipwl.status.blocked",      stats.totalBlocked.get())));
        return lines;
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private void loadStats() {
        if (!statsFile.exists()) return;
        try (FileReader reader = new FileReader(statsFile)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            if (json.has("totalAllowed"))       stats.totalAllowed.set(json.get("totalAllowed").getAsInt());
            if (json.has("totalBlocked"))       stats.totalBlocked.set(json.get("totalBlocked").getAsInt());
            if (json.has("totalDuplicates"))    stats.totalDuplicates.set(json.get("totalDuplicates").getAsInt());
            if (json.has("totalRateLimitHits")) stats.totalRateLimitHits.set(json.get("totalRateLimitHits").getAsInt());
        } catch (Exception e) {
            IPWLMod.LOGGER.error("Failed to load security stats", e);
        }
    }

    public void saveStats() {
        try (FileWriter writer = new FileWriter(statsFile)) {
            JsonObject json = new JsonObject();
            json.addProperty("totalAllowed",       stats.totalAllowed.get());
            json.addProperty("totalBlocked",       stats.totalBlocked.get());
            json.addProperty("totalDuplicates",    stats.totalDuplicates.get());
            json.addProperty("totalRateLimitHits", stats.totalRateLimitHits.get());
            new GsonBuilder().setPrettyPrinting().create().toJson(json, writer);
        } catch (IOException e) {
            IPWLMod.LOGGER.error("Failed to save security stats", e);
        }
    }

    public void shutdown() {
        saveStats();
        IPWLMod.LOGGER.info("Security manager shutdown, statistics saved");
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    private static class RateLimitData {
        private final AtomicLong    lastConnection = new AtomicLong(0);
        private final AtomicLong    lastAccess     = new AtomicLong(System.currentTimeMillis());
        private final AtomicInteger failures       = new AtomicInteger(0);

        public boolean allowConnection() {
            long now = System.currentTimeMillis();
            lastAccess.set(now);
            long last = lastConnection.get();
            if (now - last < 1000) return false;
            lastConnection.set(now);
            return true;
        }
    }

    private static class SecurityStats {
        public final AtomicInteger sessionAllowed      = new AtomicInteger(0);
        public final AtomicInteger sessionBlocked      = new AtomicInteger(0);
        public final AtomicInteger sessionDuplicates   = new AtomicInteger(0);
        public final AtomicInteger sessionRateLimitHits = new AtomicInteger(0);

        public final AtomicInteger totalAllowed        = new AtomicInteger(0);
        public final AtomicInteger totalBlocked        = new AtomicInteger(0);
        public final AtomicInteger totalDuplicates     = new AtomicInteger(0);
        public final AtomicInteger totalRateLimitHits  = new AtomicInteger(0);
    }
}