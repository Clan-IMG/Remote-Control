package net.clanimg.remote_control.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
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
                .then(ClientCommandManager.literal("url")
                    .then(ClientCommandManager.argument("url", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String url = StringArgumentType.getString(ctx, "url");
                            setUrl(ctx.getSource(), url);
                            return 1;
                        })
                    )
                )
                .then(ClientCommandManager.literal("token")
                    .then(ClientCommandManager.argument("token", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String token = StringArgumentType.getString(ctx, "token");
                            setToken(ctx.getSource(), token);
                            return 1;
                        })
                    )
                )
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

    private static void setUrl(FabricClientCommandSource source, String url) {
        RemoteControlConfig cfg = RemoteControlConfig.get();
        cfg.apiUrl = url;
        cfg.save();
        source.sendFeedback(Text.literal("[RC] API-URL gespeichert: " + url).formatted(Formatting.GREEN));
    }

    private static void setToken(FabricClientCommandSource source, String token) {
        RemoteControlConfig cfg = RemoteControlConfig.get();
        cfg.apiToken = token;
        cfg.save();
        String masked = token.length() > 8 ? token.substring(0, 8) + "..." : "***";
        source.sendFeedback(Text.literal("[RC] Token gespeichert: " + masked).formatted(Formatting.GREEN));
    }

    private static void showStatus(FabricClientCommandSource source) {
        RemoteControlConfig cfg = RemoteControlConfig.get();
        source.sendFeedback(Text.literal("[RC] Status:").formatted(Formatting.AQUA));
        source.sendFeedback(Text.literal("  URL: " + cfg.apiUrl).formatted(Formatting.GRAY));
        String tokenStatus = cfg.apiToken.isEmpty() ? "nicht gesetzt" : "gesetzt (" + cfg.apiToken.substring(0, Math.min(8, cfg.apiToken.length())) + "...)";
        source.sendFeedback(Text.literal("  Token: " + tokenStatus).formatted(Formatting.GRAY));
    }

    private static void executePing(FabricClientCommandSource source) {
        MinecraftClient client = MinecraftClient.getInstance();
        RemoteControlConfig cfg = RemoteControlConfig.get();

        client.execute(() ->
            source.sendFeedback(Text.literal("[RC] Verbindung wird geprüft...").formatted(Formatting.GRAY))
        );

        if (cfg.apiToken.isEmpty()) {
            client.execute(() ->
                source.sendFeedback(statusLine("Token", false, "nicht konfiguriert - nutze: /rc token <token>"))
            );
            return;
        }

        if (cfg.apiUrl.isEmpty() || cfg.apiUrl.equals("http://localhost:8000")) {
            client.execute(() ->
                source.sendFeedback(statusLine("URL", false, "nicht konfiguriert - nutze: /rc url <url>"))
            );
            return;
        }

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(cfg.apiUrl + "/v1/ping"))
            .header("Authorization", "Bearer " + cfg.apiToken)
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
}

