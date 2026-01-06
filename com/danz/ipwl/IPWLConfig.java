package com.danz.fabric.ipwl.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.danz.fabric.ipwl.IPWLMod;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class IPWLConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File("config/ipwl.json");
    
    // Configuration values
    private int maxConnectionsPerIp = 2;
    private long rateLimitWindowMs = 1000;
    private int maxFailuresBeforeTempBan = 5;
    private long tempBanDurationMs = 300000; // 5 minutes
    private boolean enableRateLimit = true;
    private boolean enableDuplicateCheck = true;
    private boolean logAllAttempts = false;
    private boolean kickOnWhitelistRemoval = true;
    private boolean allowWildcardIps = true;
    private boolean allowSubnetPatterns = true;
    
    // New setting for log verbosity
    private boolean verboseLogging = false;
    
    // List of Mod Admins (Users who can use IPWL commands without being OP)
    private Set<String> admins = new HashSet<>();
    
    public static IPWLConfig load() {
        IPWLConfig config = new IPWLConfig();
        
        if (!CONFIG_FILE.exists()) {
            CONFIG_FILE.getParentFile().mkdirs();
            config.save();
            return config;
        }
        
        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            
            if (json.has("maxConnectionsPerIp")) config.maxConnectionsPerIp = json.get("maxConnectionsPerIp").getAsInt();
            if (json.has("rateLimitWindowMs")) config.rateLimitWindowMs = json.get("rateLimitWindowMs").getAsLong();
            if (json.has("maxFailuresBeforeTempBan")) config.maxFailuresBeforeTempBan = json.get("maxFailuresBeforeTempBan").getAsInt();
            if (json.has("tempBanDurationMs")) config.tempBanDurationMs = json.get("tempBanDurationMs").getAsLong();
            
            if (json.has("enableRateLimit")) config.enableRateLimit = json.get("enableRateLimit").getAsBoolean();
            if (json.has("enableDuplicateCheck")) config.enableDuplicateCheck = json.get("enableDuplicateCheck").getAsBoolean();
            if (json.has("logAllAttempts")) config.logAllAttempts = json.get("logAllAttempts").getAsBoolean();
            if (json.has("kickOnWhitelistRemoval")) config.kickOnWhitelistRemoval = json.get("kickOnWhitelistRemoval").getAsBoolean();
            if (json.has("allowWildcardIps")) config.allowWildcardIps = json.get("allowWildcardIps").getAsBoolean();
            if (json.has("allowSubnetPatterns")) config.allowSubnetPatterns = json.get("allowSubnetPatterns").getAsBoolean();
            
            if (json.has("verboseLogging")) config.verboseLogging = json.get("verboseLogging").getAsBoolean();
            
            // Load Admins
            if (json.has("admins")) {
                JsonArray adminArray = json.getAsJsonArray("admins");
                for (JsonElement element : adminArray) {
                    config.admins.add(element.getAsString());
                }
            }
            
        } catch (Exception e) {
            IPWLMod.LOGGER.error("Failed to load configuration", e);
        }
        
        return config;
    }
    
    public void save() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            JsonObject json = new JsonObject();
            
            json.addProperty("maxConnectionsPerIp", maxConnectionsPerIp);
            json.addProperty("rateLimitWindowMs", rateLimitWindowMs);
            json.addProperty("maxFailuresBeforeTempBan", maxFailuresBeforeTempBan);
            json.addProperty("tempBanDurationMs", tempBanDurationMs);
            
            json.addProperty("enableRateLimit", enableRateLimit);
            json.addProperty("enableDuplicateCheck", enableDuplicateCheck);
            json.addProperty("logAllAttempts", logAllAttempts);
            json.addProperty("kickOnWhitelistRemoval", kickOnWhitelistRemoval);
            json.addProperty("allowWildcardIps", allowWildcardIps);
            json.addProperty("allowSubnetPatterns", allowSubnetPatterns);
            json.addProperty("verboseLogging", verboseLogging);
            
            // Save Admins
            JsonArray adminArray = new JsonArray();
            for (String admin : admins) {
                adminArray.add(admin);
            }
            json.add("admins", adminArray);
            
            json.addProperty("_comment_admins", "List of players who can use IPWL commands without OP");
            json.addProperty("_comment_logs", "verboseLogging: If true, logs all retry attempts. If false, logs only success/fail.");
            
            GSON.toJson(json, writer);
            IPWLMod.LOGGER.info("Configuration saved to ipwl.json");
        } catch (IOException e) {
            IPWLMod.LOGGER.error("Failed to save configuration", e);
        }
    }
    
    // Admin Management Methods
    public boolean isAdmin(String username) {
        return admins.contains(username);
    }
    
    public void addAdmin(String username) {
        admins.add(username);
        save();
    }
    
    public void removeAdmin(String username) {
        admins.remove(username);
        save();
    }
    
    public Set<String> getAdmins() {
        return new HashSet<>(admins);
    }
    
    // Log Settings
    public boolean isVerboseLogging() { return verboseLogging; }
    public void setVerboseLogging(boolean verbose) { 
        this.verboseLogging = verbose; 
        save();
    }
    
    // Getters
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