package com.danz.fabric.ipwl;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.danz.fabric.ipwl.commands.WhitelistCommands;
import com.danz.fabric.ipwl.commands.SecurityCommands;
import com.danz.fabric.ipwl.events.ConnectionEventHandler;
import com.danz.fabric.ipwl.manager.WhitelistManager;
import com.danz.fabric.ipwl.manager.SecurityManager;
import com.danz.fabric.ipwl.config.IPWLConfig;

public class IPWLMod implements ModInitializer {
    public static final String MOD_ID = "ipwl";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    private static MinecraftServer server;
    private static WhitelistManager whitelistManager;
    private static SecurityManager securityManager;
    private static IPWLConfig config;
    
    @Override
    public void onInitialize() {
        LOGGER.info("IPWhiteList Initializing...");
        
        // Initialize config
        config = IPWLConfig.load();
        
        // Initialize managers
        whitelistManager = new WhitelistManager();
        securityManager = new SecurityManager();
        
        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            WhitelistCommands.register(dispatcher);
            SecurityCommands.register(dispatcher);
            LOGGER.info("Commands registered: /ipwl, /whitelist, /security, /lockdown, /lk, /seen");
        });
        
        // Register connection event handlers
        ConnectionEventHandler.register();
        
        // Server lifecycle events
        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPED.register(this::onServerStopped);
        
        LOGGER.info("IPWL: Mod initialized successfully!");
    }
    
    private void onServerStarting(MinecraftServer minecraftServer) {
        server = minecraftServer;
        
        // Load whitelist and initialize security
        whitelistManager.load();
        securityManager.initialize();
        LOGGER.info("============================================================");
        LOGGER.info("IPWhiteList(by danz) Loaded Successfully");
        LOGGER.info("IP based whitelist is now active");
        LOGGER.info("U can see all the available commands by typing /ipwl help");
        LOGGER.info("=============================================================");
    }
    
    private void onServerStopped(MinecraftServer minecraftServer) {
        // Save all data
        whitelistManager.save();
        securityManager.shutdown();
        config.save();
        
        LOGGER.info("IPWhiteList: Server stopped, all data saved");
    }

    public static boolean hasPermission(ServerCommandSource source) {
        // Always allow console/command blocks
        if (!source.isExecutedByPlayer()) {
            return true;
        }
        
        // Allow Vanilla OPs (Level 3+)
        if (source.hasPermissionLevel(3)) {
            return true;
        }
        
        // Check IPWL Admin List
        String playerName = source.getName();
        return config.isAdmin(playerName);
    }

    public static void sendFeedback(ServerCommandSource source, String message) {
        String cleanMessage = message.replaceAll("§[0-9a-fk-or]", "");
        
        if (source.getEntity() == null) {

            source.sendFeedback(() -> Text.literal(cleanMessage), false);
        } else {
            // Source is a Player
            // Send colored message to player
            source.sendFeedback(() -> Text.literal(message), false);
            
            // Log clean message to console so admins know what the player did
            LOGGER.info("[IPWL] " + cleanMessage);
        }
    }
    
    public static MinecraftServer getServer() { return server; }
    public static WhitelistManager getWhitelistManager() { return whitelistManager; }
    public static SecurityManager getSecurityManager() { return securityManager; }
    public static IPWLConfig getConfig() { return config; }
}