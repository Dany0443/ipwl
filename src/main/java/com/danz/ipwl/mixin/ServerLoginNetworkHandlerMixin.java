package com.danz.ipwl.mixin;

import com.danz.ipwl.IPWLMod;
import com.danz.ipwl.manager.WhitelistManager;
import com.danz.ipwl.manager.SecurityManager;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.InetSocketAddress;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginNetworkHandlerMixin {

    @Shadow @Final
    private MinecraftServer server;

    @Shadow @Final
    public Connection connection;

    @Shadow
    public abstract void disconnect(Component reason);

    @Unique
    private boolean ipwl$verificationChecked = false;

    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    public void ipwl$onHello(ServerboundHelloPacket packet, CallbackInfo ci) {
        if (ipwl$verificationChecked) return;
        ipwl$verificationChecked = true;

        String username = packet.name();
        String ip = ipwl$getIpAddress();

        // FIX: null guard — securityManager is set in SERVER_STARTING; if somehow
        // a login packet arrives before that fires, fail open rather than NPE.
        SecurityManager security = IPWLMod.getSecurityManager();
        if (security == null) {
            IPWLMod.LOGGER.warn("[IPWL] SecurityManager not yet initialized, allowing {} to pass mixin check", username);
            return;
        }

        if (security.isLockdownMode()) {
            if (!IPWLMod.getConfig().isAdmin(username)) {
                security.recordBlockedConnection();
                disconnect(IPWLMod.disconnectMessage("§cServer is in lockdown mode. Only admins can join."));
                ci.cancel();
                return;
            }
        }

        if (security.isTempBanned(ip)) {
            security.recordBlockedConnection();
            disconnect(IPWLMod.disconnectMessage("§cYour IP is temporarily banned due to too many failed connections."));
            ci.cancel();
            return;
        }

        if (!security.checkRateLimit(ip)) {
            disconnect(IPWLMod.disconnectMessage("§cToo many connection attempts. Please slow down."));
            ci.cancel();
            return;
        }

        if (IPWLMod.getConfig().isEnableDuplicateCheck() && isPlayerAlreadyOnline(username)) {
            security.recordDuplicateAttempt();
            IPWLMod.LOGGER.warn("[IPWL SECURITY] Blocked duplicate connection attempt for {} from {}", username, ip);
            disconnect(IPWLMod.disconnectMessage("§cYou are already connected to this server!"));
            ci.cancel();
            return;
        }

        WhitelistManager.WhitelistResult result = IPWLMod.getWhitelistManager().checkPlayerIp(username, ip);

        if (!result.allowed) {
            security.recordBlockedConnection();
            if (IPWLMod.getConfig().isLogAllAttempts() || IPWLMod.getConfig().isVerboseLogging()) {
                IPWLMod.LOGGER.warn("[IPWL SECURITY] Blocked connection for {} from {}: {}", username, ip, result.reason);
            }
            disconnect(IPWLMod.disconnectMessage("§cYou are not whitelisted on this server!"));
            ci.cancel();
            return;
        }

        if (!security.addConnection(ip)) {
            security.recordBlockedConnection();
            IPWLMod.LOGGER.warn("[IPWL SECURITY] Blocked connection for {} from {}: Max connections reached", username, ip);
            disconnect(IPWLMod.disconnectMessage("§cToo many accounts connected from your IP address."));
            ci.cancel();
            return;
        }

        security.recordAllowedConnection();
        if (IPWLMod.getConfig().isLogAllAttempts() || IPWLMod.getConfig().isVerboseLogging()) {
            IPWLMod.LOGGER.info("[IPWL] Allowed connection for {} from {}", username, ip);
        }
    }

    @Unique
    private String ipwl$getIpAddress() {
        try {
            if (connection.getRemoteAddress() instanceof InetSocketAddress inetAddress) {
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
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.getName().getString().equals(username)) {
                    return true;
                }
            }
        } catch (Exception e) {
            IPWLMod.LOGGER.warn("Error checking online players: {}", e.getMessage());
            return false;
        }
        return false;
    }
}