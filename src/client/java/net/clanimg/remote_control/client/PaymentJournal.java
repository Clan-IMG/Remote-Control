package net.clanimg.remote_control.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Write-ahead-Journal aller ausgefuehrten Auszahlungen (config/remote_control_payments.json).
 *
 * Eine Zahlung wird hier gespeichert, BEVOR der /pay-Befehl rausgeht. Dadurch gilt:
 *  - Eine ID, die im Journal steht, wird nie wieder automatisch ausgefuehrt - auch nicht nach
 *    Absturz/Neustart, selbst wenn die API sie weiter als offen liefert.
 *  - Ein Ergebnis (erledigt/fehlgeschlagen) geht nicht verloren, wenn die API gerade nicht
 *    erreichbar ist - es wird so lange gemeldet, bis die API es bestaetigt.
 * Ist die Datei beschaedigt, werden keine Zahlungen ausgefuehrt, bis sie geprueft wurde.
 */
public final class PaymentJournal {

    private static final Logger LOGGER = LoggerFactory.getLogger("remote_control");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("remote_control_payments.json");
    private static final long RETENTION_MS = 90L * 24 * 60 * 60 * 1000; // gemeldete Zahlungen 90 Tage aufheben

    public enum State {
        /** /start bei der RC-API laeuft bzw. muss wiederholt werden - /pay wurde noch NICHT gesendet */
        STARTING,
        /** /pay ist raus, Antwort des Servers steht noch aus */
        AWAITING,
        /** Ergebnis unklar - Zahlung bleibt gesperrt, bis sie manuell geklaert ist */
        UNCONFIRMED,
        DONE,
        FAILED
    }

    public static final class Entry {
        public String id;
        public String name;
        public BigDecimal amount;
        public State state;
        /** Fehlergrund (FAILED) bzw. warum das Ergebnis unklar ist (UNCONFIRMED) */
        public String reason;
        /** API hat /done bzw. /fail mit 2xx angenommen */
        public boolean reported;
        /** API hat die Meldung endgueltig abgelehnt (z.B. 404) - muss manuell geprueft werden */
        public String reportError;
        /** Wird bei /start mitgeschickt - ein wiederholter /start mit demselben Token ist derselbe Versuch */
        public String token;
        public long sentAt;
        public long updatedAt;
    }

    private static final Map<String, Entry> entries = new LinkedHashMap<>();
    private static String loadError = null;

    static {
        load();
    }

    private PaymentJournal() {}

    public static synchronized boolean isUsable() {
        return loadError == null;
    }

    public static synchronized String loadError() {
        return loadError;
    }

    public static synchronized Entry get(String id) {
        return entries.get(id);
    }

    public static synchronized long count(State state) {
        return entries.values().stream().filter(e -> e.state == state).count();
    }

    /** Ergebnisse, die noch an die API gemeldet werden muessen. */
    public static synchronized List<Entry> pendingReports() {
        return entries.values().stream()
                .filter(e -> (e.state == State.DONE || e.state == State.FAILED) && !e.reported && e.reportError == null)
                .toList();
    }

    /** Zahlung, deren /start-Antwort verloren ging (oder die vor dem Neustart gestartet wurde) - wird zuerst fortgesetzt. */
    public static synchronized Entry firstStarting() {
        return entries.values().stream().filter(e -> e.state == State.STARTING).findFirst().orElse(null);
    }

    /** Alles, was nicht sauber abgeschlossen und gemeldet ist. */
    public static synchronized List<Entry> needingAttention() {
        return entries.values().stream()
                .filter(e -> e.state == State.AWAITING || e.state == State.UNCONFIRMED || !e.reported)
                .toList();
    }

