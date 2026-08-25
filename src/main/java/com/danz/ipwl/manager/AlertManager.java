package com.danz.ipwl.manager;

import com.danz.ipwl.IPWLMod;
import com.danz.ipwl.config.IPWLMessages;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends rate-limited, clickable alerts to all online admins when an unknown
 * player attempts to connect.
 *
 * In-game: two hover-and-click buttons:
 *   [Accept] -> /ipwl add <player> <ip>
 *   [Ban IP] -> /ipwl banip <ip>
 *
 * Console: a clearly visible multi-line banner so the terminal admin can
 * see the name + IP and copy the add/ban command without searching logs.
 */
public final class AlertManager {

    /** ip -> last alert timestamp (ms) */
    private final Map<String, Long> lastAlertTime = new ConcurrentHashMap<>();

    private AlertManager() {}

    private static final AlertManager INSTANCE = new AlertManager();
    public static AlertManager getInstance() { return INSTANCE; }

    public void fireAlert(String username, String ip) {
        var config = IPWLMod.getConfig();
        if (!config.isEnableJoinAlerts()) return;

        long now = System.currentTimeMillis();
        Long last = lastAlertTime.get(ip);
        if (last != null && (now - last) < config.getAlertCooldownMs()) return;
        lastAlertTime.put(ip, now);

        logConsoleBanner(username, ip);
        Component alert = buildAlertComponent(username, ip);
        var server = IPWLMod.getServer();
        if (server != null) {
            // onHello runs on a login/network thread; marshal chat send to the
            // main server thread so delivery to online admins is reliable.
            server.execute(() -> sendToAdmins(alert, username, ip));
        } else {
            sendToAdmins(alert, username, ip);
        }
    }

    // -------------------------------------------------------------------------
    // Console banner
    // -------------------------------------------------------------------------

    private static void logConsoleBanner(String username, String ip) {
        String bar = "====================================================";
        IPWLMod.LOGGER.warn("[IPWL] {}", bar);
        IPWLMod.LOGGER.warn("[IPWL]   !! UNKNOWN CONNECTION ATTEMPT !!");
        IPWLMod.LOGGER.warn("[IPWL] {}", bar);
        IPWLMod.LOGGER.warn("[IPWL]   Name   : {}", username);
        IPWLMod.LOGGER.warn("[IPWL]   IP     : {}", ip);
        IPWLMod.LOGGER.warn("[IPWL]   To add : /ipwl add {} {}", username, ip);
        IPWLMod.LOGGER.warn("[IPWL]   To ban : /ipwl banip {}", ip);
        IPWLMod.LOGGER.warn("[IPWL] {}", bar);
    }

    // -------------------------------------------------------------------------
    // In-game clickable alert
    // -------------------------------------------------------------------------

    private static Component buildAlertComponent(String username, String ip) {
        MutableComponent base = Component.literal(
            IPWLMessages.fmt("ipwl.alert.base", username, ip));

        String acceptCmd = IPWLMessages.fmt("ipwl.alert.cmd_accept", username, ip);
        MutableComponent accept = Component.literal(IPWLMessages.get("ipwl.alert.btn_accept"))
            .withStyle(style -> style
                .withClickEvent(new ClickEvent.RunCommand(acceptCmd))
                .withHoverEvent(new HoverEvent.ShowText(
                    Component.literal(IPWLMessages.fmt("ipwl.alert.hover_accept", username, ip)))));

        String banCmd = IPWLMessages.fmt("ipwl.alert.cmd_ban", ip);
        MutableComponent ban = Component.literal(IPWLMessages.get("ipwl.alert.btn_ban"))
            .withStyle(style -> style
                .withClickEvent(new ClickEvent.RunCommand(banCmd))
                .withHoverEvent(new HoverEvent.ShowText(
                    Component.literal(IPWLMessages.fmt("ipwl.alert.hover_ban", ip)))));

        return base.append(accept).append(ban);
    }

    // -------------------------------------------------------------------------

    private static void sendToAdmins(Component msg, String username, String ip) {
        var server = IPWLMod.getServer();
        if (server == null) return;
        var playerManager = server.getPlayerList();
        if (playerManager == null) return;
        int delivered = 0;
        for (ServerPlayer player : playerManager.getPlayers()) {
            if (IPWLMod.hasPermission(player.createCommandSourceStack())) {
                player.sendSystemMessage(msg);
                delivered++;
            }
        }
        if (delivered == 0 && IPWLMod.getConfig().isVerboseLogging()) {
            IPWLMod.LOGGER.warn(
                "[IPWL] Unknown join alert for {} ({}) had no online admin recipients",
                username, ip
            );
        }
    }
}
