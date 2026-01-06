package com.danz.fabric.ipwl.manager;

import com.danz.fabric.ipwl.IPWLMod;
import com.google.gson.*;
import net.minecraft.text.Text;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class SecurityManager {
    // Rate limiting and connection tracking
    private final Map<String, RateLimitData> rateLimitMap = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> connectionCounts = new ConcurrentHashMap<>();
    private final Set<String> tempBannedIps = ConcurrentHashMap.newKeySet();
    
    // Persistent statistics
    private final File statsFile;
    private SecurityStats stats = new SecurityStats();
    
    // Lockdown mode
    private volatile boolean lockdownMode = false;
    
    // Configuration
    private int maxConnectionsPerIp = 2;
    private long rateLimitWindow = 1000; // 1 second
    private int maxFailuresBeforeTempBan = 5;
    private long tempBanDuration = 300000; // 5 minutes
    
    public SecurityManager() {
        File configDir = IPWLMod.getServer() != null ? 
            IPWLMod.getServer().getRunDirectory().toFile() : new File(".");
        statsFile = new File(configDir, "ipwl-stats.json");
        loadStats();
    }
    
    public void initialize() {
        // Clean up old rate limit data periodically
        Timer cleanupTimer = new Timer("IPWL-Cleanup", true);
        cleanupTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                cleanupOldData();
                saveStats(); // Periodic save
            }
        }, 60000, 60000); // Every minute
        
        IPWLMod.LOGGER.info("Security manager initialized with persistent statistics");
    }
    
    public boolean checkConnection(String ip, String username) {
        if (lockdownMode) {
            stats.sessionBlocked.incrementAndGet();
            stats.totalBlocked.incrementAndGet();
            IPWLMod.LOGGER.warn("[SECURITY] Connection rejected (lockdown): {} from {}", username, ip);
            return false;
        }
        
        // Check if IP is temp banned
        if (tempBannedIps.contains(ip)) {
            stats.sessionBlocked.incrementAndGet();
            stats.totalBlocked.incrementAndGet();
            IPWLMod.LOGGER.warn("[SECURITY] Connection rejected (temp ban): {} from {}", username, ip);
            return false;
        }
        
        // Check connection count per IP
        int connections = connectionCounts.computeIfAbsent(ip, k -> new AtomicInteger(0)).get();
        if (connections >= maxConnectionsPerIp) {
            stats.sessionBlocked.incrementAndGet();
            stats.totalBlocked.incrementAndGet();
            IPWLMod.LOGGER.warn("[SECURITY] Connection rejected (max connections): {} from {}", username, ip);
            return false;
        }
        
        // Check rate limit
        RateLimitData data = rateLimitMap.computeIfAbsent(ip, k -> new RateLimitData());
        if (!data.allowConnection()) {
            stats.sessionRateLimitHits.incrementAndGet();
            stats.totalRateLimitHits.incrementAndGet();
            data.failures.incrementAndGet();
            
            // Check for temp ban threshold
            if (data.failures.get() >= maxFailuresBeforeTempBan) {
                tempBanIp(ip, tempBanDuration);
                IPWLMod.LOGGER.warn("[SECURITY] IP temp banned due to excessive failures: {}", ip);
            }
            
            IPWLMod.LOGGER.warn("[SECURITY] Connection rejected (rate limit): {} from {}", username, ip);
            return false;
        }
        
        return true;
    }
    
    public void onConnectionAccepted(String ip) {
        connectionCounts.computeIfAbsent(ip, k -> new AtomicInteger(0)).incrementAndGet();
        stats.sessionAllowed.incrementAndGet();
        stats.totalAllowed.incrementAndGet();
        
        RateLimitData data = rateLimitMap.get(ip);
        if (data != null) {
            data.failures.set(0); // Reset failures on successful connection
        }
    }
    
    public void onConnectionClosed(String ip) {
        AtomicInteger count = connectionCounts.get(ip);
        if (count != null) {
            int newCount = count.decrementAndGet();
            if (newCount <= 0) {
                connectionCounts.remove(ip);
            }
        }
    }
    
    public void incrementDuplicateAttempts() {
        stats.sessionDuplicates.incrementAndGet();
        stats.totalDuplicates.incrementAndGet();
    }
    
    public void incrementBlockedConnections() {
        stats.sessionBlocked.incrementAndGet();
        stats.totalBlocked.incrementAndGet();
    }
    
    private void tempBanIp(String ip, long duration) {
        tempBannedIps.add(ip);
        Timer tempBanTimer = new Timer("IPWL-TempBan-" + ip.replace(".", "_"), true);
        tempBanTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                tempBannedIps.remove(ip);
                IPWLMod.LOGGER.info("[SECURITY] Temp ban lifted for IP: {}", ip);
                tempBanTimer.cancel();
            }
        }, duration);
    }
    
    private void cleanupOldData() {
        long now = System.currentTimeMillis();
        int removedEntries = 0;
        
        Iterator<Map.Entry<String, RateLimitData>> iterator = rateLimitMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, RateLimitData> entry = iterator.next();
            if (now - entry.getValue().lastAccess.get() > 300000) { // 5 minutes
                iterator.remove();
                removedEntries++;
            }
        }
        
        if (removedEntries > 0) {
            IPWLMod.LOGGER.debug("Cleaned up {} old rate limit entries", removedEntries);
        }
    }
    
    public void setLockdownMode(boolean enabled) {
        this.lockdownMode = enabled;
        if (enabled) {
            IPWLMod.LOGGER.warn("LOCKDOWN MODE ACTIVATED - All new connections will be rejected");
        } else {
            IPWLMod.LOGGER.info("Lockdown mode deactivated");
        }
        saveStats();
    }
    
    public boolean isLockdownMode() {
        return lockdownMode;
    }
    
    public List<Text> getStatus() {
        List<Text> status = new ArrayList<>();
        
        status.add(Text.literal("§6=== Security Status ==="));
        status.add(Text.literal(String.format("§7Lockdown Mode: %s", 
            lockdownMode ? "§cACTIVE" : "§aInactive")));
        status.add(Text.literal(""));
        status.add(Text.literal("§e--- Session Stats ---"));
        status.add(Text.literal(String.format("§7Allowed Connections: §a%d", 
            stats.sessionAllowed.get())));
        status.add(Text.literal(String.format("§7Blocked Connections: §c%d", 
            stats.sessionBlocked.get())));
        status.add(Text.literal(String.format("§7Duplicate Attempts: §e%d", 
            stats.sessionDuplicates.get())));
        status.add(Text.literal(String.format("§7Rate Limit Hits: §e%d", 
            stats.sessionRateLimitHits.get())));
        status.add(Text.literal(""));
        status.add(Text.literal("§6--- All-Time Stats ---"));
        status.add(Text.literal(String.format("§7Total Allowed: §a%d", 
            stats.totalAllowed.get())));
        status.add(Text.literal(String.format("§7Total Blocked: §c%d", 
            stats.totalBlocked.get())));
        status.add(Text.literal(String.format("§7Total Duplicates: §e%d", 
            stats.totalDuplicates.get())));
        status.add(Text.literal(String.format("§7Total Rate Limits: §e%d", 
            stats.totalRateLimitHits.get())));
        status.add(Text.literal(""));
        status.add(Text.literal("§b--- Current State ---"));
        status.add(Text.literal(String.format("§7Active IP Connections: §b%d", 
            connectionCounts.size())));
        status.add(Text.literal(String.format("§7Temp Banned IPs: §c%d", 
            tempBannedIps.size())));
        
        return status;
    }
    
    public void resetStats() {
        stats = new SecurityStats();
        saveStats();
        IPWLMod.LOGGER.info("Security statistics reset");
    }
    
    public void resetSessionStats() {
        stats.sessionAllowed.set(0);
        stats.sessionBlocked.set(0);
        stats.sessionDuplicates.set(0);
        stats.sessionRateLimitHits.set(0);
        IPWLMod.LOGGER.info("Session statistics reset");
    }
    
    private void loadStats() {
        if (!statsFile.exists()) {
            saveStats(); // Create default file
            return;
        }
        
        try (FileReader reader = new FileReader(statsFile)) {
            Gson gson = new Gson();
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            
            if (json.has("totalAllowed")) {
                stats.totalAllowed.set(json.get("totalAllowed").getAsInt());
            }
            if (json.has("totalBlocked")) {
                stats.totalBlocked.set(json.get("totalBlocked").getAsInt());
            }
            if (json.has("totalDuplicates")) {
                stats.totalDuplicates.set(json.get("totalDuplicates").getAsInt());
            }
            if (json.has("totalRateLimitHits")) {
                stats.totalRateLimitHits.set(json.get("totalRateLimitHits").getAsInt());
            }
            
            IPWLMod.LOGGER.info("Security statistics loaded from file");
        } catch (Exception e) {
            IPWLMod.LOGGER.error("Failed to load security statistics", e);
        }
    }
    
    private void saveStats() {
        try (FileWriter writer = new FileWriter(statsFile)) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            JsonObject json = new JsonObject();
            
            json.addProperty("totalAllowed", stats.totalAllowed.get());
            json.addProperty("totalBlocked", stats.totalBlocked.get());
            json.addProperty("totalDuplicates", stats.totalDuplicates.get());
            json.addProperty("totalRateLimitHits", stats.totalRateLimitHits.get());
            json.addProperty("lastUpdated", System.currentTimeMillis());
            
            gson.toJson(json, writer);
        } catch (IOException e) {
            IPWLMod.LOGGER.error("Failed to save security statistics", e);
        }
    }
    
    // Called on server shutdown
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
            if (now - last < 1000) { // 1 second rate limit
                return false;
            }
            
            lastConnection.set(now);
            return true;
        }
    }
    
    private static class SecurityStats {
        // Session stats (reset on server restart)
        public final AtomicInteger sessionAllowed = new AtomicInteger(0);
        public final AtomicInteger sessionBlocked = new AtomicInteger(0);
        public final AtomicInteger sessionDuplicates = new AtomicInteger(0);
        public final AtomicInteger sessionRateLimitHits = new AtomicInteger(0);
        
        // Persistent stats (saved to disk)
        public final AtomicInteger totalAllowed = new AtomicInteger(0);
        public final AtomicInteger totalBlocked = new AtomicInteger(0);
        public final AtomicInteger totalDuplicates = new AtomicInteger(0);
        public final AtomicInteger totalRateLimitHits = new AtomicInteger(0);
    }
}