package org.javacord.core.util.handler.channel;

import com.fasterxml.jackson.databind.JsonNode;
import org.javacord.api.DiscordApi;
import org.javacord.api.entity.DiscordEntity;
import org.javacord.api.entity.channel.Categorizable;
import org.javacord.api.entity.channel.ChannelCategory;
import org.javacord.api.entity.channel.ServerChannel;
import org.javacord.api.entity.permission.Permissions;
import org.javacord.api.entity.permission.Role;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.channel.group.GroupChannelChangeNameEvent;
import org.javacord.api.event.channel.server.ServerChannelChangeNameEvent;
import org.javacord.api.event.channel.server.ServerChannelChangeNsfwFlagEvent;
import org.javacord.api.event.channel.server.ServerChannelChangeOverwrittenPermissionsEvent;
import org.javacord.api.event.channel.server.ServerChannelChangePositionEvent;
import org.javacord.api.event.channel.server.text.ServerTextChannelChangeTopicEvent;
import org.javacord.api.event.channel.server.voice.ServerVoiceChannelChangeBitrateEvent;
import org.javacord.api.event.channel.server.voice.ServerVoiceChannelChangeUserLimitEvent;
import org.javacord.core.entity.channel.ChannelCategoryImpl;
import org.javacord.core.entity.channel.GroupChannelImpl;
import org.javacord.core.entity.channel.ServerChannelImpl;
import org.javacord.core.entity.channel.ServerTextChannelImpl;
import org.javacord.core.entity.channel.ServerVoiceChannelImpl;
import org.javacord.core.entity.permission.PermissionsImpl;
import org.javacord.core.event.channel.group.GroupChannelChangeNameEventImpl;
import org.javacord.core.event.channel.server.ServerChannelChangeNameEventImpl;
import org.javacord.core.event.channel.server.ServerChannelChangeNsfwFlagEventImpl;
import org.javacord.core.event.channel.server.ServerChannelChangeOverwrittenPermissionsEventImpl;
import org.javacord.core.event.channel.server.ServerChannelChangePositionEventImpl;
import org.javacord.core.event.channel.server.text.ServerTextChannelChangeTopicEventImpl;
import org.javacord.core.event.channel.server.voice.ServerVoiceChannelChangeBitrateEventImpl;
import org.javacord.core.event.channel.server.voice.ServerVoiceChannelChangeUserLimitEventImpl;
import org.javacord.core.listener.EventDispatchUtil;
import org.javacord.core.util.gateway.PacketHandler;
import org.javacord.core.util.logging.LoggerUtil;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles the channel update packet.
 */
public class ChannelUpdateHandler extends PacketHandler {

    /**
     * Creates a new instance of this class.
     *
     * @param api The api.
     */
    public ChannelUpdateHandler(DiscordApi api) {
        super(api, true, "CHANNEL_UPDATE");
    }

    @Override
    public void handle(JsonNode packet) {
        int type = packet.get("type").asInt();
        switch (type) {
            case 0:
                handleServerChannel(packet);
                handleServerTextChannel(packet);
                break;
            case 1:
                handlePrivateChannel(packet);
                break;
            case 2:
                handleServerChannel(packet);
                handleServerVoiceChannel(packet);
                break;
            case 3:
                handleGroupChannel(packet);
                break;
            case 4:
                handleServerChannel(packet);
                handleChannelCategory(packet);
                break;
            default:
                LoggerUtil.getLogger(ChannelUpdateHandler.class).warn("Unexpected packet type. Your Javacord version"
                        + " might be out of date.");
        }
    }

