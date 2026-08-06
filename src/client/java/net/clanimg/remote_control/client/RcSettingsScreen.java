package net.clanimg.remote_control.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class RcSettingsScreen extends Screen {

    private TextFieldWidget payoutServerField;
    private TextFieldWidget spawnCommandField;

    public RcSettingsScreen() {
        super(Text.literal("Remote Control Settings"));
    }

    @Override
    protected void init() {
        RemoteControlConfig cfg = RemoteControlConfig.get();
        int cx = width / 2;
        int y = height / 4;

        addDrawableChild(new TextWidget(cx - 100, y - 24, 200, 10,
            Text.literal("Remote Control").formatted(Formatting.BOLD, Formatting.WHITE), textRenderer));
        addDrawableChild(new TextWidget(cx - 100, y - 12, 200, 10,
            Text.literal("Settings").formatted(Formatting.GRAY), textRenderer));

        addDrawableChild(new TextWidget(cx - 100, y + 12, 200, 9,
            Text.literal("Payout Server").formatted(Formatting.WHITE), textRenderer));
        payoutServerField = new TextFieldWidget(textRenderer, cx - 100, y + 24, 200, 20, Text.empty());
        payoutServerField.setMaxLength(64);
        payoutServerField.setText(cfg.payoutServer);
        addDrawableChild(payoutServerField);

        addDrawableChild(new TextWidget(cx - 100, y + 52, 200, 9,
            Text.literal("Teleport Command").formatted(Formatting.WHITE), textRenderer));
        spawnCommandField = new TextFieldWidget(textRenderer, cx - 100, y + 64, 200, 20, Text.empty());
        spawnCommandField.setMaxLength(128);
        spawnCommandField.setText(cfg.spawnCommand);
        addDrawableChild(spawnCommandField);

        addDrawableChild(ButtonWidget.builder(Text.literal("Speichern"), btn -> save())
            .dimensions(cx - 50, y + 100, 100, 20)
            .build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
    }

    private void save() {
        RemoteControlConfig cfg = RemoteControlConfig.get();
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
