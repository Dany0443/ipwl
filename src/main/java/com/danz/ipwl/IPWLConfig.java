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

    private Set<String> admins = new HashSet<>();

    public static IPWLConfig load() {
        if (!CONFIG_FILE.exists()) {
            CONFIG_FILE.getParentFile().mkdirs();
            IPWLConfig config = new IPWLConfig();
            config.save();
            return config;
        }

        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            return GSON.fromJson(reader, IPWLConfig.class);
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

    public boolean isAdmin(String username) { return admins.contains(username); }
    public void addAdmin(String username) { admins.add(username); save(); }
    public void removeAdmin(String username) { admins.remove(username); save(); }
    public Set<String> getAdmins() { return new HashSet<>(admins); }
    public boolean isVerboseLogging() { return verboseLogging; }
    public void setVerboseLogging(boolean verbose) { this.verboseLogging = verbose; save(); }
    public int getMaxConnectionsPerIp() { return maxConnectionsPerIp; }
    public long getRateLimitWindowMs() { return rateLimitWindowMs; }
    public int getMaxFailuresBeforeTempBan() { return maxFailuresBeforeTempBan; }
    public long getTempBanDurationMs() { return tempBanDurationMs; }
    public boolean isEnableRateLimit() { return enableRateLimit; }
    public boolean isEnableDuplicateCheck() { return enableDuplicateCheck; }
    public boolean isLogAllAttempts() { return logAllAttempts; }
    public boolean isKickOnWhitelistRemoval() { return kickOnWhitelistRemoval; }
    public boolean isAllowWildcardIps() { return allowWildcardIps; }
    public boolean isAllowSubnetPatterns() { return allowSubnetPatterns; }
}