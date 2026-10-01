package net.clanimg.remote_control.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.clanimg.remote_control.client.PaymentJournal.Entry;
import net.clanimg.remote_control.client.PaymentJournal.State;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.command.CommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * /rc pay                         – offene/unklare Zahlungen anzeigen
 * /rc pay resolve <id> done|fail  – unbestaetigte Zahlung nach Pruefung abschliessen
 * /rc pay retry <id>              – von der API abgelehnte Meldung erneut senden
 */
public class PayCommand {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM. HH:mm:ss").withZone(ZoneId.systemDefault());

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            ClientCommandManager.literal("rc")
                .then(ClientCommandManager.literal("pay")
                    .executes(ctx -> {
                        showList(ctx.getSource());
                        return 1;
                    })
                    .then(ClientCommandManager.literal("resolve")
                        .then(ClientCommandManager.argument("id", StringArgumentType.word())
                            .suggests((ctx, builder) -> CommandSource.suggestMatching(
                                PaymentJournal.needingAttention().stream().filter(e -> e.state == State.UNCONFIRMED).map(e -> e.id),
                                builder))
                            .then(ClientCommandManager.literal("done")
                                .executes(ctx -> {
                                    resolve(ctx.getSource(), StringArgumentType.getString(ctx, "id"), true);
                                    return 1;
                                })
                            )
                            .then(ClientCommandManager.literal("fail")
                                .executes(ctx -> {
                                    resolve(ctx.getSource(), StringArgumentType.getString(ctx, "id"), false);
                                    return 1;
                                })
                            )
                        )
                    )
                    .then(ClientCommandManager.literal("retry")
                        .then(ClientCommandManager.argument("id", StringArgumentType.word())
                            .suggests((ctx, builder) -> CommandSource.suggestMatching(
                                PaymentJournal.needingAttention().stream().filter(e -> e.reportError != null).map(e -> e.id),
                                builder))
                            .executes(ctx -> {
                                String error = PaymentPoller.retryReport(StringArgumentType.getString(ctx, "id"));
                                ctx.getSource().sendFeedback(error == null
                                    ? Text.literal("[RC] Meldung wird erneut gesendet.").formatted(Formatting.GREEN)
                                    : Text.literal("[RC] " + error).formatted(Formatting.RED));
                                return 1;
                            })
                        )
                    )
                )
        );
    }

    private static void showList(FabricClientCommandSource source) {
        String block = PaymentPoller.blockReason();
        source.sendFeedback(block == null
            ? Text.literal("[RC] Auszahlungen: bereit").formatted(Formatting.GREEN)
            : Text.literal("[RC] Auszahlungen pausiert: " + block).formatted(Formatting.RED));

        List<Entry> open = PaymentJournal.needingAttention();
        if (open.isEmpty()) {
            source.sendFeedback(Text.literal("  Keine offenen oder unklaren Zahlungen.").formatted(Formatting.GRAY));
            return;
        }
        for (Entry e : open) {
            String time = e.sentAt > 0 ? TIME.format(Instant.ofEpochMilli(e.sentAt)) + " " : "";
            source.sendFeedback(Text.literal("  " + time + e.id + "  " + e.name + "  " + PaymentPoller.plain(e.amount) + "$  ")
                .formatted(Formatting.GRAY)
                .append(Text.literal(label(e)).formatted(color(e))));
            if (e.reason != null) source.sendFeedback(Text.literal("    " + e.reason).formatted(Formatting.DARK_GRAY));
            if (e.reportError != null) source.sendFeedback(Text.literal("    API: " + e.reportError).formatted(Formatting.DARK_GRAY));
        }
        if (open.stream().anyMatch(e -> e.state == State.UNCONFIRMED)) {
            source.sendFeedback(Text.literal("  Unbestätigt = unklar, ob das Geld angekommen ist. Erst prüfen (z.B. /money, Server-Log),")
                .formatted(Formatting.YELLOW));
            source.sendFeedback(Text.literal("  dann /rc pay resolve <id> done (angekommen) oder fail (nicht angekommen).")
                .formatted(Formatting.YELLOW));
        }
    }

    private static void resolve(FabricClientCommandSource source, String id, boolean arrived) {
        String error = PaymentPoller.resolveManually(id, arrived);
        source.sendFeedback(error == null
            ? Text.literal("[RC] Zahlung " + id + " als " + (arrived ? "angekommen" : "nicht angekommen") + " abgeschlossen.").formatted(Formatting.GREEN)
            : Text.literal("[RC] " + error).formatted(Formatting.RED));
    }

    private static String label(Entry e) {
        if (e.reportError != null) return "API lehnt Meldung ab (/rc pay retry " + e.id + ")";
        return switch (e.state) {
            case STARTING -> "Freigabe durch RC-API ausstehend, /pay noch nicht gesendet";
            case AWAITING -> "wartet auf Server";
            case UNCONFIRMED -> "UNBESTÄTIGT";
            case DONE -> "erledigt, Meldung an API ausstehend";
            case FAILED -> "fehlgeschlagen, Meldung an API ausstehend";
        };
    }

    private static Formatting color(Entry e) {
        if (e.reportError != null || e.state == State.UNCONFIRMED) return Formatting.RED;
        return Formatting.YELLOW;
    }
}
