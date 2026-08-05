package net.clanimg.remote_control.client;

import com.google.gson.*;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class PaymentPoller {

    private static final Logger LOGGER = LoggerFactory.getLogger("remote_control");
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Verhindert parallele Requests */
    private static final AtomicBoolean running = new AtomicBoolean(false);

    private static int ticks = 0;
    private static final int POLL_INTERVAL = 100; // alle 5 Sekunden (20 ticks/s)

    public static void onTick(MinecraftClient client) {
        if (client.player == null) return;
        if (++ticks < POLL_INTERVAL) return;
        ticks = 0;

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

            // Ausführung muss auf dem Game-Thread passieren
            client.execute(() -> sendPayCommand(client, cfg, id, name, amount));
        }
    }

    private static void sendPayCommand(MinecraftClient client, RemoteControlConfig cfg,
                                       String id, String name, BigDecimal amount) {
        if (client.player == null) return;

        String amountStr = amount.stripTrailingZeros().toPlainString();
        client.player.networkHandler.sendChatCommand("pay " + name + " " + amountStr);

        markDone(cfg, id);
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
}
