package net.clanimg.remote_control.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class Remote_controlClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(PaymentPoller::onTick);
        ClientTickEvents.END_CLIENT_TICK.register(AutoReconnectManager::onTick);
        ClientTickEvents.END_CLIENT_TICK.register(RcKeyHandler::onTick);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
            AutoReconnectManager.onJoin(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
            AutoReconnectManager.onDisconnect());

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                PaymentPoller.onGameMessage(message);
                AutoReconnectManager.onGameMessage(message);
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            RcPingCommand.register(dispatcher)
        );
    }
}


