package com.danz.fabric.ipwl.mixin;

import com.danz.fabric.ipwl.IPWLMod;
import com.danz.fabric.ipwl.manager.WhitelistManager;
import com.danz.fabric.ipwl.manager.SecurityManager;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.packet.c2s.login.LoginHelloC2SPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

@Mixin(ServerLoginNetworkHandler.class)
public abstract class ServerLoginNetworkHandlerMixin {
    
    @Shadow @Final
    private MinecraftServer server;
    
    @Shadow @Final
    public ClientConnection connection;
    
    @Shadow
    private GameProfile profile;
    
    @Shadow
    public abstract void disconnect(Text reason);
    
    @Unique
    private boolean ipwl$authenticated = false;
    
    @Unique
    private String ipwl$playerIp = null;
    
    @Inject(method = "onHello", at = @At("HEAD"), cancellable = true)
    private void onLoginStart(LoginHelloC2SPacket packet, CallbackInfo ci) {
        String username = packet.name();
        String ip = getPlayerIp();
        ipwl$playerIp = ip;
        
        // Null safety checks
        WhitelistManager whitelistManager = IPWLMod.getWhitelistManager();
        SecurityManager securityManager = IPWLMod.getSecurityManager();
        
        if (whitelistManager == null || securityManager == null) {
            IPWLMod.LOGGER.error("IPWL managers not initialized, rejecting connection: {}", username);
            disconnect(Text.literal("§cServer error. Please try again later."));
            ci.cancel();
            return;
        }
        
        try {
            // Security check first
            if (!securityManager.checkConnection(ip, username)) {
                disconnect(Text.literal("§cConnection Refused: Security check failed."));
                ci.cancel();
                return;
            }
            
            // Check for duplicate login (case-sensitive exact match)
            if (isPlayerAlreadyOnline(username)) {
                securityManager.incrementDuplicateAttempts();
                IPWLMod.LOGGER.warn("Duplicate login attempt: {} from {}", username, ip);
                disconnect(Text.literal("§cConnection Refused: Already logged in."));
                ci.cancel();
                return;
            }
            
            // Whitelist check with error handling
            if (!whitelistManager.isWhitelisted(username, ip)) {
                securityManager.incrementBlockedConnections();
                IPWLMod.LOGGER.warn("Non-whitelisted connection attempt: {} from {}", username, ip);
                disconnect(Text.literal("§cConnection Refused: You are not whitelisted."));
                ci.cancel();
                return;
            }
            
            // Mark as authenticated (but don't call onConnectionAccepted here - let event handler do it)
            ipwl$authenticated = true;
            IPWLMod.LOGGER.info("Login verification successful: {} from IP {}", username, ip);
            
        } catch (Exception e) {
            IPWLMod.LOGGER.error("Error during login verification for {} from {}: {}", username, ip, e.getMessage(), e);
            disconnect(Text.literal("§cConnection error. Please try again."));
            ci.cancel();
            return;
        }
    }
    
    @Inject(method = "onDisconnected", at = @At("HEAD"))
    private void onDisconnect(DisconnectionInfo info, CallbackInfo ci) {
        if (ipwl$playerIp != null) {
            Text reason = info.reason();
            String playerName = profile != null ? ipwl$getProfileName(profile) : "unknown";
            
            // Only call onConnectionClosed if we were in the middle of authentication
            // The actual connection tracking will be handled by the connection event handlers
            if (!ipwl$authenticated) {
                SecurityManager securityManager = IPWLMod.getSecurityManager();
                if (securityManager != null) {
                    // This was a failed login attempt, don't need to decrement connections
                    IPWLMod.LOGGER.debug("Failed login attempt disconnected: {} from IP {} - {}", 
                        playerName, ipwl$playerIp, reason.getString());
                }
            } else {
                IPWLMod.LOGGER.debug("Authenticated player disconnected during login: {} from IP {} - {}", 
                    playerName, ipwl$playerIp, reason.getString());
            }
        }
    }
    
    @Unique
    private String getPlayerIp() {
        try {
            SocketAddress address = connection.getAddress();
            if (address instanceof InetSocketAddress inetAddress) {
                return inetAddress.getAddress().getHostAddress();
            }
        } catch (Exception e) {
            IPWLMod.LOGGER.warn("Failed to get player IP address: {}", e.getMessage());
        }
        return "unknown";
    }
    
    @Unique
    private boolean isPlayerAlreadyOnline(String username) {
        try {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                // Case-sensitive exact match for username
                if (player.getName().getString().equals(username)) {
                    return true;
                }
            }
        } catch (Exception e) {
            IPWLMod.LOGGER.warn("Error checking online players: {}", e.getMessage());
            // If we can't check, assume not online to allow connection attempt
            return false;
        }
        return false;
    }

    @Unique
    private String ipwl$getProfileName(GameProfile p) {
        try {
            return (String) GameProfile.class.getMethod("getName").invoke(p);
        } catch (Throwable ignored) {
        }
        try {
            return (String) GameProfile.class.getMethod("name").invoke(p);
        } catch (Throwable ignored) {
        }
        try {
            Object id = GameProfile.class.getMethod("getId").invoke(p);
            return id != null ? String.valueOf(id) : "unknown";
        } catch (Throwable ignored) {
        }
        return "unknown";
    }
}