    /**
     * Handles a server channel update.
     *
     * @param jsonChannel The channel data.
     */
    private void handleServerChannel(JsonNode jsonChannel) {
        long channelId = jsonChannel.get("id").asLong();
        api.getServerChannelById(channelId).map(ServerChannelImpl.class::cast).ifPresent(channel -> {
            String oldName = channel.getName();
            String newName = jsonChannel.get("name").asText();
            if (!Objects.deepEquals(oldName, newName)) {
                channel.setName(newName);
                ServerChannelChangeNameEvent event =
                        new ServerChannelChangeNameEventImpl(channel, newName, oldName);

                EventDispatchUtil.dispatchToServerChannelChangeNameListeners(
                        channel.getServer(),
                        channel.getServer(),
                        channel,
                        api,
                        listener -> listener.onServerChannelChangeName(event));
            }

            ChannelCategory oldCategory = channel.asCategorizable().flatMap(Categorizable::getCategory).orElse(null);
            ChannelCategory newCategory = jsonChannel.hasNonNull("parent_id")
                    ? channel.getServer().getChannelCategoryById(jsonChannel.get("parent_id").asLong(-1)).orElse(null)
                    : null;
            int oldRawPosition = channel.getRawPosition();
            int newRawPosition = jsonChannel.get("position").asInt();
            if (oldRawPosition != newRawPosition || !Objects.deepEquals(oldCategory, newCategory)) {
                int oldPosition = channel.getPosition();
                if (channel instanceof ServerTextChannelImpl) {
                    ((ServerTextChannelImpl) channel).setParentId(newCategory == null ? -1 : newCategory.getId());
                } else if (channel instanceof ServerVoiceChannelImpl) {
                    ((ServerVoiceChannelImpl) channel).setParentId(newCategory == null ? -1 : newCategory.getId());
                }
                channel.setPosition(newRawPosition);

                int newPosition = channel.getPosition();

                ServerChannelChangePositionEvent event = new ServerChannelChangePositionEventImpl(
                        channel, newPosition, oldPosition, newRawPosition, oldRawPosition, newCategory, oldCategory);

                EventDispatchUtil.dispatchToServerChannelChangePositionListeners(
                        channel.getServer(),
                        channel.getServer(),
                        channel,
                        api,
                        listener -> listener.onServerChannelChangePosition(event));
            }

            Collection<Long> rolesWithOverwrittenPermissions = new HashSet<>();
            Collection<Long> usersWithOverwrittenPermissions = new HashSet<>();
            if (jsonChannel.has("permission_overwrites") && !jsonChannel.get("permission_overwrites").isNull()) {
                for (JsonNode permissionOverwriteJson : jsonChannel.get("permission_overwrites")) {
                    Permissions oldOverwrittenPermissions;
                    DiscordEntity entity;
                    ConcurrentHashMap<Long, Permissions> overwrittenPermissions = null;
                    switch (permissionOverwriteJson.get("type").asText()) {
                        case "role":
                            entity = api.getRoleById(permissionOverwriteJson.get("id").asText()).orElseThrow(() ->
                                    new IllegalStateException("Received channel update event with unknown role!"));
                            oldOverwrittenPermissions = channel.getOverwrittenPermissions((Role) entity);
                            overwrittenPermissions = channel.getInternalOverwrittenRolePermissions();
                            rolesWithOverwrittenPermissions.add(entity.getId());
                            break;
                        case "member":
                            entity = api.getCachedUserById(permissionOverwriteJson.get("id").asText()).orElseThrow(()
                                    -> new IllegalStateException("Received channel update event with unknown user!"));
                            oldOverwrittenPermissions = channel.getOverwrittenPermissions((User) entity);
                            overwrittenPermissions = channel.getInternalOverwrittenUserPermissions();
                            usersWithOverwrittenPermissions.add(entity.getId());
                            break;
                        default:
                            throw new IllegalStateException("Permission overwrite object with unknown type: "
                                    + permissionOverwriteJson.toString());
                    }
                    int allow = permissionOverwriteJson.get("allow").asInt(0);
                    int deny = permissionOverwriteJson.get("deny").asInt(0);
                    Permissions newOverwrittenPermissions = new PermissionsImpl(allow, deny);
                    if (!newOverwrittenPermissions.equals(oldOverwrittenPermissions)) {
                        overwrittenPermissions.put(entity.getId(), newOverwrittenPermissions);
                        dispatchServerChannelChangeOverwrittenPermissionsEvent(
                                channel, newOverwrittenPermissions, oldOverwrittenPermissions, entity);
                    }
                }
            }
            ConcurrentHashMap<Long, Permissions> overwrittenRolePermissions = null;
            ConcurrentHashMap<Long, Permissions> overwrittenUserPermissions = null;
            overwrittenRolePermissions = channel.getInternalOverwrittenRolePermissions();
            overwrittenUserPermissions = channel.getInternalOverwrittenUserPermissions();

            Iterator<Map.Entry<Long, Permissions>> userIt = overwrittenUserPermissions.entrySet().iterator();
            while (userIt.hasNext()) {
                Map.Entry<Long, Permissions> entry = userIt.next();
                if (usersWithOverwrittenPermissions.contains(entry.getKey())) {
                    continue;
                }
                api.getCachedUserById(entry.getKey()).ifPresent(user -> {
                    Permissions oldPermissions = entry.getValue();
                    userIt.remove();
                    dispatchServerChannelChangeOverwrittenPermissionsEvent(
                            channel, PermissionsImpl.EMPTY_PERMISSIONS, oldPermissions, user);
                });
            }

            Iterator<Map.Entry<Long, Permissions>> roleIt = overwrittenRolePermissions.entrySet().iterator();
            while (roleIt.hasNext()) {
                Map.Entry<Long, Permissions> entry = roleIt.next();
                if (rolesWithOverwrittenPermissions.contains(entry.getKey())) {
                    continue;
                }
                api.getRoleById(entry.getKey()).ifPresent(role -> {
                    Permissions oldPermissions = entry.getValue();
                    roleIt.remove();
                    dispatchServerChannelChangeOverwrittenPermissionsEvent(
                            channel, PermissionsImpl.EMPTY_PERMISSIONS, oldPermissions, role);
                });
            }
        });
    }

