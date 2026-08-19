package net.clanimg.remote_control.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class RcPingCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("remote_control");
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            ClientCommandManager.literal("rc")
                .then(ClientCommandManager.literal("status")
                    .executes(ctx -> {
                        showStatus(ctx.getSource());
                        return 1;
                    })
                )
                .then(ClientCommandManager.literal("ping")
                    .executes(ctx -> {
                        executePing(ctx.getSource());
                        return 1;
                    })
                )
        );
    }

    private static void showStatus(FabricClientCommandSource source) {
        RemoteControlConfig cfg = RemoteControlConfig.get();
        source.sendFeedback(Text.literal("[RC] Status:").formatted(Formatting.AQUA));
        source.sendFeedback(Text.literal("  RC API: " + cfg.rcApiUrl).formatted(Formatting.GRAY));
        String rcTokenStatus = cfg.rcApiToken.isEmpty() ? "nicht gesetzt" : "gesetzt (" + cfg.rcApiToken.substring(0, Math.min(8, cfg.rcApiToken.length())) + "...)";
        source.sendFeedback(Text.literal("  RC Token: " + rcTokenStatus).formatted(Formatting.GRAY));
        source.sendFeedback(Text.literal("  Chat API: " + cfg.chatApiUrl).formatted(Formatting.GRAY));
        String chatTokenStatus = cfg.chatApiToken.isEmpty() ? "nicht gesetzt" : "gesetzt (" + cfg.chatApiToken.substring(0, Math.min(8, cfg.chatApiToken.length())) + "...)";
        source.sendFeedback(Text.literal("  Chat Token: " + chatTokenStatus).formatted(Formatting.GRAY));
    }

    private static void executePing(FabricClientCommandSource source) {
        MinecraftClient client = MinecraftClient.getInstance();
        RemoteControlConfig cfg = RemoteControlConfig.get();

        client.execute(() ->
            source.sendFeedback(Text.literal("[RC] Verbindung wird geprüft...").formatted(Formatting.GRAY))
        );

        client.execute(() -> source.sendFeedback(payoutStatusLine(cfg)));

        if (cfg.rcApiToken.isEmpty()) {
            client.execute(() ->
                source.sendFeedback(statusLine("RC Token", false, "nicht konfiguriert - öffne Einstellungen mit R+C"))
            );
            return;
        }

        if (cfg.rcApiUrl.isEmpty()) {
            client.execute(() ->
                source.sendFeedback(statusLine("RC URL", false, "nicht konfiguriert - öffne Einstellungen mit R+C"))
            );
            return;
        }

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(cfg.rcApiUrl + "/v1/ping"))
            .header("Authorization", "Bearer " + cfg.rcApiToken)
            .GET()
            .build();

        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenAccept(resp -> client.execute(() -> handleResponse(source, resp)))
            .exceptionally(e -> {
                LOGGER.error("Ping failed", e);
                client.execute(() ->
                    source.sendFeedback(statusLine("RC-API", false, "nicht erreichbar: " + e.getMessage()))
                );
                return null;
            });
    }

    private static void handleResponse(FabricClientCommandSource source, HttpResponse<String> resp) {
        if (resp.statusCode() == 401) {
            source.sendFeedback(statusLine("Token", false, "ungültig (401)"));
            return;
        }
        if (resp.statusCode() != 200) {
            source.sendFeedback(statusLine("RC-API", false, "HTTP " + resp.statusCode()));
            return;
        }

        try {
            JsonObject json = GSON.fromJson(resp.body(), JsonObject.class);
            if (json.has("rc_api")) {
                boolean ok = "ok".equals(json.get("rc_api").getAsString());
                source.sendFeedback(statusLine("RC-API", ok, ok ? "ok" : json.get("rc_api").getAsString()));
            }
            if (json.has("db")) {
                boolean ok = "ok".equals(json.get("db").getAsString());
                source.sendFeedback(statusLine("Datenbank", ok, ok ? "ok" : json.get("db").getAsString()));
            }
            if (json.has("main_api")) {
                boolean ok = "ok".equals(json.get("main_api").getAsString());
                source.sendFeedback(statusLine("Haupt-API", ok, ok ? "ok" : json.get("main_api").getAsString()));
            }
        } catch (Exception e) {
            LOGGER.error("Failed to parse response", e);
            source.sendFeedback(statusLine("Antwort", false, "konnte nicht geparst werden"));
        }
    }

    private static MutableText statusLine(String label, boolean ok, String detail) {
        String symbol = ok ? "✓" : "✗";
        Formatting color = ok ? Formatting.GREEN : Formatting.RED;
        return Text.literal("[RC] " + label + ": " + symbol + " " + detail).formatted(color);
    }

    /** Zeigt, ob Zahlungen aktuell tatsaechlich ausgefuehrt werden koennen - unabhaengig von der API-Erreichbarkeit. */
    private static MutableText payoutStatusLine(RemoteControlConfig cfg) {
        if (cfg.payoutServer.isEmpty()) {
            return Text.literal("[RC] Payout-Server: - nicht konfiguriert").formatted(Formatting.GRAY);
        }
        if (AutoReconnectManager.isInQueue()) {
            return statusLine("Payout-Server", false, "wartet in der Warteschlange (Server voll) - Zahlungen pausiert");
        }
        if (AutoReconnectManager.isConnectingToPayoutServer()) {
            return statusLine("Payout-Server", false, "wechselt gerade zu \"" + cfg.payoutServer + "\"");
        }
        if (AutoReconnectManager.isOnPayoutServer()) {
            return statusLine("Payout-Server", true, "verbunden (\"" + cfg.payoutServer + "\")");
        }
        return statusLine("Payout-Server", false, "nicht verbunden");
    }
}

