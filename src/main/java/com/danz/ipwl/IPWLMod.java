package com.danz.ipwl;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.danz.ipwl.commands.WhitelistCommands;
import com.danz.ipwl.commands.SecurityCommands;
import com.danz.ipwl.config.IPWLConfig;
import com.danz.ipwl.config.IPWLMessages;
import com.danz.ipwl.events.ConnectionEventHandler;
import com.danz.ipwl.manager.WhitelistManager;
import com.danz.ipwl.manager.SecurityManager;

public class IPWLMod implements ModInitializer {
    public static final String MOD_ID = "ipwl";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static MinecraftServer server;
    private static WhitelistManager whitelistManager;
    private static SecurityManager  securityManager;
    private static IPWLConfig       config;

    @Override
    public void onInitialize() {
        LOGGER.info("IPWhiteList Initializing...");

        config = IPWLConfig.load();
        // Load messages early so every subsequent use is ready
        IPWLMessages.reload();

        ServerLifecycleEvents.SERVER_STARTING.register(s -> {
            server = s;
            whitelistManager = new WhitelistManager();
            whitelistManager.load();
            securityManager = new SecurityManager();
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            WhitelistCommands.register(dispatcher);
            SecurityCommands.register(dispatcher);
        });

        ConnectionEventHandler.register();
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> shutdown());

        LOGGER.info("IPWhiteList initialization complete");
    }

    private void shutdown() {
        if (whitelistManager != null) { whitelistManager.save(); whitelistManager.shutdown(); }
        if (securityManager  != null) securityManager.shutdown();
        if (config           != null) config.save();
        LOGGER.info("IPWhiteList: Server stopped, all data saved");
    }

    public static boolean hasPermission(CommandSourceStack source) {
        if (!source.isPlayer()) return true;
        if (source.permissions().hasPermission(
                net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN)) return true;
        return config != null && config.isAdmin(source.getTextName());
    }

    public static void sendFeedback(CommandSourceStack source, String message) {
        String clean = message.replaceAll("§[0-9a-fk-or]", "");
        if (source.getEntity() == null) {
            source.sendSuccess(() -> Component.literal(clean), false);
        } else {
            source.sendSuccess(() -> Component.literal(message), false);
            LOGGER.info("[IPWL] " + clean);
        }
    }

    public static Component disconnectMessage(String coloredMessage) {
        String clean = coloredMessage.replaceAll("§[0-9a-fk-or]", "");
        LOGGER.info("[IPWL] Disconnecting player: {}", clean);
        return Component.literal(coloredMessage);
    }

    /**
     * Reloads config, whitelist, and messages from disk.
     */
    public static void reloadConfig() {
        config = IPWLConfig.load();
        IPWLMessages.reload();
    }

    public static MinecraftServer  getServer()          { return server; }
    public static WhitelistManager getWhitelistManager(){ return whitelistManager; }
    public static SecurityManager  getSecurityManager() { return securityManager; }
    public static IPWLConfig       getConfig()          { return config; }
}