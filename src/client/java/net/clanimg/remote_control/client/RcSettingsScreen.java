package net.clanimg.remote_control.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class RcSettingsScreen extends Screen {

    private TextFieldWidget rcApiUrlField;
    private TextFieldWidget rcApiTokenField;
    private TextFieldWidget chatApiUrlField;
    private TextFieldWidget chatApiTokenField;
    private TextFieldWidget payoutServerField;
    private TextFieldWidget spawnCommandField;

    public RcSettingsScreen() {
        super(Text.literal("Remote Control Settings"));
    }

    @Override
    protected void init() {
        RemoteControlConfig cfg = RemoteControlConfig.get();
        int cx = width / 2;
        int y = 10;

        addDrawableChild(new TextWidget(cx - 100, y, 200, 10,
            Text.literal("Remote Control Settings").formatted(Formatting.BOLD, Formatting.AQUA), textRenderer));
        y += 16;

        // ── RC API (Payout) ──
        addDrawableChild(new TextWidget(cx - 100, y, 200, 9,
            Text.literal("RC API URL (Payout)").formatted(Formatting.WHITE), textRenderer));
        y += 10;
        rcApiUrlField = new TextFieldWidget(textRenderer, cx - 100, y, 200, 18, Text.empty());
        rcApiUrlField.setMaxLength(256);
        rcApiUrlField.setText(cfg.rcApiUrl);
        addDrawableChild(rcApiUrlField);
        y += 22;

        addDrawableChild(new TextWidget(cx - 100, y, 200, 9,
            Text.literal("RC API Token").formatted(Formatting.WHITE), textRenderer));
        y += 10;
        rcApiTokenField = new TextFieldWidget(textRenderer, cx - 100, y, 200, 18, Text.empty());
        rcApiTokenField.setMaxLength(256);
        String rcTokenDisplay = cfg.rcApiToken.isEmpty() ? "" : "●●●●●●●●●● (gespeichert)";
        rcApiTokenField.setText(rcTokenDisplay);
        addDrawableChild(rcApiTokenField);
        y += 24;

        // ── Chat API ──
        addDrawableChild(new TextWidget(cx - 100, y, 200, 9,
            Text.literal("Chat API URL (Messages)").formatted(Formatting.WHITE), textRenderer));
        y += 10;
        chatApiUrlField = new TextFieldWidget(textRenderer, cx - 100, y, 200, 18, Text.empty());
        chatApiUrlField.setMaxLength(256);
        chatApiUrlField.setText(cfg.chatApiUrl);
        addDrawableChild(chatApiUrlField);
        y += 22;

        addDrawableChild(new TextWidget(cx - 100, y, 200, 9,
            Text.literal("Chat API Token (= API_TOKEN in .env)").formatted(Formatting.YELLOW), textRenderer));
        y += 10;
        chatApiTokenField = new TextFieldWidget(textRenderer, cx - 100, y, 200, 18, Text.empty());
        chatApiTokenField.setMaxLength(256);
        String chatTokenDisplay = cfg.chatApiToken.isEmpty() ? "" : "●●●●●●●●●● (gespeichert)";
        chatApiTokenField.setText(chatTokenDisplay);
        addDrawableChild(chatApiTokenField);
        y += 24;

        // ── Payout Server ──
        addDrawableChild(new TextWidget(cx - 100, y, 200, 9,
            Text.literal("Payout Server").formatted(Formatting.WHITE), textRenderer));
        y += 10;
        payoutServerField = new TextFieldWidget(textRenderer, cx - 100, y, 200, 18, Text.empty());
        payoutServerField.setMaxLength(64);
        payoutServerField.setText(cfg.payoutServer);
        addDrawableChild(payoutServerField);
        y += 22;

        // ── Teleport Command ──
        addDrawableChild(new TextWidget(cx - 100, y, 200, 9,
            Text.literal("Teleport Command").formatted(Formatting.WHITE), textRenderer));
        y += 10;
        spawnCommandField = new TextFieldWidget(textRenderer, cx - 100, y, 200, 18, Text.empty());
        spawnCommandField.setMaxLength(128);
        spawnCommandField.setText(cfg.spawnCommand);
        addDrawableChild(spawnCommandField);
        y += 24;

        // Save Button
        addDrawableChild(ButtonWidget.builder(Text.literal("Speichern"), btn -> save())
            .dimensions(cx - 50, y, 100, 20)
            .build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
    }

    private void save() {
        RemoteControlConfig cfg = RemoteControlConfig.get();
        
        // RC API URL speichern
        String newRcApiUrl = rcApiUrlField.getText().trim();
        if (!newRcApiUrl.isEmpty()) {
            cfg.rcApiUrl = newRcApiUrl;
        }
        
        // RC API Token speichern (nur wenn nicht das Anzeigeformat)
        String newRcToken = rcApiTokenField.getText().trim();
        if (!newRcToken.isEmpty() && !newRcToken.contains("●") && !newRcToken.contains("gespeichert")) {
            cfg.rcApiToken = newRcToken;
        }
        
        // Chat API URL speichern
        String newChatApiUrl = chatApiUrlField.getText().trim();
        if (!newChatApiUrl.isEmpty()) {
            cfg.chatApiUrl = newChatApiUrl;
        }
        
        // Chat API Token speichern (nur wenn nicht das Anzeigeformat)
        String newChatToken = chatApiTokenField.getText().trim();
        if (!newChatToken.isEmpty() && !newChatToken.contains("●") && !newChatToken.contains("gespeichert")) {
            cfg.chatApiToken = newChatToken;
        }
        
        cfg.payoutServer = payoutServerField.getText().trim();
        cfg.spawnCommand = spawnCommandField.getText().trim();
        cfg.save();

        if (client != null) {
            client.inGameHud.getChatHud().addMessage(
                Text.literal("[RC] Einstellungen gespeichert.").formatted(Formatting.GREEN));
        }

        // Switch immediately if already connected and payout server is configured
        if (client.player != null && !cfg.payoutServer.isEmpty()) {
            AutoReconnectManager.switchToPayoutServer(client);
        }

        close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
