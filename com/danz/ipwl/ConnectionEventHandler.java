package com.danz.fabric.ipwl.events;

import com.danz.fabric.ipwl.IPWLMod;
import com.danz.fabric.ipwl.manager.WhitelistManager;
import com.danz.fabric.ipwl.commands.SecurityCommands;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.concurrent.CompletableFuture;

public class ConnectionEventHandler {
    
    public static void register() {
        // Handle player join events
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            String username = player.getName().getString();
            String ip = player.getIp();
            
            // Update seen data
            SecurityCommands.updatePlayerSeen(username, ip);
            
            // Verify whitelist with retry logic
            CompletableFuture.runAsync(() -> {
                if (!verifyPlayerWithRetry(username, ip, 3)) {
                    // Kick player if verification fails after retries
                    server.execute(() -> {
                        if (player.networkHandler != null) {
                            player.networkHandler.disconnect(
                                Text.literal("§cSecurity verification failed. Please contact an administrator.")
                            );
                            IPWLMod.LOGGER.warn("[IPWL SECURITY] Kicked {} due to verification failure", username);
                        }
                    });
                } else {
                    // Only log success if verbose or if we want to confirm access
                    if (IPWLMod.getConfig().isVerboseLogging()) {
                        IPWLMod.LOGGER.info("[IPWL SECURITY] {} verified successfully (IP: {})", username, ip);
                    }
                }
            });
        });
        
        // Handle player disconnect events
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            String username = player.getName().getString();
            String ip = player.getIp();
            SecurityCommands.updatePlayerLeft(username, ip);
        });
    }
    
    private static boolean verifyPlayerWithRetry(String username, String ip, int maxRetries) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                WhitelistManager.WhitelistResult result = IPWLMod.getWhitelistManager().isWhitelistedDetailed(username, ip);
                
                if (result.allowed) {
                    // SILENT MODE: Don't log normal first-try success to avoid spam (redundant with login log)
                    // VERBOSE MODE: Log everything
                    if (IPWLMod.getConfig().isVerboseLogging()) {
                        IPWLMod.LOGGER.info("[IPWL SECURITY] {} verification successful on attempt {} ({})", 
                            username, attempt, result.reason);
                    } else if (attempt > 1) {
                        // If it took more than 1 try, it's worth logging even in silent mode
                        IPWLMod.LOGGER.info("[IPWL SECURITY] {} verified on retry {} ({})", 
                            username, attempt, result.reason);
                    }
                    return true;
                }
                
                // If failed, we log warnings only if it's not the final attempt or if verbose
                if (attempt < maxRetries) {
                    if (IPWLMod.getConfig().isVerboseLogging()) {
                        IPWLMod.LOGGER.warn("[IPWL SECURITY] {} verification failed on attempt {}: {} - Retrying...", 
                            username, attempt, result.reason);
                    }
                    Thread.sleep(500 * attempt);
                } else {
                    // Final failure is always logged
                    IPWLMod.LOGGER.error("[IPWL SECURITY] {} verification failed after {} attempts: {}", 
                        username, maxRetries, result.reason);
                }
                
            } catch (Exception e) {
                IPWLMod.LOGGER.error("[IPWL SECURITY] Exception during verification attempt {} for {}: {}", 
                    attempt, username, e.getMessage());
                
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(1000 * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        
        return false;
    }
}