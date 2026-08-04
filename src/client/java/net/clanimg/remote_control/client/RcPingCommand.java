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
                .then(ClientCommandManager.literal("ping")
                    .executes(ctx -> {
                        executePing(ctx.getSource());
                        return 1;
                    })
                )
        );
    }

    private static void executePing(FabricClientCommandSource source) {
        MinecraftClient client = MinecraftClient.getInstance();
        RemoteControlConfig cfg = RemoteControlConfig.get();

        client.execute(() ->
            source.sendFeedback(Text.literal("[RC] Verbindung wird geprüft...").formatted(Formatting.GRAY))
        );

        if (cfg.apiToken.isEmpty()) {
            client.execute(() ->
                source.sendFeedback(statusLine("Token", false, "nicht konfiguriert"))
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
                    source.sendFeedback(statusLine("RC-API", false, "nicht erreichbar"))
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
            JsonObject body = GSON.fromJson(resp.body(), JsonObject.class);

            String rcApi  = body.has("rc_api")   ? body.get("rc_api").getAsString()   : null;
            String db     = body.has("db")        ? body.get("db").getAsString()        : null;
            String mainApi = body.has("main_api") ? body.get("main_api").getAsString()  : null;

            if (rcApi  != null) source.sendFeedback(statusLine("RC-API",       "ok".equals(rcApi),  rcApi));
            if (db     != null) source.sendFeedback(statusLine("Datenbank",    "ok".equals(db),     db));
            if (mainApi != null) source.sendFeedback(statusLine("Clan-IMG API", "ok".equals(mainApi), mainApi));
        } catch (Exception e) {
            source.sendFeedback(statusLine("RC-API", false, "Antwort konnte nicht gelesen werden"));
        }
    }

    private static MutableText statusLine(String label, boolean ok, String detail) {
        String symbol = ok ? "✓" : "✗";
        Formatting color = ok ? Formatting.GREEN : Formatting.RED;
        String suffix = ok ? "" : "  (" + detail + ")";
        return Text.literal("[RC] ").formatted(Formatting.DARK_GRAY)
            .append(Text.literal(label + ": ").formatted(Formatting.WHITE))
            .append(Text.literal(symbol + (ok ? " OK" : " Fehler") + suffix).formatted(color));
    }
}
