package net.clanimg.remote_control.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

public class AutoReconnectManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("remote_control");

    private static volatile boolean connectingToPayoutServer = false;
    private static volatile boolean onPayoutServer = false;

    // True while the server has put us in the "Server voll" join queue instead of
    // actually connecting us — must not assume arrival / send the teleport command
    // until the queue message tells us we've actually left it.
    private static volatile boolean inQueue = false;

    // A "/server X" switch on a proxy (Bungee/Velocity) is seamless — no new
    // ClientPlayConnectionEvents.JOIN fires, so arrival must be timer-based.
    private static int arrivalDelay = 0;
    private static final int ARRIVAL_DELAY_TICKS = 100; // 5 s budget for the proxy switch itself

    private static int homeCommandDelay = 0;
    private static final int HOME_COMMAND_STABILIZE_TICKS = 60; // 3 s after arrival before the teleport command

    private static int payoutSwitchDelay = 0;
    private static final int PAYOUT_SWITCH_DELAY = 200; // 10 s

    private static int periodicCheckTicks = 0;
    private static final int PERIODIC_CHECK_INTERVAL = 18000; // 15 min

    /** Called by ClientPlayConnectionEvents.JOIN */
    public static void onJoin(MinecraftClient client) {
        periodicCheckTicks = 0;

        // Fresh connection to the network. Backend switches are seamless (handled
        // entirely by the arrivalDelay timer below), so this only fires for a real login.
        if (connectingToPayoutServer) return;

        onPayoutServer = false;
        RemoteControlConfig cfg = RemoteControlConfig.get();
        if (!cfg.payoutServer.isEmpty()) {
            payoutSwitchDelay = PAYOUT_SWITCH_DELAY;
            LOGGER.info("[RC] Scheduling payout server switch in 10 s");
        }
    }

    /** Called by ClientPlayConnectionEvents.DISCONNECT */
    public static void onDisconnect() {
        onPayoutServer = false;
        connectingToPayoutServer = false;
        inQueue = false;
        arrivalDelay = 0;
        homeCommandDelay = 0;
        payoutSwitchDelay = 0;
        periodicCheckTicks = 0;
    }

    public static void switchToPayoutServer(MinecraftClient client) {
        if (connectingToPayoutServer || client.player == null) return;
        RemoteControlConfig cfg = RemoteControlConfig.get();
        if (cfg.payoutServer.isEmpty()) return;
        connectingToPayoutServer = true;
        inQueue = false;
        arrivalDelay = ARRIVAL_DELAY_TICKS;
        client.player.networkHandler.sendChatCommand("server " + cfg.payoutServer);
        LOGGER.info("[RC] Switching to payout server: {}", cfg.payoutServer);
    }

    /** Called for every incoming system/game chat message — watches for the join-queue state. */
    public static void onGameMessage(Text message) {
        if (!connectingToPayoutServer && !inQueue) return;

        String lower = message.getString().toLowerCase(Locale.ROOT);
        if (!lower.contains("warteschlange")) return;

        if (!inQueue && (lower.contains("hinzugef") || lower.contains("voll"))) {
            // Server is full — we were put in the queue instead of actually connecting.
            // Cancel the arrival/teleport timers, sending any command now would kick us
            // out of the queue again without ever reaching the payout server.
            inQueue = true;
            arrivalDelay = 0;
            homeCommandDelay = 0;
            LOGGER.info("[RC] Server voll, warte in der Warteschlange...");
        } else if (inQueue && lower.contains("verlassen")) {
            // We naturally left the queue (our turn came up) — now actually connecting.
            inQueue = false;
            arrivalDelay = ARRIVAL_DELAY_TICKS;
            LOGGER.info("[RC] Warteschlange verlassen, warte auf Ankunft");
        }
    }

    public static boolean isOnPayoutServer() {
        return onPayoutServer;
    }

    public static boolean isConnectingToPayoutServer() {
        return connectingToPayoutServer;
    }

    public static boolean isInQueue() {
        return inQueue;
    }

    public static void onTick(MinecraftClient client) {
        // Stuck in the join queue (server full) — wait for onGameMessage to detect we've left it
        if (inQueue) return;

        // Waiting for the seamless proxy switch to the payout server to complete
        if (arrivalDelay > 0 && client.player != null) {
            if (--arrivalDelay == 0) {
                connectingToPayoutServer = false;
                onPayoutServer = true;
                RemoteControlConfig cfg = RemoteControlConfig.get();
                if (!cfg.spawnCommand.isEmpty()) {
                    homeCommandDelay = HOME_COMMAND_STABILIZE_TICKS;
                }
                LOGGER.info("[RC] Assumed arrival on payout server, teleport command in 3 s");
            }
            return;
        }

        // Send the teleport command a moment after arriving on the payout server
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