    /**
     * Handles a channel category update.
     *
     * @param jsonChannel The channel data.
     */
    private void handleChannelCategory(JsonNode jsonChannel) {
        long channelCategoryId = jsonChannel.get("id").asLong();
        api.getChannelCategoryById(channelCategoryId).map(ChannelCategoryImpl.class::cast).ifPresent(channel -> {
            boolean oldNsfwFlag = channel.isNsfw();
            boolean newNsfwFlag = jsonChannel.get("nsfw").asBoolean();
            if (oldNsfwFlag != newNsfwFlag) {
                channel.setNsfwFlag(newNsfwFlag);
                ServerChannelChangeNsfwFlagEvent event =
                        new ServerChannelChangeNsfwFlagEventImpl(channel, newNsfwFlag, oldNsfwFlag);

                EventDispatchUtil.dispatchToServerChannelChangeNsfwFlagListeners(
                        channel.getServer(),
                        channel,
                        channel.getServer(),
                        null,
                        api,
                        listener -> listener.onServerChannelChangeNsfwFlag(event));
            }
        });
    }

    /**
     * Handles a server text channel update.
     *
     * @param jsonChannel The json channel data.
     */
    private void handleServerTextChannel(JsonNode jsonChannel) {
        long channelId = jsonChannel.get("id").asLong();
        api.getTextChannelById(channelId).map(c -> ((ServerTextChannelImpl) c)).ifPresent(channel -> {
            String oldTopic = channel.getTopic();
            String newTopic = jsonChannel.has("topic") && !jsonChannel.get("topic").isNull()
                    ? jsonChannel.get("topic").asText() : "";
            if (!oldTopic.equals(newTopic)) {
                channel.setTopic(newTopic);

                ServerTextChannelChangeTopicEvent event =
                        new ServerTextChannelChangeTopicEventImpl(channel, newTopic, oldTopic);

                EventDispatchUtil.dispatchToServerTextChannelChangeTopicListeners(
                        channel.getServer(),
                        channel.getServer(),
                        channel,
                        api,
                        listener -> listener.onServerTextChannelChangeTopic(event));
            }

            boolean oldNsfwFlag = channel.isNsfw();
            boolean newNsfwFlag = jsonChannel.get("nsfw").asBoolean();
            if (oldNsfwFlag != newNsfwFlag) {
                channel.setNsfwFlag(newNsfwFlag);
                ServerChannelChangeNsfwFlagEvent event =
                        new ServerChannelChangeNsfwFlagEventImpl(channel, newNsfwFlag, oldNsfwFlag);

                EventDispatchUtil.dispatchToServerChannelChangeNsfwFlagListeners(
                        channel.getServer(),
                        null,
                        channel.getServer(),
                        channel,
                        api,
                        listener -> listener.onServerChannelChangeNsfwFlag(event));
            }
        });
    }

