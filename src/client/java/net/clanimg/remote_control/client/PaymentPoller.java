package net.clanimg.remote_control.client;

import com.google.gson.*;
import net.clanimg.remote_control.client.PaymentJournal.Entry;
import net.clanimg.remote_control.client.PaymentJournal.State;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Fuehrt Auszahlungen per /pay aus und meldet das Ergebnis an die remote-control-api.
 *
 * Regeln:
 *  - Jede Zahlung steht im PaymentJournal, BEVOR /pay rausgeht, und wird nie zweimal automatisch ausgefuehrt.
 *  - Zusaetzlich muss die RC-API jede Zahlung per /start freigeben; sie gibt jede Zahlung nur einmal frei
 *    (zweite, unabhaengige Sicherung - greift auch, wenn das lokale Journal verloren geht).
 *  - Es ist immer nur eine Zahlung unterwegs; die naechste startet erst, wenn die vorige geklaert ist.
 *  - "Erledigt" nur bei pay_message.fromme mit passendem Namen UND Betrag, "fehlgeschlagen" nur bei
 *    einer pay_message mit code 400 (siehe chat-format.yml).
 *  - Keine (oder eine unpassende) Antwort heisst NICHT fehlgeschlagen: die Zahlung wird unbestaetigt,
 *    bleibt auf der Website gesperrt und weitere Auszahlungen pausieren bis /rc pay resolve.
 *  - Ergebnisse werden so lange gemeldet, bis die API sie mit 2xx annimmt.
 *
 * Alle Zustaende werden nur auf dem Client-Thread veraendert (HTTP-Antworten laufen ueber client.execute).
 */
public class PaymentPoller {

    private static final Logger LOGGER = LoggerFactory.getLogger("remote_control");
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /** Java- und Bedrock-(Floodgate-)Namen - alles andere koennte weitere /pay-Argumente einschleusen */
    private static final Pattern VALID_NAME = Pattern.compile("[.*]?[A-Za-z0-9_]{1,16}");

    /** Verhindert parallele Abfragen */
    private static final AtomicBoolean polling = new AtomicBoolean(false);

    private static int pollTicks = 0;
    private static final int POLL_INTERVAL = 100; // alle 5 Sekunden - zugleich das "Bot online"-Signal fuer die API

    /**
     * Von der API erhaltene, noch nicht gestartete Auftraege. /pending liefert jeden Auftrag nur einmal
     * aus (danach 5 min reserviert) - deshalb wird hier nur ergaenzt, nie verworfen. Ein veralteter
     * Eintrag ist harmlos: /start laesst ihn nicht mehr durch.
     */
    private static final Deque<PendingPayment> queue = new ArrayDeque<>();
    private static int commandDelayTicks = 0;
    private static final int COMMAND_INTERVAL_TICKS = 60; // 3 Sekunden Abstand nach jeder geklaerten Zahlung
    private static final int START_RETRY_TICKS = 100;     // 5 Sekunden bis zum naechsten /start-Versuch

    /** Die eine Zahlung, die gerade gestartet wird (STARTING) oder auf die Antwort des Servers wartet (AWAITING) */
    private static Entry inFlight = null;
    /** Nur die Antwort auf den letzten /start zaehlt */
    private static int startSeq = 0;
    private static int confirmTicksLeft = 0;
    private static final int CONFIRM_WINDOW_TICKS = 600; // 30 Sekunden - reicht auch bei Lag

    /** Meldungen an die API, die gerade unterwegs sind */
    private static final Set<String> reportsInFlight = new HashSet<>();
    /** Schon gewarnte IDs, damit das Log nicht alle 5 s dieselbe Warnung bekommt */
    private static final Set<String> warnedIds = new HashSet<>();

    private record PendingPayment(String id, String name, BigDecimal amount) {}

