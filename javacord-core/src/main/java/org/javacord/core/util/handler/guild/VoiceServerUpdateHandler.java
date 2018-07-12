package org.javacord.core.util.handler.guild;

import com.fasterxml.jackson.databind.JsonNode;
import org.javacord.api.DiscordApi;
import org.javacord.api.entity.server.Server;
import org.javacord.api.event.server.voice.VoiceServerUpdateEvent;
import org.javacord.api.listener.server.voice.VoiceServerUpdateListener;
import org.javacord.core.event.server.voice.VoiceServerUpdateEventImpl;
import org.javacord.core.util.gateway.PacketHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles the voice server update packet.
 */
public class VoiceServerUpdateHandler extends PacketHandler {

    /**
     * Creates a new instance of this class.
     *
     * @param api The api.
     */
    public VoiceServerUpdateHandler(DiscordApi api) {
        super(api, true, "VOICE_SERVER_UPDATE");
    }

    @Override
    public void handle(JsonNode packet) {
        api.getServerById(packet.get("guild_id").asLong())
                .ifPresent(server -> {
                    VoiceServerUpdateEvent event = new VoiceServerUpdateEventImpl(server,
                                                                                  packet.get("token").asText(),
                                                                                  packet.get("endpoint").asText());

                    List<VoiceServerUpdateListener> listeners = new ArrayList<>();
                    listeners.addAll(api.getObjectListeners(Server.class, server.getId(),
                                                            VoiceServerUpdateListener.class));
                    listeners.addAll(api.getListeners(VoiceServerUpdateListener.class));

                    api.getEventDispatcher()
                            .dispatchEvent(api, listeners, listener -> listener.onVoiceServerUpdate(event));
                });
    }

}
