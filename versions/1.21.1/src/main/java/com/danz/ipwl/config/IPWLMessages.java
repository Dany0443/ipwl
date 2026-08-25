package com.danz.ipwl.config;

import com.danz.ipwl.IPWLMod;
import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads and caches all user-facing strings from {@code config/ipwl-lang.json}.
 * Falls back to the built-in {@code lang/en_us.json} (jar resource) if the
 * external file does not exist or a key is missing.
 *
 * <p>Usage: {@code IPWLMessages.get("ipwl.disconnect.not_whitelisted")}
 * or with format args: {@code IPWLMessages.fmt("ipwl.cmd.add_ip", player, ip)}
 */
public final class IPWLMessages {

    private static final File OVERRIDE_FILE = new File("config/ipwl-lang.json");
    private static final String BUILTIN_RESOURCE = "/lang/en_us.json";

    private static Map<String, String> builtIn  = new HashMap<>();
    private static Map<String, String> overrides = new HashMap<>();

    static {
        reload();
    }

    private IPWLMessages() {}

    /** Reload both the built-in defaults and the server-owner override file. */
    public static void reload() {
        builtIn  = loadResource(BUILTIN_RESOURCE);
        overrides = loadFile(OVERRIDE_FILE);
    }

    /**
     * Return the message for {@code key}, preferring any server-owner override.
     * Returns the raw key string if not found anywhere (so nothing is silently swallowed).
     */
    public static String get(String key) {
        if (overrides.containsKey(key)) return overrides.get(key);
        if (builtIn.containsKey(key))   return builtIn.get(key);
        IPWLMod.LOGGER.warn("[IPWL] Missing lang key: {}", key);
        return key;
    }

    /**
     * Convenience: {@code String.format(get(key), args)}.
     */
    public static String fmt(String key, Object... args) {
        return String.format(get(key), args);
    }

    // -------------------------------------------------------------------------

    private static Map<String, String> loadResource(String path) {
        Map<String, String> map = new HashMap<>();
        try (InputStream in = IPWLMessages.class.getResourceAsStream(path)) {
            if (in == null) {
                IPWLMod.LOGGER.error("[IPWL] Built-in lang resource not found: {}", path);
                return map;
            }
            return parseJson(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            IPWLMod.LOGGER.error("[IPWL] Failed to load built-in lang: {}", e.getMessage());
            return map;
        }
    }

    private static Map<String, String> loadFile(File file) {
        if (!file.exists()) return new HashMap<>();
        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            return parseJson(reader);
        } catch (Exception e) {
            IPWLMod.LOGGER.error("[IPWL] Failed to load lang override file: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private static Map<String, String> parseJson(Reader reader) {
        Map<String, String> map = new HashMap<>();
        JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            map.put(entry.getKey(), entry.getValue().getAsString());
        }
        return map;
    }
}