    /**
     * Handles a server voice channel update.
     *
     * @param jsonChannel The channel data.
     */
    private void handleServerVoiceChannel(JsonNode jsonChannel) {
        long channelId = jsonChannel.get("id").asLong();
        api.getServerVoiceChannelById(channelId).map(ServerVoiceChannelImpl.class::cast).ifPresent(channel -> {
            int oldBitrate = channel.getBitrate();
            int newBitrate = jsonChannel.get("bitrate").asInt();
            if (oldBitrate != newBitrate) {
                channel.setBitrate(newBitrate);
                ServerVoiceChannelChangeBitrateEvent event =
                        new ServerVoiceChannelChangeBitrateEventImpl(channel, newBitrate, oldBitrate);

                EventDispatchUtil.dispatchToServerVoiceChannelChangeBitrateListeners(
                        channel.getServer(),
                        channel.getServer(),
                        channel,
                        api,
                        listener -> listener.onServerVoiceChannelChangeBitrate(event));
            }

            int oldUserLimit = channel.getUserLimit().orElse(0);
            int newUserLimit = jsonChannel.get("user_limit").asInt();
            if (oldUserLimit != newUserLimit) {
                channel.setUserLimit(newUserLimit);
                ServerVoiceChannelChangeUserLimitEvent event =
                        new ServerVoiceChannelChangeUserLimitEventImpl(channel, newUserLimit, oldUserLimit);

                EventDispatchUtil.dispatchToServerVoiceChannelChangeUserLimitListeners(
                        channel.getServer(),
                        channel.getServer(),
                        channel,
                        api,
                        listener -> listener.onServerVoiceChannelChangeUserLimit(event));
            }
        });
    }

    /**
     * Handles a private channel update.
     *
     * @param channel The channel data.
     */
    private void handlePrivateChannel(JsonNode channel) {
    }

    /**
     * Handles a group channel update.
     *
     * @param jsonChannel The channel data.
     */
    private void handleGroupChannel(JsonNode jsonChannel) {
        long channelId = jsonChannel.get("id").asLong();
        api.getGroupChannelById(channelId).map(GroupChannelImpl.class::cast).ifPresent(channel -> {
            String oldName = channel.getName().orElseThrow(AssertionError::new);
            String newName = jsonChannel.get("name").asText();
            if (!Objects.equals(oldName, newName)) {
                channel.setName(newName);

                GroupChannelChangeNameEvent event =
                        new GroupChannelChangeNameEventImpl(channel, newName, oldName);

                EventDispatchUtil.dispatchToGroupChannelChangeNameListeners(
                        api,
                        Collections.singleton(channel),
                        channel.getMembers(),
                        api,
                        listener -> listener.onGroupChannelChangeName(event));
            }
        });
    }

    /**
     * Dispatches a ServerChannelChangeOverwrittenPermissionsEvent.
     *
     * @param channel The channel of the event.
     * @param newPermissions The new overwritten permissions.
     * @param oldPermissions The old overwritten permissions.
     * @param entity The entity of the event.
     */
    private void dispatchServerChannelChangeOverwrittenPermissionsEvent(
            ServerChannel channel, Permissions newPermissions, Permissions oldPermissions, DiscordEntity entity) {
        if (newPermissions.equals(oldPermissions)) {
            // This can be caused by adding a user/role in a channels overwritten permissions without modifying
            // any of its values. We don't need to dispatch an event for this.
            return;
        }
        ServerChannelChangeOverwrittenPermissionsEvent event =
                new ServerChannelChangeOverwrittenPermissionsEventImpl(
                        channel, newPermissions, oldPermissions, entity);

        EventDispatchUtil.dispatchToServerChannelChangeOverwrittenPermissionsListeners(
                channel.getServer(),
                (entity instanceof Role) ? (Role) entity : null,
                channel.getServer(),
                channel,
                (entity instanceof User) ? (User) entity : null,
                api,
                listener -> listener.onServerChannelChangeOverwrittenPermissions(event));
    }

}
