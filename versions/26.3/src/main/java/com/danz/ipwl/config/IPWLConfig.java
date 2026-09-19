package com.danz.ipwl.config;

import com.google.gson.*;
import com.danz.ipwl.IPWLMod;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class IPWLConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File("config/ipwl.json");

    private int maxConnectionsPerIp = 2;
    private long rateLimitWindowMs = 1000;
    private int maxFailuresBeforeTempBan = 5;
    private long tempBanDurationMs = 300000;
    private boolean enableRateLimit = true;
    private boolean enableDuplicateCheck = true;
    private boolean logAllAttempts = false;
    private boolean kickOnWhitelistRemoval = true;
    private boolean allowWildcardIps = true;
    private boolean allowSubnetPatterns = true;
    private boolean verboseLogging = false;

    // --- Unknown-player join alerts ---
    /** Send clickable alert to admins when an unknown player tries to join. */
    private boolean enableJoinAlerts = true;
    /** Minimum milliseconds between alerts for the same IP (anti-spam). */
    private long alertCooldownMs = 30000;

    // --- Persistent banned IPs (permanent bans via /ipwl banip) ---
    private Set<String> bannedIps = new HashSet<>();

    private Set<String> admins = new HashSet<>();

    public static IPWLConfig load() {
        if (!CONFIG_FILE.exists()) {
            CONFIG_FILE.getParentFile().mkdirs();
            IPWLConfig config = new IPWLConfig();
            config.save();
            return config;
        }

        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            IPWLConfig loaded = GSON.fromJson(reader, IPWLConfig.class);
            // Null-guard collections in case the JSON predates these fields
            if (loaded.admins    == null) loaded.admins    = new HashSet<>();
            if (loaded.bannedIps == null) loaded.bannedIps = new HashSet<>();
            return loaded;
        } catch (IOException e) {
            IPWLMod.LOGGER.error("Failed to load configuration", e);
            return new IPWLConfig();
        }
    }

    public void save() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            IPWLMod.LOGGER.error("Failed to save configuration", e);
        }
    }

    // --- Admins ---
    public boolean isAdmin(String username) { return admins.contains(username); }
    public void addAdmin(String username)    { admins.add(username); save(); }
    public void removeAdmin(String username) { admins.remove(username); save(); }
    public Set<String> getAdmins()           { return new HashSet<>(admins); }

    // --- Permanent IP bans ---
    public boolean isBannedIp(String ip)  { return bannedIps.contains(ip); }
    public void banIp(String ip)          { bannedIps.add(ip); save(); }
    public void unbanIp(String ip)        { bannedIps.remove(ip); save(); }
    public Set<String> getBannedIps()     { return new HashSet<>(bannedIps); }

    // --- Logging ---
    public boolean isVerboseLogging()              { return verboseLogging; }
    public void setVerboseLogging(boolean verbose) { this.verboseLogging = verbose; save(); }

    // --- Alerts ---
    public boolean isEnableJoinAlerts() { return enableJoinAlerts; }
    public long    getAlertCooldownMs() { return alertCooldownMs; }

    // --- Other getters ---
    public int     getMaxConnectionsPerIp()      { return maxConnectionsPerIp; }
    public long    getRateLimitWindowMs()         { return rateLimitWindowMs; }
    public int     getMaxFailuresBeforeTempBan()  { return maxFailuresBeforeTempBan; }
    public long    getTempBanDurationMs()          { return tempBanDurationMs; }
    public boolean isEnableRateLimit()            { return enableRateLimit; }
    public boolean isEnableDuplicateCheck()       { return enableDuplicateCheck; }
    public boolean isLogAllAttempts()             { return logAllAttempts; }
    public boolean isKickOnWhitelistRemoval()     { return kickOnWhitelistRemoval; }
    public boolean isAllowWildcardIps()           { return allowWildcardIps; }
    public boolean isAllowSubnetPatterns()        { return allowSubnetPatterns; }
}