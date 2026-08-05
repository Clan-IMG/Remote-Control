package net.clanimg.remote_control.client;

import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoReconnectManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("remote_control");

    private static volatile boolean connectingToPayoutServer = false;
    private static volatile boolean onPayoutServer = false;
    private static int switchTimeoutTicks = 0;
    private static final int SWITCH_TIMEOUT = 400; // 20 s safety reset

    private static int homeCommandDelay = 0;

    private static int payoutSwitchDelay = 0;
    private static final int PAYOUT_SWITCH_DELAY = 200; // 10 s

    private static int periodicCheckTicks = 0;
    private static final int PERIODIC_CHECK_INTERVAL = 18000; // 15 min

    /** Called by ClientPlayConnectionEvents.JOIN */
    public static void onJoin(MinecraftClient client) {
        switchTimeoutTicks = 0;
        periodicCheckTicks = 0;

        if (connectingToPayoutServer) {
            connectingToPayoutServer = false;
            onPayoutServer = true;
            RemoteControlConfig cfg = RemoteControlConfig.get();
            if (!cfg.spawnCommand.isEmpty()) {
                homeCommandDelay = 60; // 3 s
            }
            LOGGER.info("[RC] Joined payout server, spawn command in 3 s");
        } else {
            onPayoutServer = false;
            RemoteControlConfig cfg = RemoteControlConfig.get();
            if (!cfg.payoutServer.isEmpty()) {
                payoutSwitchDelay = PAYOUT_SWITCH_DELAY;
                LOGGER.info("[RC] Scheduling payout server switch in 10 s");
            }
        }
    }

    /** Called by ClientPlayConnectionEvents.DISCONNECT */
    public static void onDisconnect() {
        onPayoutServer = false;
        homeCommandDelay = 0;
        payoutSwitchDelay = 0;
        periodicCheckTicks = 0;
        // connectingToPayoutServer intentionally NOT reset here so BungeeCord
        // disconnect→reconnect pairs work correctly
    }

    public static void switchToPayoutServer(MinecraftClient client) {
        if (connectingToPayoutServer || client.player == null) return;
        RemoteControlConfig cfg = RemoteControlConfig.get();
        if (cfg.payoutServer.isEmpty()) return;
        connectingToPayoutServer = true;
        switchTimeoutTicks = 0;
        client.player.networkHandler.sendChatCommand("server " + cfg.payoutServer);
        LOGGER.info("[RC] Switching to payout server: {}", cfg.payoutServer);
    }

    public static boolean isOnPayoutServer() {
        return onPayoutServer;
    }

    public static boolean isConnectingToPayoutServer() {
        return connectingToPayoutServer;
    }

    public static void onTick(MinecraftClient client) {
        // Safety timeout for stuck connectingToPayoutServer flag
        if (connectingToPayoutServer) {
            if (++switchTimeoutTicks >= SWITCH_TIMEOUT) {
                connectingToPayoutServer = false;
                switchTimeoutTicks = 0;
                LOGGER.warn("[RC] Payout server switch timed out, resetting");
            }
        } else {
            switchTimeoutTicks = 0;
        }

        // Send spawn command 3 s after arriving on payout server
        if (homeCommandDelay > 0 && client.player != null) {
            if (--homeCommandDelay == 0) {
                String cmd = RemoteControlConfig.get().spawnCommand;
                if (!cmd.isEmpty()) {
                    if (cmd.startsWith("/")) cmd = cmd.substring(1);
                    client.player.networkHandler.sendChatCommand(cmd);
                    LOGGER.info("[RC] Sent /{}", cmd);
                }
            }
            return;
        }

        // Auto-switch to payout server after lobby join
        if (payoutSwitchDelay > 0 && client.player != null) {
            if (--payoutSwitchDelay == 0) {
                switchToPayoutServer(client);
            }
        }

        // Periodic safety check: every 15 min ensure we're on the payout server
        if (client.player != null && !onPayoutServer && !connectingToPayoutServer && payoutSwitchDelay == 0) {
            RemoteControlConfig cfg = RemoteControlConfig.get();
            if (!cfg.payoutServer.isEmpty()) {
                if (++periodicCheckTicks >= PERIODIC_CHECK_INTERVAL) {
                    periodicCheckTicks = 0;
                    LOGGER.info("[RC] Periodic check: not on payout server, switching");
                    switchToPayoutServer(client);
                }
            } else {
                periodicCheckTicks = 0;
            }
        } else {
            periodicCheckTicks = 0;
        }
    }
}