    /**
     * Legt einen neuen Eintrag an und schreibt ihn sofort auf die Platte.
     * Gibt null zurueck, wenn das nicht moeglich war - dann darf die Zahlung NICHT ausgefuehrt werden.
     */
    public static synchronized Entry add(String id, String name, BigDecimal amount, State state, String reason) {
        if (loadError != null || entries.containsKey(id)) return null;
        Entry e = new Entry();
        e.id = id;
        e.name = name;
        e.amount = amount;
        e.state = state;
        e.reason = reason;
        e.token = UUID.randomUUID().toString();
        e.updatedAt = System.currentTimeMillis();
        entries.put(id, e);
        if (!save()) {
            entries.remove(id);
            return null;
        }
        return e;
    }

    /**
     * STARTING -> AWAITING, direkt vor dem /pay. false = nicht gespeichert, dann darf /pay NICHT raus
     * (sonst wuerde die Zahlung nach einem Absturz als "noch nicht gesendet" erneut gestartet).
     */
    public static synchronized boolean markAwaiting(Entry e) {
        e.state = State.AWAITING;
        e.sentAt = System.currentTimeMillis();
        e.updatedAt = e.sentAt;
        if (save()) return true;
        e.state = State.STARTING;
        e.sentAt = 0;
        return false;
    }

    /** Nur fuer Zahlungen, deren /pay nie gesendet wurde und die die RC-API nicht (mehr) ausfuehren laesst. */
    public static synchronized void remove(Entry e) {
        entries.remove(e.id);
        save();
    }

    public static synchronized void resolve(Entry e, State state, String reason) {
        e.state = state;
        e.reason = reason;
        e.reported = false;
        e.reportError = null;
        e.updatedAt = System.currentTimeMillis();
        save();
    }

    public static synchronized void markReported(Entry e) {
        e.reported = true;
        e.updatedAt = System.currentTimeMillis();
        save();
    }

    public static synchronized void markReportRejected(Entry e, String error) {
        e.reportError = error;
        e.updatedAt = System.currentTimeMillis();
        save();
    }

    public static synchronized void clearReportError(Entry e) {
        e.reportError = null;
        e.updatedAt = System.currentTimeMillis();
        save();
    }

    private static void load() {
        if (!Files.exists(PATH)) return;
        try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
            List<Entry> list = GSON.fromJson(reader, new TypeToken<List<Entry>>() {}.getType());
            for (Entry e : list == null ? List.<Entry>of() : list) {
                if (e == null || e.id == null || e.state == null) throw new IllegalStateException("Eintrag ohne id/state");
                entries.put(e.id, e);
            }
        } catch (Exception e) {
            entries.clear();
            loadError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            LOGGER.error("[RC] Zahlungsjournal {} ist beschaedigt - Auszahlungen bleiben gesperrt, bis die Datei geprueft wurde",
                    PATH.toAbsolutePath(), e);
            return;
        }

        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Entry e : entries.values()) {
            // Client wurde zwischen /pay und Serverantwort beendet - Ergebnis ist unbekannt.
            // STARTING bleibt: /pay wurde nie gesendet, der Start wird mit demselben Token fortgesetzt.
            if (e.state == State.AWAITING) {
                e.state = State.UNCONFIRMED;
                e.reason = "Client wurde beendet, bevor der Server die Zahlung bestätigt hat.";
                e.updatedAt = now;
                changed = true;
                LOGGER.warn("[RC] Zahlung {} an {} ({}$) ist nach dem Neustart unbestaetigt", e.id, e.name, e.amount);
            }
        }
        changed |= entries.values().removeIf(e ->
                e.reported && (e.state == State.DONE || e.state == State.FAILED) && now - e.updatedAt > RETENTION_MS);
        if (changed) save();
    }

    private static boolean save() {
        if (loadError != null) return false; // beschaedigte Datei nie ueberschreiben
        try {
            Path tmp = PATH.resolveSibling(PATH.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(new ArrayList<>(entries.values()), writer);
            }
            try {
                Files.move(tmp, PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, PATH, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            LOGGER.error("[RC] Zahlungsjournal konnte nicht gespeichert werden", e);
            return false;
        }
    }
}
