package net.clanimg.remote_control.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class Remote_controlClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(PaymentPoller::onTick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            RcPingCommand.register(dispatcher)
        );
    }
}
