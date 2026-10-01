package net.clanimg.remote_control.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Laedt chat-format.yml (1:1 die Servernachrichten) und erkennt eingehende Chatzeilen.
 *
 * Der Server liefert keinen Statuscode, nur Chatnachrichten - deshalb wird jede Vorlage in ein
 * Muster uebersetzt, das die KOMPLETTE Zeile abdecken muss (^...$). Eine Spieler-DM oder
 * Chatnachricht, die zufaellig "Du hast ... gegeben" enthaelt, matcht damit nie, weil ihr
 * Praefix ("FREUNDE » [...]" bzw. Rang/Name) nicht zur Vorlage passt.
 */
public final class ChatFormat {

    private static final Logger LOGGER = LoggerFactory.getLogger("remote_control");

    public static final String PAY = "pay_message";
    public static final String DM = "dm_message";

    public record Entry(String category, String key, int code, String template, Pattern pattern) {}

    public record Match(Entry entry, String line, String player, String amount, String message) {
        public String key() { return entry.key(); }
        public int code() { return entry.code(); }
    }

    // &a / §a Farbcodes, &#RRGGBB Hexfarben
    private static final Pattern TEMPLATE_CODES = Pattern.compile("(?i)&#[0-9a-f]{6}|[&§][0-9a-fk-orx]");
    private static final Pattern RECEIVED_CODES = Pattern.compile("(?i)§[0-9a-fk-orx]");
    private static final Pattern PLACEHOLDER = Pattern.compile("%([a-z_]+)%");

    private static final Map<String, List<Entry>> CATEGORIES = new LinkedHashMap<>();
    private static String loadError = null;

    static {
        try (InputStream in = ChatFormat.class.getResourceAsStream("/chat-format.yml")) {
            if (in == null) throw new IllegalStateException("chat-format.yml fehlt im Mod-JAR");
            Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions()))
                    .load(new InputStreamReader(in, StandardCharsets.UTF_8));
            if (root == null) throw new IllegalStateException("chat-format.yml ist leer");
            for (Map.Entry<String, Object> category : root.entrySet()) {
                List<Entry> entries = new ArrayList<>();
                for (Map.Entry<?, ?> e : asMap(category.getValue(), category.getKey()).entrySet()) {
                    String key = String.valueOf(e.getKey());
                    Map<?, ?> def = asMap(e.getValue(), category.getKey() + "." + key);
                    if (!(def.get("code") instanceof Integer code) || !(def.get("message") instanceof String template)) {
                        throw new IllegalStateException(category.getKey() + "." + key + " braucht 'code' (Zahl) und 'message' (Text)");
                    }
                    entries.add(new Entry(category.getKey(), key, code, template, compile(template)));
                }
                CATEGORIES.put(category.getKey(), List.copyOf(entries));
            }
            if (!CATEGORIES.containsKey(PAY) || !CATEGORIES.get(PAY).stream().anyMatch(e -> e.key().equals("fromme"))) {
                throw new IllegalStateException("pay_message.fromme fehlt - ohne sie kann keine Zahlung bestaetigt werden");
            }
            LOGGER.info("[RC] chat-format.yml geladen: {}", CATEGORIES.keySet());
        } catch (Exception e) {
            CATEGORIES.clear();
            loadError = e.getMessage();
            LOGGER.error("[RC] chat-format.yml konnte nicht geladen werden - Auszahlungen bleiben gesperrt", e);
        }
    }

    private ChatFormat() {}

    /** Ohne geladene Formate kann keine Zahlung bestaetigt werden - dann darf auch keine ausgefuehrt werden. */
    public static boolean isLoaded() {
        return loadError == null;
    }

    public static String loadError() {
        return loadError;
    }

    /** Erste Vorlage der Kategorie, die die komplette Zeile abdeckt, sonst null. */
    public static Match match(String category, String line) {
        String normalized = normalize(RECEIVED_CODES.matcher(line).replaceAll(""));
        for (Entry entry : CATEGORIES.getOrDefault(category, List.of())) {
            Matcher m = entry.pattern().matcher(normalized);
            if (m.matches()) {
                return new Match(entry, normalized, group(m, "player"), group(m, "amount"), group(m, "message"));
            }
        }
        return null;
    }

    /** Rohe Vorlage (mit &-Farbcodes), z.B. fuer die Anzeige in /rc log. */
    public static String template(String category, String key, String fallback) {
        for (Entry entry : CATEGORIES.getOrDefault(category, List.of())) {
            if (entry.key().equals(key)) return entry.template();
        }
        return fallback;
    }

    private static Pattern compile(String template) {
        String plain = normalize(TEMPLATE_CODES.matcher(template).replaceAll(""));
        StringBuilder regex = new StringBuilder("^");
        Set<String> seen = new HashSet<>();
        Matcher m = PLACEHOLDER.matcher(plain);
        int last = 0;
        while (m.find()) {
            regex.append(Pattern.quote(plain.substring(last, m.start())));
            String name = m.group(1);
            switch (name) {
                // Minecraft-Namen und Betraege enthalten nie Leerzeichen; der Betrag wird
                // bewusst locker erfasst und erst danach exakt gegen den Auftrag geprueft
                case "player", "amount" -> regex.append(seen.add(name) ? "(?<" + name + ">\\S+)" : "\\k<" + name + ">");
                case "message" -> regex.append(seen.add(name) ? "(?<message>.*)" : "\\k<message>");
                default -> regex.append(".*?");
            }
            last = m.end();
        }
        regex.append(Pattern.quote(plain.substring(last))).append('$');
        return Pattern.compile(regex.toString());
    }

    private static String normalize(String s) {
        return s.replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String group(Matcher m, String name) {
        try {
            return m.group(name);
        } catch (IllegalArgumentException e) {
            return null; // Vorlage hat diesen Platzhalter nicht
        }
    }

    private static Map<?, ?> asMap(Object value, String path) {
        if (value instanceof Map<?, ?> map) return map;
        throw new IllegalStateException(path + " ist kein Abschnitt");
    }
}
