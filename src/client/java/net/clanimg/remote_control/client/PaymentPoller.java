package net.clanimg.remote_control.client;

import com.google.gson.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class PaymentPoller {

    private static final Logger LOGGER = LoggerFactory.getLogger("remote_control");
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Verhindert parallele Requests */
    private static final AtomicBoolean running = new AtomicBoolean(false);

    private static int pollTicks = 0;
    private static final int POLL_INTERVAL = 100; // alle 5 Sekunden (20 ticks/s)

    /** Auftraege, die noch per /pay ausgefuehrt werden muessen — einer alle 3 s, nie gleichzeitig */
    private static final Queue<PendingPayment> queue = new ConcurrentLinkedQueue<>();
    private static int commandDelayTicks = 0;
    private static final int COMMAND_INTERVAL_TICKS = 60; // 3 Sekunden Abstand zwischen Befehlen

    /** Zuletzt gesendeter /pay-Befehl — wird kurz auf eine "Spieler nicht online"-Meldung geprueft */
    private static volatile String pendingPaymentId = null;
    private static volatile String pendingPaymentName = null;
    private static int confirmTicksLeft = 0;
    private static final int CONFIRM_WINDOW_TICKS = 40; // 2 Sekunden

    private record PendingPayment(String id, String name, BigDecimal amount) {}

    public static void onTick(MinecraftClient client) {
        if (client.player == null) return;

        // Warten, ob nach dem letzten /pay eine Fehlermeldung (Spieler offline) eintrifft
        if (confirmTicksLeft > 0 && --confirmTicksLeft == 0 && pendingPaymentId != null) {
            markDone(RemoteControlConfig.get(), pendingPaymentId);
            pendingPaymentId = null;
            pendingPaymentName = null;
        }

        // Einen wartenden Auftrag ausführen, sobald der Mindestabstand verstrichen ist
        if (commandDelayTicks > 0) {
            commandDelayTicks--;
        } else {
            PendingPayment next = queue.poll();
            if (next != null) {
                sendPayCommand(client, RemoteControlConfig.get(), next.id(), next.name(), next.amount());
                commandDelayTicks = COMMAND_INTERVAL_TICKS;
            }
        }

        if (++pollTicks < POLL_INTERVAL) return;
        pollTicks = 0;

        if (!running.compareAndSet(false, true)) return;

        RemoteControlConfig cfg = RemoteControlConfig.get();
        if (cfg.apiToken.isEmpty()) {
            running.set(false);
            return;
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(cfg.apiUrl + "/v1/pay/pending"))
                .header("Authorization", "Bearer " + cfg.apiToken)
                .GET()
                .build();

        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() == 200) {
                        processPayments(client, cfg, GSON.fromJson(resp.body(), JsonArray.class));
                    } else {
                        LOGGER.warn("GET /v1/pay/pending returned {}", resp.statusCode());
                    }
                })
                .exceptionally(e -> {
                    LOGGER.error("Failed to reach remote-control-api", e);
                    return null;
                })
                .whenComplete((v, t) -> running.set(false));
    }

    /** Called for every incoming system/game chat message — checks for a "Spieler nicht online" reply to /pay */
    public static void onGameMessage(Text message) {
        String id = pendingPaymentId;
        String name = pendingPaymentName;
        if (id == null || name == null || confirmTicksLeft <= 0) return;

        String text = message.getString();
        if (text.contains(name) && text.toLowerCase(Locale.ROOT).contains("ist nicht online")) {
            LOGGER.warn("Zahlung an {} abgebrochen, Spieler nicht online: {}", name, text);
            markFailed(RemoteControlConfig.get(), id, "Spieler war zum Zeitpunkt der Zahlung nicht online.");
            pendingPaymentId = null;
            pendingPaymentName = null;
            confirmTicksLeft = 0;
        }
    }

    private static void processPayments(MinecraftClient client, RemoteControlConfig cfg, JsonArray payments) {
        if (payments.isEmpty()) return;

        // If a payout server is configured, only process payments when there
        if (!cfg.payoutServer.isEmpty() && !AutoReconnectManager.isOnPayoutServer()) {
            client.execute(() -> AutoReconnectManager.switchToPayoutServer(client));
            return;
        }

        for (JsonElement el : payments) {
            JsonObject p = el.getAsJsonObject();
            String id = p.get("id").getAsString();
            String name = p.get("name").getAsString();
            BigDecimal amount = p.get("amount").getAsBigDecimal();

            // Nur einreihen — die Ausführung erfolgt getaktet in onTick (3 s Abstand)
            queue.add(new PendingPayment(id, name, amount));
        }
    }

    private static void sendPayCommand(MinecraftClient client, RemoteControlConfig cfg,
                                       String id, String name, BigDecimal amount) {
        if (client.player == null) return;

        String amountStr = amount.stripTrailingZeros().toPlainString();
        client.player.networkHandler.sendChatCommand("pay " + name + " " + amountStr);

        // Erst nach dem Bestaetigungsfenster (siehe onTick/onGameMessage) als erledigt melden
        pendingPaymentId = id;
        pendingPaymentName = name;
        confirmTicksLeft = CONFIRM_WINDOW_TICKS;
    }

    private static void markDone(RemoteControlConfig cfg, String id) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(cfg.apiUrl + "/v1/pay/" + id + "/done"))
                .header("Authorization", "Bearer " + cfg.apiToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .exceptionally(e -> {
                    LOGGER.error("Failed to mark payment {} as done", id, e);
                    return null;
                });
    }

    private static void markFailed(RemoteControlConfig cfg, String id, String reason) {
        String json = "{\"reason\":\"" + reason.replace("\"", "'") + "\"}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(cfg.apiUrl + "/v1/pay/" + id + "/fail"))
                .header("Authorization", "Bearer " + cfg.apiToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .exceptionally(e -> {
                    LOGGER.error("Failed to mark payment {} as failed", id, e);
                    return null;
                });
    }
}


