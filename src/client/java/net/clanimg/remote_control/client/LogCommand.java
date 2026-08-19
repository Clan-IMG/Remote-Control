package net.clanimg.remote_control.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/** /rc log <auftragnummer> [zahl] – holt Nachrichten direkt von der Chat-API. */
public class LogCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("remote_control");
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static String fmtSend    = "&b&lFREUNDE&r &8\u00bb &r&7[&cDu &7-> &c%player%&7] &r&f%message%";
    private static String fmtReceive = "&b&lFREUNDE&r &8\u00bb &r&7[&c%player% &7-> &cMir&7] &r&f%message%";

    static {
        try (InputStream in = LogCommand.class.getResourceAsStream("/config-chat.json")) {
            if (in != null) {
                JsonObject cfg = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
                if (cfg.has("dm-message-format-send"))    fmtSend    = cfg.get("dm-message-format-send").getAsString();
                if (cfg.has("dm-message-format-receive")) fmtReceive = cfg.get("dm-message-format-receive").getAsString();
            }
        } catch (Exception e) {
            // keep defaults
        }
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            ClientCommandManager.literal("rc")
                .then(ClientCommandManager.literal("log")
                    .then(ClientCommandManager.argument("auftragnummer", StringArgumentType.word())
                        .then(ClientCommandManager.argument("zahl", IntegerArgumentType.integer(1, 100))
                            .executes(ctx -> {
                                fetchAndShow(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "auftragnummer"),
                                    IntegerArgumentType.getInteger(ctx, "zahl"));
                                return 1;
                            })
                        )
                        .executes(ctx -> {
                            fetchAndShow(ctx.getSource(),
                                StringArgumentType.getString(ctx, "auftragnummer"), 10);
                            return 1;
                        })
                    )
                    .executes(ctx -> {
                        showHelp(ctx.getSource());
                        return 1;
                    })
                )
        );
    }
    
    private static void fetchAndShow(FabricClientCommandSource source, String orderNumber, int count) {
        RemoteControlConfig cfg = RemoteControlConfig.get();
        if (cfg.chatApiToken.isEmpty()) {
            source.sendFeedback(Text.literal("[RC] Chat API Token nicht gesetzt (R+C)").formatted(Formatting.RED));
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        String url = cfg.chatApiUrl + "/orders/" + orderNumber + "/messages";
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("X-API-Token", cfg.chatApiToken)
            .GET()
            .build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenAccept(resp -> client.execute(() -> handleResponse(source, orderNumber, resp, count)))
            .exceptionally(e -> {
                LOGGER.error("Failed to fetch messages", e);
                client.execute(() -> source.sendFeedback(
                    Text.literal("[RC] Netzwerkfehler: " + e.getMessage()).formatted(Formatting.RED)));
                return null;
            });
    }

    private static void handleResponse(FabricClientCommandSource source, String orderNumber,
                                       HttpResponse<String> resp, int count) {
        if (resp.statusCode() == 401) {
            source.sendFeedback(Text.literal("[RC] Token ungültig (401)").formatted(Formatting.RED));
            return;
        }
        if (resp.statusCode() == 404) {
            source.sendFeedback(Text.literal("[RC] Auftrag nicht gefunden: " + orderNumber).formatted(Formatting.YELLOW));
            return;
        }
        if (resp.statusCode() != 200) {
            source.sendFeedback(Text.literal("[RC] API Fehler: HTTP " + resp.statusCode()).formatted(Formatting.RED));
            return;
        }
        try {
            JsonObject json = GSON.fromJson(resp.body(), JsonObject.class);
            JsonArray messages = json.getAsJsonArray("messages");
            // customerName is always the order's customer (= %player% in both templates)
            String customerName = json.has("customerName") && !json.get("customerName").isJsonNull()
                ? json.get("customerName").getAsString() : "Unbekannt";
            int start = Math.max(0, messages.size() - count);
            if (start >= messages.size()) return;
            for (int i = start; i < messages.size(); i++) {
                source.sendFeedback(formatMsg(messages.get(i).getAsJsonObject(), customerName));
            }
        } catch (Exception e) {
            LOGGER.error("Failed to parse messages", e);
            source.sendFeedback(Text.literal("[RC] Parse-Fehler").formatted(Formatting.RED));
        }
    }

    private static Text formatMsg(JsonObject msg, String customerName) {
        String body = msg.has("body") ? msg.get("body").getAsString().replaceAll("\\s*[\\r\\n]+\\s*", " ").trim() : "(leer)";
        String role = msg.has("senderRole") ? msg.get("senderRole").getAsString() : "customer";
        String template = "team".equals(role) ? fmtSend : fmtReceive;
        String formatted = template
            .replace("%player%", customerName)
            .replace("%message%", body);

        return fromLegacyText(formatted);
    }

    /** Wandelt &-Farbcodes in einen Minecraft MutableText um. */
    private static MutableText fromLegacyText(String text) {
        MutableText result = Text.empty();
        Formatting currentColor = null;
        boolean bold = false, italic = false, underline = false, strikethrough = false, obfuscated = false;

        int len = text.length();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '&' && i + 1 < len) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                Formatting fmt = Formatting.byCode(code);
                if (fmt != null) {
                    // flush current segment
                    if (current.length() > 0) {
                        MutableText part = Text.literal(current.toString());
                        if (currentColor != null) part = part.formatted(currentColor);
                        if (bold)          part = part.formatted(Formatting.BOLD);
                        if (italic)        part = part.formatted(Formatting.ITALIC);
                        if (underline)     part = part.formatted(Formatting.UNDERLINE);
                        if (strikethrough) part = part.formatted(Formatting.STRIKETHROUGH);
                        if (obfuscated)    part = part.formatted(Formatting.OBFUSCATED);
                        result.append(part);
                        current.setLength(0);
                    }
                    if (fmt == Formatting.RESET) {
                        currentColor = null; bold = false; italic = false;
                        underline = false; strikethrough = false; obfuscated = false;
                    } else if (fmt.isColor()) {
                        currentColor = fmt;
                        bold = false; italic = false; underline = false; strikethrough = false; obfuscated = false;
                    } else {
                        if (fmt == Formatting.BOLD)          bold = true;
                        if (fmt == Formatting.ITALIC)        italic = true;
                        if (fmt == Formatting.UNDERLINE)     underline = true;
                        if (fmt == Formatting.STRIKETHROUGH) strikethrough = true;
                        if (fmt == Formatting.OBFUSCATED)    obfuscated = true;
                    }
                    i++;
                    continue;
                }
            }
            current.append(c);
        }
        if (current.length() > 0) {
            MutableText part = Text.literal(current.toString());
            if (currentColor != null) part = part.formatted(currentColor);
            if (bold)          part = part.formatted(Formatting.BOLD);
            if (italic)        part = part.formatted(Formatting.ITALIC);
            if (underline)     part = part.formatted(Formatting.UNDERLINE);
            if (strikethrough) part = part.formatted(Formatting.STRIKETHROUGH);
            if (obfuscated)    part = part.formatted(Formatting.OBFUSCATED);
            result.append(part);
        }
        return result;
    }

    private static void showHelp(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("[RC] /rc log <auftragnummer> [zahl]").formatted(Formatting.AQUA));
        source.sendFeedback(
            Text.literal("  /rc log <auftragnummer> [zahl]")
                .formatted(Formatting.GRAY)
        );
        source.sendFeedback(
            Text.literal("    - auftragnummer: z.B. A067, B123 (3-stellig)")
                .formatted(Formatting.GRAY)
        );
        source.sendFeedback(
            Text.literal("    - zahl: Anzahl der letzten Nachrichten (Standard: 10, Max: 100)")
                .formatted(Formatting.GRAY)
        );
        source.sendFeedback(
            Text.literal("  Beispiel: /rc log A067 15")
                .formatted(Formatting.GRAY)
        );
    }
}
