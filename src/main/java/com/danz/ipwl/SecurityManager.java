package com.danz.ipwl.manager;

import com.danz.ipwl.IPWLMod;
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
    private final Map<String, RateLimitData> rateLimitMap = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> connectionCounts = new ConcurrentHashMap<>();
    private final Set<String> tempBannedIps = ConcurrentHashMap.newKeySet();

    private final File statsFile;
    private SecurityStats stats = new SecurityStats();
    private volatile boolean lockdownMode = false;

    public SecurityManager() {
        // Use FabricLoader config dir — always resolves correctly regardless of server state
        statsFile = FabricLoader.getInstance().getConfigDir().resolve("ipwl-stats.json").toFile();
        loadStats();
    }

    public void recordAllowedConnection() {
        stats.sessionAllowed.incrementAndGet();
        stats.totalAllowed.incrementAndGet();
    }

    public void recordBlockedConnection() {
        stats.sessionBlocked.incrementAndGet();
        stats.totalBlocked.incrementAndGet();
    }

    public void recordDuplicateAttempt() {
        stats.sessionDuplicates.incrementAndGet();
        stats.totalDuplicates.incrementAndGet();
    }

    public boolean checkRateLimit(String ip) {
        if (!IPWLMod.getConfig().isEnableRateLimit()) return true;

        RateLimitData data = rateLimitMap.computeIfAbsent(ip, k -> new RateLimitData());
        if (!data.allowConnection()) {
            stats.sessionRateLimitHits.incrementAndGet();
            stats.totalRateLimitHits.incrementAndGet();

            if (data.failures.incrementAndGet() >= IPWLMod.getConfig().getMaxFailuresBeforeTempBan()) {
                tempBanIp(ip);
            }
            return false;
        }
        return true;
    }

    public boolean addConnection(String ip) {
        int max = IPWLMod.getConfig().getMaxConnectionsPerIp();
        if (max <= 0) return true;

        AtomicInteger count = connectionCounts.computeIfAbsent(ip, k -> new AtomicInteger(0));
        if (count.incrementAndGet() > max) {
            count.decrementAndGet();
            return false;
        }
        return true;
    }

    public void removeConnection(String ip) {
        AtomicInteger count = connectionCounts.get(ip);
        if (count != null) {
            if (count.decrementAndGet() <= 0) {
                connectionCounts.remove(ip);
            }
        }
    }

    private void tempBanIp(String ip) {
        tempBannedIps.add(ip);
        IPWLMod.LOGGER.warn("[IPWL SECURITY] Temporarily banned IP {} for rate limit violations", ip);

        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                tempBannedIps.remove(ip);
                rateLimitMap.remove(ip);
            }
        }, IPWLMod.getConfig().getTempBanDurationMs());
    }

    public boolean isTempBanned(String ip) {
        return tempBannedIps.contains(ip);
    }

    public boolean isLockdownMode() {
        return lockdownMode;
    }

    public void setLockdownMode(boolean enabled) {
        this.lockdownMode = enabled;
        if (enabled) {
            IPWLMod.LOGGER.warn("[IPWL SECURITY] LOCKDOWN MODE ENABLED! Only admins can join.");
        } else {
            IPWLMod.LOGGER.info("[IPWL SECURITY] Lockdown mode disabled. Normal joining resumed.");
        }
    }

    public List<Component> getStatus() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("§6=== IPWL Security Status ==="));
        lines.add(Component.literal("§7Lockdown Mode: " + (lockdownMode ? "§cENABLED" : "§aDISABLED")));
        lines.add(Component.literal("§7Active Connections Tracked: §f" + connectionCounts.size()));
        lines.add(Component.literal("§7Active Temp Bans: §f" + tempBannedIps.size()));

        lines.add(Component.literal("§6--- Session Stats ---"));
        lines.add(Component.literal("§7Allowed: §a" + stats.sessionAllowed.get()));
        lines.add(Component.literal("§7Blocked: §c" + stats.sessionBlocked.get()));
        lines.add(Component.literal("§7Duplicates Blocked: §e" + stats.sessionDuplicates.get()));
        lines.add(Component.literal("§7Rate Limit Hits: §e" + stats.sessionRateLimitHits.get()));

        lines.add(Component.literal("§6--- All Time Stats ---"));
        lines.add(Component.literal("§7Allowed: §a" + stats.totalAllowed.get()));
        lines.add(Component.literal("§7Blocked: §c" + stats.totalBlocked.get()));

        return lines;
    }

    private void loadStats() {
        if (!statsFile.exists()) return;
        try (FileReader reader = new FileReader(statsFile)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            if (json.has("totalAllowed")) stats.totalAllowed.set(json.get("totalAllowed").getAsInt());
            if (json.has("totalBlocked")) stats.totalBlocked.set(json.get("totalBlocked").getAsInt());
            if (json.has("totalDuplicates")) stats.totalDuplicates.set(json.get("totalDuplicates").getAsInt());
            if (json.has("totalRateLimitHits")) stats.totalRateLimitHits.set(json.get("totalRateLimitHits").getAsInt());
        } catch (Exception e) {
            IPWLMod.LOGGER.error("Failed to load security stats", e);
        }
    }

    public void saveStats() {
        try (FileWriter writer = new FileWriter(statsFile)) {
            JsonObject json = new JsonObject();
            json.addProperty("totalAllowed", stats.totalAllowed.get());
            json.addProperty("totalBlocked", stats.totalBlocked.get());
            json.addProperty("totalDuplicates", stats.totalDuplicates.get());
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

    private static class RateLimitData {
        private final AtomicLong lastConnection = new AtomicLong(0);
        private final AtomicLong lastAccess = new AtomicLong(System.currentTimeMillis());
        private final AtomicInteger failures = new AtomicInteger(0);

        public boolean allowConnection() {
            long now = System.currentTimeMillis();
            lastAccess.set(now);

            long last = lastConnection.get();
            if (now - last < 1000) {
                return false;
            }

            lastConnection.set(now);
            return true;
        }
    }

    private static class SecurityStats {
        public final AtomicInteger sessionAllowed = new AtomicInteger(0);
        public final AtomicInteger sessionBlocked = new AtomicInteger(0);
        public final AtomicInteger sessionDuplicates = new AtomicInteger(0);
        public final AtomicInteger sessionRateLimitHits = new AtomicInteger(0);

        public final AtomicInteger totalAllowed = new AtomicInteger(0);
        public final AtomicInteger totalBlocked = new AtomicInteger(0);
        public final AtomicInteger totalDuplicates = new AtomicInteger(0);
        public final AtomicInteger totalRateLimitHits = new AtomicInteger(0);
    }
}