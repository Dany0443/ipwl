package com.danz.ipwl.manager;

import com.google.gson.*;
import com.danz.ipwl.IPWLMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class WhitelistManager {
    private final Map<String, Set<String>> whitelist = new ConcurrentHashMap<>();
    private final File whitelistFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public WhitelistManager() {
        // Use FabricLoader config dir — always resolves correctly regardless of server state
        whitelistFile = FabricLoader.getInstance().getConfigDir().resolve("ipwl_whitelist.json").toFile();
    }

    public void load() {
        if (!whitelistFile.exists()) {
            save();
            return;
        }

        try (FileReader reader = new FileReader(whitelistFile)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            whitelist.clear();

            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                String username = entry.getKey();
                Set<String> ips = new HashSet<>();

                JsonArray ipArray = entry.getValue().getAsJsonArray();
                for (JsonElement ipElement : ipArray) {
                    ips.add(ipElement.getAsString());
                }

                whitelist.put(username, ips);
            }
        } catch (IOException e) {
            IPWLMod.LOGGER.error("Failed to load whitelist", e);
        }
    }

    public void save() {
        try (FileWriter writer = new FileWriter(whitelistFile)) {
            JsonObject json = new JsonObject();

            for (Map.Entry<String, Set<String>> entry : whitelist.entrySet()) {
                JsonArray ipArray = new JsonArray();
                for (String ip : entry.getValue()) {
                    ipArray.add(ip);
                }
                json.add(entry.getKey(), ipArray);
            }

            gson.toJson(json, writer);
        } catch (IOException e) {
            IPWLMod.LOGGER.error("Failed to save whitelist", e);
        }
    }

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

    public WhitelistResult checkPlayerIp(String username, String ip) {
        Set<String> allowedIps = whitelist.get(username);

        if (allowedIps == null) {
            return new WhitelistResult(false, "Player not in whitelist");
        }

        if (allowedIps.contains("*") && IPWLMod.getConfig().isAllowWildcardIps()) {
            return new WhitelistResult(true, "Allowed by wildcard");
        }

        if (allowedIps.contains(ip)) {
            return new WhitelistResult(true, "Exact IP match");
        }

        if (IPWLMod.getConfig().isAllowSubnetPatterns()) {
            for (String allowedIp : allowedIps) {
                if (allowedIp.endsWith(".*")) {
                    String prefix = allowedIp.substring(0, allowedIp.length() - 2);
                    if (ip.startsWith(prefix)) {
                        return new WhitelistResult(true, "Subnet match: " + allowedIp);
                    }
                }
            }
        }

        return new WhitelistResult(false, "IP mismatch. Connection attempted from: " + ip);
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

    public List<Component> getFormattedList() {
        List<Component> lines = new ArrayList<>();

        if (whitelist.isEmpty()) {
            lines.add(Component.literal("§7No players whitelisted"));
            return lines;
        }

        lines.add(Component.literal("§6=== IP Whitelist ==="));
        for (Map.Entry<String, Set<String>> entry : whitelist.entrySet()) {
            String username = entry.getKey();
            Set<String> ips = entry.getValue();

            if (ips.contains("*")) {
                lines.add(Component.literal(String.format("§a%s §7→ §e[Any IP]", username)));
            } else {
                lines.add(Component.literal(String.format("§a%s §7→ §f%s",
                    username, String.join(", ", ips))));
            }
        }

        return lines;
    }

    public static class WhitelistResult {
        public final boolean allowed;
        public final String reason;

        public WhitelistResult(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }
    }
}