    public static void onTick(MinecraftClient client) {
        if (client.player == null) return;

        if (inFlight != null && inFlight.state == State.AWAITING && --confirmTicksLeft <= 0) {
            resolveInFlight(client, State.UNCONFIRMED,
                    "Keine Antwort vom Server innerhalb von 30 s - unklar, ob das Geld angekommen ist.");
        }

        if (commandDelayTicks > 0) {
            commandDelayTicks--;
        } else if (inFlight == null) {
            // Zuerst eine Zahlung fortsetzen, deren /start-Antwort verloren ging, dann neue
            Entry resume = PaymentJournal.firstStarting();
            if ((resume != null || !queue.isEmpty()) && blockReason() == null) {
                if (resume != null) {
                    startPayment(client, resume);
                } else {
                    beginPayment(client, queue.poll());
                }
            }
        }

        if (++pollTicks < POLL_INTERVAL) return;
        pollTicks = 0;

        RemoteControlConfig cfg = RemoteControlConfig.get();
        if (cfg.rcApiToken.isEmpty()) return;
        try {
            flushReports(client, cfg);
            fetchPending(client, cfg);
        } catch (IllegalArgumentException e) {
            LOGGER.error("[RC] Ungueltige RC-API-URL: {}", cfg.rcApiUrl, e);
        }
    }

