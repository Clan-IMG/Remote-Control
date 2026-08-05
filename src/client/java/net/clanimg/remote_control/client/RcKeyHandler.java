package net.clanimg.remote_control.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;

public class RcKeyHandler {

    private static boolean wasPressed = false;

    public static void onTick(MinecraftClient client) {
        var window = client.getWindow();
        boolean r = InputUtil.isKeyPressed(window, InputUtil.GLFW_KEY_R);
        boolean c = InputUtil.isKeyPressed(window, InputUtil.GLFW_KEY_C);
        boolean combo = r && c;
        if (combo && !wasPressed) {
            wasPressed = true;
            client.execute(() -> client.setScreen(new RcSettingsScreen()));
        } else if (!combo) {
            wasPressed = false;
        }
    }
}
