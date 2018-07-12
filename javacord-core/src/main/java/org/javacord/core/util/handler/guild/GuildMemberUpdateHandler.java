package org.javacord.core.util.handler.guild;

import com.fasterxml.jackson.databind.JsonNode;
import org.javacord.api.DiscordApi;
import org.javacord.api.entity.permission.Role;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.server.role.UserRoleAddEvent;
import org.javacord.api.event.server.role.UserRoleRemoveEvent;
import org.javacord.api.event.user.UserChangeNicknameEvent;
import org.javacord.core.entity.permission.RoleImpl;
import org.javacord.core.entity.server.ServerImpl;
import org.javacord.core.event.server.role.UserRoleAddEventImpl;
import org.javacord.core.event.server.role.UserRoleRemoveEventImpl;
import org.javacord.core.event.user.UserChangeNicknameEventImpl;
import org.javacord.core.listener.EventDispatchUtil;
import org.javacord.core.util.gateway.PacketHandler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;

/**
 * Handles the guild member update packet.
 */
public class GuildMemberUpdateHandler extends PacketHandler {

    /**
     * Creates a new instance of this class.
     *
     * @param api The api.
     */
    public GuildMemberUpdateHandler(DiscordApi api) {
        super(api, true, "GUILD_MEMBER_UPDATE");
    }

    @Override
    public void handle(JsonNode packet) {
        api.getAllServerById(packet.get("guild_id").asLong()).map(server -> (ServerImpl) server).ifPresent(server -> {
            User user = api.getOrCreateUser(packet.get("user"));
            if (packet.has("nick")) {
                String newNickname = packet.get("nick").asText(null);
                String oldNickname = server.getNickname(user).orElse(null);
                if (!Objects.deepEquals(newNickname, oldNickname)) {
                    server.setNickname(user, newNickname);

                    UserChangeNicknameEvent event =
                            new UserChangeNicknameEventImpl(user, server, newNickname, oldNickname);

                    EventDispatchUtil.dispatchToUserChangeNicknameListeners(
                            server,
                            server,
                            user,
                            api,
                            listener -> listener.onUserChangeNickname(event));
                }
            }

            if (packet.has("roles")) {
                JsonNode jsonRoles = packet.get("roles");
                Collection<Role> newRoles = new HashSet<>();
                Collection<Role> oldRoles = server.getRoles(user);
                Collection<Role> intersection = new HashSet<>();
                for (JsonNode roleIdJson : jsonRoles) {
                    api.getRoleById(roleIdJson.asText())
                            .map(role -> {
                                newRoles.add(role);
                                return role;
                            })
                            .filter(oldRoles::contains)
                            .ifPresent(intersection::add);
                }

                // Added roles
                Collection<Role> addedRoles = new ArrayList<>(newRoles);
                addedRoles.removeAll(intersection);
                for (Role role : addedRoles) {
                    if (role.isEveryoneRole()) {
                        continue;
                    }
                    ((RoleImpl) role).addUserToCache(user);
                    UserRoleAddEvent event = new UserRoleAddEventImpl(role, user);

                    EventDispatchUtil.dispatchToUserRoleAddListeners(
                            role.getServer(),
                            role,
                            role.getServer(),
                            user,
                            api,
                            listener -> listener.onUserRoleAdd(event));
                }

                // Removed roles
                Collection<Role> removedRoles = new ArrayList<>(oldRoles);
                removedRoles.removeAll(intersection);
                for (Role role : removedRoles) {
                    if (role.isEveryoneRole()) {
                        continue;
                    }
                    ((RoleImpl) role).removeUserFromCache(user);
                    UserRoleRemoveEvent event = new UserRoleRemoveEventImpl(role, user);

                    EventDispatchUtil.dispatchToUserRoleRemoveListeners(
                            role.getServer(),
                            role,
                            role.getServer(),
                            user,
                            api,
                            listener -> listener.onUserRoleRemove(event));
                }
            }
        });
    }

}