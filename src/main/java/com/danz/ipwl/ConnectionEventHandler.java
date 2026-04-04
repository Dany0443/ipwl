package com.danz.ipwl.events;

import com.danz.ipwl.IPWLMod;
import com.danz.ipwl.config.IPWLMessages;
import com.danz.ipwl.manager.WhitelistManager;
import com.danz.ipwl.commands.SecurityCommands;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ConnectionEventHandler {

    // Dedicated thread pool for async IP verification — avoids starving the
    // common ForkJoinPool with Thread.sleep() calls during retry back-off.
    private static final ExecutorService VERIFY_EXECUTOR =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "ipwl-verify");
                t.setDaemon(true);
                return t;
            });

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            String username = player.getName().getString();

            String ip = "unknown";
            if (player.connection.getRemoteAddress() instanceof InetSocketAddress addr) {
                ip = addr.getAddress().getHostAddress();
            }

            SecurityCommands.updatePlayerSeen(username, ip);
            final String finalIp = ip;

            CompletableFuture.runAsync(() -> {
                if (!verifyPlayerWithRetry(username, finalIp, 3)) {
                    server.execute(() -> {
                        if (player.connection != null) {
                            player.connection.disconnect(
                                Component.literal(IPWLMessages.get("ipwl.disconnect.security_failed"))
                            );
                            IPWLMod.LOGGER.warn("[IPWL SECURITY] Kicked {} due to verification failure", username);
                        }
                    });
                }
            }, VERIFY_EXECUTOR);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.player;
            String username = player.getName().getString();
            String ip = "unknown";
            if (player.connection.getRemoteAddress() instanceof InetSocketAddress addr) {
                ip = addr.getAddress().getHostAddress();
            }
            SecurityCommands.updatePlayerLeft(username, ip);
            IPWLMod.getSecurityManager().removeConnection(ip);
        });
    }

    /**
     * Shuts down the verify executor gracefully. Call from IPWLMod.shutdown() if desired.
     */
    public static void shutdown() {
        VERIFY_EXECUTOR.shutdownNow();
    }

    private static boolean verifyPlayerWithRetry(String username, String ip, int maxRetries) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                WhitelistManager.WhitelistResult result = IPWLMod.getWhitelistManager().checkPlayerIp(username, ip);
                if (result.allowed) {
                    if (attempt > 1 && IPWLMod.getConfig().isVerboseLogging()) {
                        IPWLMod.LOGGER.info("[IPWL SECURITY] {} successfully verified on attempt {}", username, attempt);
                    }
                    return true;
                }

                if (attempt < maxRetries) {
                    if (IPWLMod.getConfig().isVerboseLogging()) {
                        IPWLMod.LOGGER.warn("[IPWL SECURITY] {} verification failed on attempt {}: {} - Retrying...",
                            username, attempt, result.reason);
                    }
                    Thread.sleep(500L * attempt);
                } else {
                    IPWLMod.LOGGER.error("[IPWL SECURITY] {} verification failed after {} attempts: {}",
                        username, maxRetries, result.reason);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                IPWLMod.LOGGER.error("[IPWL SECURITY] Exception during verification attempt {} for {}: {}",
                    attempt, username, e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(1000L * attempt);
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