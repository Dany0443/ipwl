package com.danz.ipwl.mixin;

import com.danz.ipwl.IPWLMod;
import com.danz.ipwl.config.IPWLMessages;
import com.danz.ipwl.manager.AlertManager;
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

    @Shadow @Final private MinecraftServer server;
    @Shadow @Final public Connection connection;
    @Shadow public abstract void disconnect(Component reason);

    @Unique private boolean ipwl$verificationChecked = false;

    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    public void ipwl$onHello(ServerboundHelloPacket packet, CallbackInfo ci) {
        if (ipwl$verificationChecked) return;
        ipwl$verificationChecked = true;

        String username = packet.name();
        String ip       = ipwl$getIpAddress();

        SecurityManager security = IPWLMod.getSecurityManager();
        if (security == null) {
            IPWLMod.LOGGER.warn("[IPWL] SecurityManager not yet initialized, allowing {} through mixin", username);
            return;
        }

        // 1. Lockdown
        if (security.isLockdownMode() && !IPWLMod.getConfig().isAdmin(username)) {
            security.recordBlockedConnection();
            disconnect(Component.literal(IPWLMessages.get("ipwl.disconnect.lockdown")));
            ci.cancel();
            return;
        }

        // 2. Permanent IP ban
        if (IPWLMod.getConfig().isBannedIp(ip)) {
            security.recordBlockedConnection();
            IPWLMod.LOGGER.warn("[IPWL SECURITY] Permanently banned IP {} tried to connect as {}", ip, username);
            disconnect(Component.literal(IPWLMessages.get("ipwl.disconnect.temp_banned")));
            ci.cancel();
            return;
        }

        // 3. Temp ban (covers both rate-limit and bruteforce escalations)
        if (security.isTempBanned(ip)) {
            security.recordBlockedConnection();
            disconnect(Component.literal(IPWLMessages.get("ipwl.disconnect.temp_banned")));
            ci.cancel();
            return;
        }

        // 4. Bruteforce detection — IP trying many different names (bot behaviour)
        //    Must run BEFORE rate-limit so we catch multi-name attempts that individually
        //    pass the 1-per-second rate check.
        if (!security.checkBruteForce(ip, username)) {
            security.recordBlockedConnection();
            disconnect(Component.literal(IPWLMessages.get("ipwl.disconnect.temp_banned")));
            ci.cancel();
            return;
        }

        // 5. Rate limit (single name hammering)
        if (!security.checkRateLimit(ip)) {
            disconnect(Component.literal(IPWLMessages.get("ipwl.disconnect.rate_limit")));
            ci.cancel();
            return;
        }

        // 6. Duplicate session
        if (IPWLMod.getConfig().isEnableDuplicateCheck() && ipwl$isPlayerAlreadyOnline(username)) {
            security.recordDuplicateAttempt();
            IPWLMod.LOGGER.warn("[IPWL SECURITY] Blocked duplicate login for {} from {}", username, ip);
            disconnect(Component.literal(IPWLMessages.get("ipwl.disconnect.duplicate")));
            ci.cancel();
            return;
        }

        // 7. Whitelist check
        WhitelistManager.WhitelistResult result = IPWLMod.getWhitelistManager().checkPlayerIp(username, ip);
        if (!result.allowed) {
            security.recordBlockedConnection();
            if (IPWLMod.getConfig().isLogAllAttempts() || IPWLMod.getConfig().isVerboseLogging()) {
                IPWLMod.LOGGER.warn("[IPWL SECURITY] Blocked {} from {}: {}", username, ip, result.reason);
            }
            AlertManager.getInstance().fireAlert(username, ip);
            disconnect(Component.literal(IPWLMessages.get("ipwl.disconnect.not_whitelisted")));
            ci.cancel();
            return;
        }

        // 8. Max connections per IP
        if (!security.addConnection(ip)) {
            security.recordBlockedConnection();
            IPWLMod.LOGGER.warn("[IPWL SECURITY] Max connections reached for {} from {}", username, ip);
            disconnect(Component.literal(IPWLMessages.get("ipwl.disconnect.max_connections")));
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
            if (connection.getRemoteAddress() instanceof InetSocketAddress addr) {
                return addr.getAddress().getHostAddress();
            }
        } catch (Exception e) {
            IPWLMod.LOGGER.warn("[IPWL] Failed to get player IP: {}", e.getMessage());
        }
        return "unknown";
    }

    @Unique
    private boolean ipwl$isPlayerAlreadyOnline(String username) {
        try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.getName().getString().equals(username)) return true;
            }
        } catch (Exception e) {
            IPWLMod.LOGGER.warn("[IPWL] Error checking online players: {}", e.getMessage());
        }
        return false;
    }
}