    /** Called for every incoming system/game chat message — waits for the /pay success or failure reply */
    public static void onGameMessage(Text message) {
        ChatFormat.Match m = ChatFormat.match(ChatFormat.PAY, message.getString());
        if (m == null) return;

        Entry e = inFlight;
        if (e == null || e.state != State.AWAITING) {
            // Waehrend /start laeuft ist noch kein /pay raus - die Nachricht kann nicht zu dieser Zahlung gehoeren
            if ("fromme".equals(m.key())) noteLateConfirmation(m);
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();

        if ("fromme".equals(m.key())) {
            if (!e.name.equalsIgnoreCase(m.player())) {
                LOGGER.warn("[RC] Bestaetigung fuer {} passt nicht zur laufenden Zahlung an {} - ignoriert: {}", m.player(), e.name, m.line());
                return;
            }
            if (amountMatches(m.amount(), e.amount)) {
                resolveInFlight(client, State.DONE, null);
            } else {
                resolveInFlight(client, State.UNCONFIRMED, "Server bestätigt Zahlung an " + m.player()
                        + ", aber der Betrag \"" + m.amount() + "\" passt nicht zum Auftrag (" + plain(e.amount) + "$).");
            }
            return;
        }

        if (m.code() >= 400) {
            // Nennt die Fehlermeldung einen Spieler, muss es der Empfaenger dieser Zahlung sein
            if (m.player() != null && !e.name.equalsIgnoreCase(m.player())) return;
            resolveInFlight(client, State.FAILED, failureReason(m));
        }
        // Andere code-200-Nachrichten (z.B. "tome": jemand hat dem Bot Geld gegeben) betreffen keine Auszahlung
    }

    public static void onDisconnect() {
        if (inFlight == null) return;
        if (inFlight.state == State.STARTING) {
            // /pay wurde noch nicht gesendet - der Start wird spaeter mit demselben Token fortgesetzt
            inFlight = null;
            return;
        }
        resolveInFlight(MinecraftClient.getInstance(), State.UNCONFIRMED,
                "Verbindung zum Server während der Bestätigung getrennt - unklar, ob das Geld angekommen ist.");
    }

    public static boolean isPaymentInFlight() {
        return inFlight != null;
    }

    /** Warum gerade keine Zahlung ausgefuehrt werden darf, oder null wenn alles bereit ist. */
    public static String blockReason() {
        if (!ChatFormat.isLoaded()) return "chat-format.yml nicht geladen (" + ChatFormat.loadError() + ")";
        if (!PaymentJournal.isUsable()) return "Zahlungsjournal beschädigt (" + PaymentJournal.loadError() + ")";
        long unconfirmed = PaymentJournal.count(State.UNCONFIRMED);
        if (unconfirmed > 0) return unconfirmed + " unbestätigte Zahlung(en) - mit /rc pay klären";
        RemoteControlConfig cfg = RemoteControlConfig.get();
        if (!cfg.payoutServer.isEmpty() && (!AutoReconnectManager.isOnPayoutServer()
                || AutoReconnectManager.isConnectingToPayoutServer() || AutoReconnectManager.isInQueue())) {
            return "nicht auf dem Payout-Server";
        }
        return null;
    }

    /** /rc pay resolve: eine unbestaetigte Zahlung nach manueller Pruefung abschliessen. Gibt einen Fehlertext oder null zurueck. */
    public static String resolveManually(String id, boolean arrived) {
        Entry e = PaymentJournal.get(id);
        if (e == null) return "Keine Zahlung mit der ID " + id + " im Journal.";
        if (e.state != State.UNCONFIRMED) return "Zahlung " + id + " ist nicht unbestätigt (Status: " + e.state + ").";
        PaymentJournal.resolve(e, arrived ? State.DONE : State.FAILED,
                arrived ? null : "Zahlung ist laut manueller Prüfung nicht angekommen.");
        LOGGER.warn("[RC] Zahlung {} an {} ({}$) manuell als {} abgeschlossen", e.id, e.name, plain(e.amount), e.state);
        flushReports(MinecraftClient.getInstance(), RemoteControlConfig.get());
        return null;
    }

    /** /rc pay retry: eine von der API abgelehnte Meldung erneut senden. Gibt einen Fehlertext oder null zurueck. */
    public static String retryReport(String id) {
        Entry e = PaymentJournal.get(id);
        if (e == null) return "Keine Zahlung mit der ID " + id + " im Journal.";
        if (e.reportError == null) return "Für Zahlung " + id + " liegt kein Meldefehler vor.";
        PaymentJournal.clearReportError(e);
        flushReports(MinecraftClient.getInstance(), RemoteControlConfig.get());
        return null;
    }

    private static void fetchPending(MinecraftClient client, RemoteControlConfig cfg) {
        if (!polling.compareAndSet(false, true)) return;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(cfg.rcApiUrl + "/v1/pay/pending"))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + cfg.rcApiToken)
                .GET()
                .build();

        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, err) -> {
                    polling.set(false);
                    client.execute(() -> {
                        if (err != null) {
                            LOGGER.error("Failed to reach remote-control-api", err);
                        } else if (resp.statusCode() != 200) {
                            LOGGER.warn("GET /v1/pay/pending returned {}", resp.statusCode());
                        } else {
                            try {
                                processPayments(client, cfg, GSON.fromJson(resp.body(), JsonArray.class));
                            } catch (RuntimeException e) {
                                LOGGER.error("[RC] Antwort von /v1/pay/pending nicht lesbar: {}", resp.body(), e);
                            }
                        }
                    });
                });
    }

    private static void processPayments(MinecraftClient client, RemoteControlConfig cfg, JsonArray payments) {
        if (payments != null) {
            for (JsonElement el : payments) addToQueue(el);
        }

        // If a payout server is configured, switch there - the queued payments wait (see blockReason) until we've arrived
        if ((!queue.isEmpty() || PaymentJournal.firstStarting() != null) && inFlight == null
                && !cfg.payoutServer.isEmpty() && !AutoReconnectManager.isOnPayoutServer()) {
            AutoReconnectManager.switchToPayoutServer(client);
        }
    }

    private static void addToQueue(JsonElement el) {
        PendingPayment p = parse(el);
        if (p == null) return;

        if (inFlight != null && inFlight.id.equals(p.id())) return;
        if (queue.stream().anyMatch(q -> q.id().equals(p.id()))) return;

        Entry known = PaymentJournal.get(p.id());
        if (known != null) {
            // Schon einmal gestartet - wird NIE automatisch wiederholt, egal was die API meldet
            if (known.reported && warnedIds.add(p.id())) {
                LOGGER.warn("[RC] API meldet bereits abgeschlossene Zahlung {} ({}) erneut als offen - wird nicht erneut ausgefuehrt",
                        p.id(), known.state);
            }
            return;
        }

        String invalid = validate(p);
        if (invalid != null) {
            // Nie ausgefuehrt - der Fehlschlag ist also sicher und darf gemeldet werden
            LOGGER.warn("[RC] Auftrag {} abgelehnt: {}", p.id(), invalid);
            PaymentJournal.add(p.id(), p.name(), p.amount(), State.FAILED, invalid);
            return;
        }

        queue.add(p);
    }

    private static PendingPayment parse(JsonElement el) {
        try {
            JsonObject o = el.getAsJsonObject();
            return new PendingPayment(o.get("id").getAsString(), o.get("name").getAsString().trim(), o.get("amount").getAsBigDecimal());
        } catch (RuntimeException e) {
            LOGGER.error("[RC] Unlesbarer Auftrag von der API ignoriert: {}", el);
            return null;
        }
    }

    private static String validate(PendingPayment p) {
        if (!VALID_NAME.matcher(p.name()).matches()) {
            return "Ungültiger Minecraft-Name \"" + p.name() + "\" - Auszahlung wurde nicht ausgeführt.";
        }
        if (p.amount().signum() <= 0 || p.amount().stripTrailingZeros().scale() > 2) {
            return "Ungültiger Betrag " + p.amount().toPlainString() + " - Auszahlung wurde nicht ausgeführt.";
        }
        return null;
    }

    private static void beginPayment(MinecraftClient client, PendingPayment p) {
        // Erst ins Journal, dann /start - ab hier wird diese ID nie wieder neu begonnen
        Entry entry = PaymentJournal.add(p.id(), p.name(), p.amount(), State.STARTING, null);
        if (entry == null) {
            LOGGER.error("[RC] Zahlung {} konnte nicht im Journal gespeichert werden - wird NICHT ausgefuehrt", p.id());
            commandDelayTicks = COMMAND_INTERVAL_TICKS;
            return;
        }
        startPayment(client, entry);
    }

    /** Laesst sich die Zahlung von der RC-API freigeben - /pay geht erst nach einer 2xx-Antwort raus. */
    private static void startPayment(MinecraftClient client, Entry e) {
        RemoteControlConfig cfg = RemoteControlConfig.get();
        JsonObject body = new JsonObject();
        body.addProperty("token", e.token);
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.rcApiUrl + "/v1/pay/" + URLEncoder.encode(e.id, StandardCharsets.UTF_8) + "/start"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + cfg.rcApiToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException ex) {
            LOGGER.error("[RC] Ungueltige RC-API-URL: {}", cfg.rcApiUrl, ex);
            commandDelayTicks = START_RETRY_TICKS;
            return;
        }

        inFlight = e;
        int seq = ++startSeq;
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, err) -> client.execute(() -> onStartResult(client, e, seq, resp, err)));
    }

    private static void onStartResult(MinecraftClient client, Entry e, int seq, HttpResponse<String> resp, Throwable err) {
        // Veraltete Antwort (Verbindung getrennt, neuerer Versuch, schon gestartet) - nur der aktuelle /start zaehlt,
        // sonst koennte eine doppelt ankommende 2xx-Antwort ein zweites /pay ausloesen
        if (inFlight != e || seq != startSeq || e.state != State.STARTING) return;
        int status = err == null ? resp.statusCode() : -1;

        if (status >= 200 && status < 300) {
            if (client.player == null || blockReason() != null) {
                // Inzwischen nicht mehr bereit (z.B. Serverwechsel) - /start ist mit demselben Token wiederholbar
                inFlight = null;
                commandDelayTicks = COMMAND_INTERVAL_TICKS;
                return;
            }
            sendPayCommand(client, e);
        } else if (status == -1 || isRetryable(status)) {
            // Unklar, ob die RC-API den Start gespeichert hat - /pay ist nicht raus, und derselbe Token
            // macht den naechsten Versuch gefahrlos
            LOGGER.warn("[RC] /start fuer Zahlung {} nicht bestaetigt ({}), neuer Versuch in 5 s",
                    e.id, err != null ? err.toString() : "HTTP " + status);
            inFlight = null;
            commandDelayTicks = START_RETRY_TICKS;
        } else {
            // Unbekannt oder schon gestartet/abgeschlossen (404/409) - nie ausfuehren. /pay wurde nicht
            // gesendet, also bleibt nichts offen. (404 auch, wenn die RC-API noch kein /start kennt.)
            LOGGER.error("[RC] RC-API gibt Zahlung {} nicht frei (HTTP {}): {} - wird nicht ausgefuehrt", e.id, status, resp.body());
            PaymentJournal.remove(e);
            inFlight = null;
            commandDelayTicks = COMMAND_INTERVAL_TICKS;
        }
    }

    private static void sendPayCommand(MinecraftClient client, Entry e) {
        // Erst AWAITING speichern, dann zahlen: stuerzt der Client genau dazwischen ab, gilt die Zahlung nach
        // dem Neustart als unbestaetigt statt als "noch nicht gesendet"
        if (!PaymentJournal.markAwaiting(e)) {
            LOGGER.error("[RC] Zahlung {} konnte nicht im Journal gespeichert werden - /pay wird NICHT gesendet", e.id);
            inFlight = null;
            commandDelayTicks = START_RETRY_TICKS;
            return;
        }

        confirmTicksLeft = CONFIRM_WINDOW_TICKS;
        try {
            client.player.networkHandler.sendChatCommand("pay " + e.name + " " + plain(e.amount));
            LOGGER.info("[RC] /pay {} {} gesendet (Auftrag {})", e.name, plain(e.amount), e.id);
        } catch (RuntimeException ex) {
            LOGGER.error("[RC] /pay fuer Auftrag {} konnte nicht gesendet werden", e.id, ex);
            resolveInFlight(client, State.UNCONFIRMED, "Senden des /pay-Befehls fehlgeschlagen: " + ex.getMessage());
        }
    }

    /** Antworten, bei denen die API nichts entschieden hat (Token/Netz/Ueberlast) - spaeter erneut versuchen. */
    private static boolean isRetryable(int status) {
        return status == 401 || status == 403 || status == 408 || status == 429 || status >= 500;
    }

    private static void resolveInFlight(MinecraftClient client, State state, String reason) {
        Entry e = inFlight;
        inFlight = null;
        confirmTicksLeft = 0;
        commandDelayTicks = COMMAND_INTERVAL_TICKS;
        PaymentJournal.resolve(e, state, reason);

        switch (state) {
            case DONE -> LOGGER.info("[RC] Zahlung {} an {} ({}$) bestaetigt", e.id, e.name, plain(e.amount));
            case FAILED -> LOGGER.warn("[RC] Zahlung {} an {} ({}$) fehlgeschlagen: {}", e.id, e.name, plain(e.amount), reason);
            default -> {
                LOGGER.error("[RC] Zahlung {} an {} ({}$) UNBESTAETIGT - Auszahlungen pausiert: {}", e.id, e.name, plain(e.amount), reason);
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("[RC] Zahlung " + e.id + " an " + e.name + " (" + plain(e.amount)
                            + "$) unbestätigt - Auszahlungen pausiert. Prüfen mit /rc pay").formatted(Formatting.RED), false);
                }
                return;
            }
        }
        flushReports(client, RemoteControlConfig.get());
    }

    /** Meldet jedes noch offene Ergebnis an die API, bis sie es mit 2xx annimmt. */
    private static void flushReports(MinecraftClient client, RemoteControlConfig cfg) {
        if (cfg.rcApiToken.isEmpty()) return;
        for (Entry e : PaymentJournal.pendingReports()) {
            if (reportsInFlight.contains(e.id)) continue;

            boolean done = e.state == State.DONE;
            HttpRequest.Builder req;
            try {
                req = HttpRequest.newBuilder()
                        .uri(URI.create(cfg.rcApiUrl + "/v1/pay/" + URLEncoder.encode(e.id, StandardCharsets.UTF_8) + (done ? "/done" : "/fail")))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Authorization", "Bearer " + cfg.rcApiToken);
            } catch (IllegalArgumentException ex) {
                LOGGER.error("[RC] Ungueltige RC-API-URL {} - Ergebnis fuer Zahlung {} bleibt gespeichert", cfg.rcApiUrl, e.id, ex);
                return;
            }
            if (done) {
                req.POST(HttpRequest.BodyPublishers.noBody());
            } else {
                JsonObject body = new JsonObject();
                body.addProperty("reason", e.reason == null ? "Unbekannter Fehler" : e.reason);
                req.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
            }

            reportsInFlight.add(e.id);
            HTTP.sendAsync(req.build(), HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, err) -> client.execute(() -> {
                        reportsInFlight.remove(e.id);
                        if (err != null) {
                            LOGGER.warn("[RC] Ergebnis fuer Zahlung {} nicht zugestellt, neuer Versuch in 5 s: {}", e.id, err.toString());
                            return;
                        }
                        int status = resp.statusCode();
                        if (status >= 200 && status < 300) {
                            PaymentJournal.markReported(e);
                            LOGGER.info("[RC] Ergebnis fuer Zahlung {} ({}) an die API gemeldet", e.id, e.state);
                        } else if (isRetryable(status)) {
                            LOGGER.warn("[RC] API hat Ergebnis fuer Zahlung {} mit {} beantwortet, neuer Versuch in 5 s", e.id, status);
                        } else {
                            String body = resp.body() == null ? "" : resp.body();
                            PaymentJournal.markReportRejected(e, "HTTP " + status + ": " + body.substring(0, Math.min(200, body.length())));
                            LOGGER.error("[RC] API hat Ergebnis fuer Zahlung {} endgueltig abgelehnt (HTTP {}): {} - manuell pruefen",
                                    e.id, status, body);
                        }
                    }));
        }
    }

    private static void noteLateConfirmation(ChatFormat.Match m) {
        for (Entry e : PaymentJournal.needingAttention()) {
            if (e.state == State.UNCONFIRMED && e.name.equalsIgnoreCase(m.player()) && amountMatches(m.amount(), e.amount)) {
                LOGGER.warn("[RC] Spaete Bestaetigung passt zur unbestaetigten Zahlung {}: \"{}\" - nach Pruefung mit /rc pay resolve {} done abschliessen",
                        e.id, m.line(), e.id);
                return;
            }
        }
        LOGGER.warn("[RC] Zahlungsbestaetigung ohne laufende Zahlung: {}", m.line());
    }

    private static String failureReason(ChatFormat.Match m) {
        RemoteControlConfig cfg = RemoteControlConfig.get();
        return switch (m.key()) {
            case "notonline" -> cfg.payoutServer.isEmpty()
                    ? "Spieler war zum Zeitpunkt der Zahlung nicht online."
                    : "Spieler war nicht online. Muss dafür auf dem Server \"" + cfg.payoutServer + "\" sein.";
            case "nomoney" -> "Der Auszahlungs-Account hat gerade nicht genug Guthaben.";
            case "frommetome" -> "Empfänger ist der Auszahlungs-Account selbst.";
            default -> "Server hat die Zahlung abgelehnt: " + m.line();
        };
    }

    /** Akzeptiert z.B. "100", "1.000", "1,000", "100,00" - bei einer Lesart muss der Betrag exakt passen. */
    static boolean amountMatches(String shown, BigDecimal expected) {
        if (shown == null || !shown.matches("[0-9][0-9.,]*")) return false;
        if (sameAmount(shown.replaceAll("[.,]", ""), expected)) return true;
        int lastSeparator = Math.max(shown.lastIndexOf('.'), shown.lastIndexOf(','));
        if (lastSeparator < 0) return false;
        String asDecimal = shown.substring(0, lastSeparator).replaceAll("[.,]", "") + "." + shown.substring(lastSeparator + 1);
        return sameAmount(asDecimal, expected);
    }

    private static boolean sameAmount(String s, BigDecimal expected) {
        try {
            return new BigDecimal(s).compareTo(expected) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static String plain(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }
}
