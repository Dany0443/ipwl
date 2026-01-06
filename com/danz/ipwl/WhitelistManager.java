package com.danz.fabric.ipwl.manager;

import com.google.gson.*;
import com.danz.fabric.ipwl.IPWLMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WhitelistManager {
    private final Map<String, Set<String>> whitelist = new ConcurrentHashMap<>();
    private final File whitelistFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    
    public WhitelistManager() {
        MinecraftServer server = IPWLMod.getServer();
        File configDir = server != null ? server.getRunDirectory().toFile() : new File(".");
        whitelistFile = new File(configDir, "ipwl_whitelist.json");
    }
    
    public void load() {
        if (!whitelistFile.exists()) {
            save(); // Create default file
            return;
        }
        
        try (FileReader reader = new FileReader(whitelistFile)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            whitelist.clear();
            
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                String username = entry.getKey(); // Keep original case
                Set<String> ips = new HashSet<>();
                
                JsonArray ipArray = entry.getValue().getAsJsonArray();
                for (JsonElement element : ipArray) {
                    ips.add(element.getAsString());
                }
                
                whitelist.put(username, ips);
            }
            
            IPWLMod.LOGGER.info("Loaded {} whitelisted players", whitelist.size());
        } catch (Exception e) {
            IPWLMod.LOGGER.error("Failed to load whitelist", e);
        }
    }
    
    public void save() {
        try (FileWriter writer = new FileWriter(whitelistFile)) {
            JsonObject json = new JsonObject();
            
            for (Map.Entry<String, Set<String>> entry : whitelist.entrySet()) {
                JsonArray ipArray = new JsonArray();
                entry.getValue().forEach(ipArray::add);
                json.add(entry.getKey(), ipArray);
            }
            
            gson.toJson(json, writer);
            IPWLMod.LOGGER.info("Saved whitelist with {} entries", whitelist.size());
        } catch (IOException e) {
            IPWLMod.LOGGER.error("Failed to save whitelist", e);
        }
    }
    
    /**
     * Enhanced whitelist checking with detailed logging and retry logic
     */
    public WhitelistResult isWhitelistedDetailed(String username, String ip) {
        if (username == null || ip == null) {
            IPWLMod.LOGGER.warn("[SECURITY] Null username or IP provided - username: {}, ip: {}", username, ip);
            return new WhitelistResult(false, "Null username or IP");
        }
        
        String normalizedUsername = username.trim(); // Only trim whitespace, keep case
        Set<String> allowedIps = whitelist.get(normalizedUsername);
        
        if (allowedIps == null || allowedIps.isEmpty()) {
            IPWLMod.LOGGER.warn("[SECURITY] Blocked {} (unknown username, IP: {})", username, ip);
            return new WhitelistResult(false, "Username not whitelisted");
        }
        
        // If wildcard IP (*) is present, allow any IP for this user
        if (allowedIps.contains("*")) {
            IPWLMod.LOGGER.info("[SECURITY] {} verified successfully (IP: {}) [WILDCARD]", username, ip);
            return new WhitelistResult(true, "Wildcard IP match");
        }
        
        // Check if specific IP is allowed
        if (allowedIps.contains(ip)) {
            IPWLMod.LOGGER.info("[SECURITY] {} verified successfully (IP: {})", username, ip);
            return new WhitelistResult(true, "Exact IP match");
        }
        
        // Check IP patterns (subnet wildcards)
        if (checkIpPattern(allowedIps, ip)) {
            IPWLMod.LOGGER.info("[SECURITY] {} verified successfully (IP: {}) [SUBNET]", username, ip);
            return new WhitelistResult(true, "Subnet pattern match");
        }
        
        IPWLMod.LOGGER.warn("[SECURITY] Blocked {} (IP mismatch - allowed: {}, attempted: {})", 
            username, allowedIps, ip);
        return new WhitelistResult(false, "IP not in whitelist");
    }
    
    /**
     * Legacy method for backwards compatibility
     */
    public boolean isWhitelisted(String username, String ip) {
        return isWhitelistedDetailed(username, ip).allowed;
    }
    
    private boolean checkIpPattern(Set<String> patterns, String ip) {
        for (String pattern : patterns) {
            if (pattern.endsWith(".*")) {
                // Subnet wildcard (e.g., "192.168.1.*")
                String subnet = pattern.substring(0, pattern.length() - 2);
                if (ip.startsWith(subnet)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    public void addPlayer(String username, String ip) {
        String normalizedUsername = username.trim(); // Only trim whitespace, keep case
        whitelist.computeIfAbsent(normalizedUsername, k -> new HashSet<>()).add(ip);
        save();
        IPWLMod.LOGGER.info("Added {} to whitelist with IP {}", username, ip);
    }
    
    public void removePlayer(String username) {
        String normalizedUsername = username.trim(); // Only trim whitespace, keep case
        if (whitelist.remove(normalizedUsername) != null) {
            save();
            IPWLMod.LOGGER.info("Removed {} from whitelist", username);
        }
    }
    
    public void addIpToPlayer(String username, String ip) {
        String normalizedUsername = username.trim(); // Only trim whitespace, keep case
        Set<String> ips = whitelist.get(normalizedUsername);
        if (ips != null) {
            ips.add(ip);
            save();
            IPWLMod.LOGGER.info("Added IP {} to {}'s whitelist", ip, username);
        }
    }
    
    public void removeIpFromPlayer(String username, String ip) {
        String normalizedUsername = username.trim(); // Only trim whitespace, keep case
        Set<String> ips = whitelist.get(normalizedUsername);
        if (ips != null) {
            ips.remove(ip);
            if (ips.isEmpty()) {
                whitelist.remove(normalizedUsername);
                IPWLMod.LOGGER.info("Removed {} from whitelist (no IPs remaining)", username);
            }
            save();
            IPWLMod.LOGGER.info("Removed IP {} from {}'s whitelist", ip, username);
        }
    }
    
    public Map<String, Set<String>> getWhitelist() {
        return new HashMap<>(whitelist);
    }
    
    public Set<String> getPlayerIps(String username) {
        return whitelist.getOrDefault(username.trim(), new HashSet<>());
    }
    
    public void reload() {
        load();
        IPWLMod.LOGGER.info("Whitelist reloaded from disk");
    }
    
    public boolean hasPlayer(String username) {
        return whitelist.containsKey(username.trim());
    }
    
    public List<Text> getFormattedList() {
        List<Text> lines = new ArrayList<>();
        
        if (whitelist.isEmpty()) {
            lines.add(Text.literal("§7No players whitelisted"));
            return lines;
        }
        
        lines.add(Text.literal("§6=== IP Whitelist ==="));
        for (Map.Entry<String, Set<String>> entry : whitelist.entrySet()) {
            String username = entry.getKey();
            Set<String> ips = entry.getValue();
            
            if (ips.contains("*")) {
                lines.add(Text.literal(String.format("§a%s §7→ §e[Any IP]", username)));
            } else {
                lines.add(Text.literal(String.format("§a%s §7→ §f%s", 
                    username, String.join(", ", ips))));
            }
        }
        
        return lines;
    }
    
    /**
     * Result class for detailed whitelist checking
     */
    public static class WhitelistResult {
        public final boolean allowed;
        public final String reason;
        
        public WhitelistResult(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }
    }
}