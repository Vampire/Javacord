package org.javacord.core.util.handler.guild;

import com.fasterxml.jackson.databind.JsonNode;
import org.javacord.api.DiscordApi;
import org.javacord.api.entity.channel.GroupChannel;
import org.javacord.api.entity.channel.PrivateChannel;
import org.javacord.api.entity.channel.ServerVoiceChannel;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.channel.server.voice.ServerVoiceChannelMemberJoinEvent;
import org.javacord.api.event.channel.server.voice.ServerVoiceChannelMemberLeaveEvent;
import org.javacord.api.listener.channel.server.voice.ServerVoiceChannelMemberJoinListener;
import org.javacord.api.listener.channel.server.voice.ServerVoiceChannelMemberLeaveListener;
import org.javacord.core.entity.channel.GroupChannelImpl;
import org.javacord.core.entity.channel.PrivateChannelImpl;
import org.javacord.core.entity.channel.ServerVoiceChannelImpl;
import org.javacord.core.event.channel.server.voice.ServerVoiceChannelMemberJoinEventImpl;
import org.javacord.core.event.channel.server.voice.ServerVoiceChannelMemberLeaveEventImpl;
import org.javacord.core.util.gateway.PacketHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Handles the voice state update packet.
 */
public class VoiceStateUpdateHandler extends PacketHandler {

    /**
     * Creates a new instance of this class.
     *
     * @param api The api.
     */
    public VoiceStateUpdateHandler(DiscordApi api) {
        super(api, true, "VOICE_STATE_UPDATE");
    }

    @Override
    public void handle(JsonNode packet) {
        if (!packet.hasNonNull("user_id")) {
            return;
        }

        long userId = packet.get("user_id").asLong();
        if (packet.hasNonNull("guild_id")) {
            handleServerVoiceChannel(packet, userId);
        } else if (packet.hasNonNull("channel_id")) {
            long channelId = packet.get("channel_id").asLong();
            api.getVoiceChannelById(channelId).ifPresent(voiceChannel -> {
                if (voiceChannel instanceof PrivateChannel) {
                    handlePrivateChannel(userId, ((PrivateChannelImpl) voiceChannel));
                } else if (voiceChannel instanceof GroupChannel) {
                    handleGroupChannel(userId, ((GroupChannelImpl) voiceChannel));
                }
            });
        }
    }

    private void handleServerVoiceChannel(JsonNode packet, long userId) {
        Optional<Server> optionalServer = api.getPossiblyUnreadyServerById(packet.get("guild_id").asLong());
        Optional<ServerVoiceChannelImpl> oldChannel = optionalServer
                .flatMap(server -> server.getConnectedVoiceChannel(userId))
                .map(ServerVoiceChannelImpl.class::cast);

        optionalServer.ifPresent(server -> {
            Optional<ServerVoiceChannelImpl> newChannel;
            if (packet.hasNonNull("channel_id")) {
                newChannel = server
                        .getVoiceChannelById(packet.get("channel_id").asLong())
                        .map(ServerVoiceChannelImpl.class::cast);

                if (newChannel.equals(oldChannel)) {
                    return;
                }
            } else {
                newChannel = Optional.empty();
            }

            Optional<User> optionalUser = api.getCachedUserById(userId);

            oldChannel.ifPresent(channel -> {
                channel.removeConnectedUser(userId);
                dispatchServerVoiceChannelMemberLeaveEvent(userId, newChannel.orElse(null), channel, server);
            });

            newChannel.ifPresent(channel -> {
                channel.addConnectedUser(userId);
                dispatchServerVoiceChannelMemberJoinEvent(userId, channel, oldChannel.orElse(null), server);
            });
        });
    }

    private void handlePrivateChannel(long userId, PrivateChannelImpl channel) {
        //channel.addConnectedUser(user);
        //dispatchVoiceChannelMemberJoinEvent(user, channel);
    }

    private void handleGroupChannel(long userId, GroupChannelImpl channel) {
        //channel.addConnectedUser(user);
        //dispatchVoiceChannelMemberJoinEvent(user, channel);
    }

    private void dispatchServerVoiceChannelMemberJoinEvent(
            Long userId, ServerVoiceChannel newChannel, ServerVoiceChannel oldChannel, Server server) {
        ServerVoiceChannelMemberJoinEvent event = new ServerVoiceChannelMemberJoinEventImpl(
                userId, newChannel, oldChannel);

        List<ServerVoiceChannelMemberJoinListener> listeners = new ArrayList<>();
        listeners.addAll(api.getObjectListeners(User.class, userId, ServerVoiceChannelMemberJoinListener.class));
        listeners.addAll(newChannel.getServerVoiceChannelMemberJoinListeners());
        if (server != null) {
            listeners.addAll(server.getServerVoiceChannelMemberJoinListeners());
        }
        listeners.addAll(api.getServerVoiceChannelMemberJoinListeners());

        api.getEventDispatcher().dispatchEvent(server,
                listeners, listener -> listener.onServerVoiceChannelMemberJoin(event));
    }

    private void dispatchServerVoiceChannelMemberLeaveEvent(
            Long userId, ServerVoiceChannel newChannel, ServerVoiceChannel oldChannel, Server server) {
        ServerVoiceChannelMemberLeaveEvent event = new ServerVoiceChannelMemberLeaveEventImpl(
                userId, newChannel, oldChannel);

        List<ServerVoiceChannelMemberLeaveListener> listeners = new ArrayList<>();
        listeners.addAll(api.getObjectListeners(User.class, userId, ServerVoiceChannelMemberLeaveListener.class));
        listeners.addAll(oldChannel.getServerVoiceChannelMemberLeaveListeners());
        if (server != null) {
            listeners.addAll(server.getServerVoiceChannelMemberLeaveListeners());
        }
        listeners.addAll(api.getServerVoiceChannelMemberLeaveListeners());

        api.getEventDispatcher().dispatchEvent(server,
                listeners, listener -> listener.onServerVoiceChannelMemberLeave(event));
    }

}
