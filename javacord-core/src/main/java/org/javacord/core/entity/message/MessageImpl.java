package org.javacord.core.entity.message;

import com.fasterxml.jackson.databind.JsonNode;
import org.javacord.api.DiscordApi;
import org.javacord.api.entity.DiscordEntity;
import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.emoji.CustomEmoji;
import org.javacord.api.entity.emoji.Emoji;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.MessageAttachment;
import org.javacord.api.entity.message.MessageAuthor;
import org.javacord.api.entity.message.MessageType;
import org.javacord.api.entity.message.Reaction;
import org.javacord.api.entity.message.embed.Embed;
import org.javacord.api.entity.permission.Role;
import org.javacord.api.entity.user.User;
import org.javacord.api.listener.ObjectAttachableListener;
import org.javacord.api.listener.message.CachedMessagePinListener;
import org.javacord.api.listener.message.CachedMessageUnpinListener;
import org.javacord.api.listener.message.MessageAttachableListener;
import org.javacord.api.listener.message.MessageAttachableListenerManager;
import org.javacord.api.listener.message.MessageDeleteListener;
import org.javacord.api.listener.message.MessageEditListener;
import org.javacord.api.listener.message.reaction.ReactionAddListener;
import org.javacord.api.listener.message.reaction.ReactionRemoveAllListener;
import org.javacord.api.listener.message.reaction.ReactionRemoveListener;
import org.javacord.api.util.DiscordRegexPattern;
import org.javacord.api.util.event.ListenerManager;
import org.javacord.core.DiscordApiImpl;
import org.javacord.core.entity.emoji.UnicodeEmojiImpl;
import org.javacord.core.entity.message.embed.EmbedImpl;
import org.javacord.core.util.cache.MessageCacheImpl;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;

/**
 * The implementation of {@link Message}.
 */
public class MessageImpl implements Message {

    /**
     * The discord api instance.
     */
    private final DiscordApiImpl api;

    /**
     * The channel of the message.
     */
    private final TextChannel channel;

    /**
     * The id of the message.
     */
    private final long id;

    /**
     * The content of the message.
     */
    private volatile String content;

    /**
     * The type of the message.
     */
    private final MessageType type;

    /**
     * The pinned flag of the message.
     */
    private volatile boolean pinned;

    /**
     * Gets the last edit time.
     */
    private volatile Instant lastEditTime;

    /**
     * The author of the message.
     */
    private final MessageAuthor author;

    /**
     * If the message should be cached forever or not.
     */
    private volatile boolean cacheForever = false;

    /**
     * A list with all embeds.
     */
    private final List<Embed> embeds = new ArrayList<>();

    /**
     * A list with all reactions.
     */
    private final List<Reaction> reactions = new ArrayList<>();

    /**
     * The attachments of the message.
     */
    private final List<MessageAttachment> attachments = new ArrayList<>();

    /**
     * The users mentioned in this message.
     */
    private final List<User> mentions = new ArrayList<>();

    /**
     * The roles mentioned in this message.
     */
    private final List<Role> roleMentions = new ArrayList<>();

    /**
     * Creates a new message object.
     *
     * @param api The discord api instance.
     * @param channel The channel of the message.
     * @param data The json data of the message.
     */
    public MessageImpl(DiscordApiImpl api, TextChannel channel, JsonNode data) {
        this.api = api;
        this.channel = channel;

        id = data.get("id").asLong();
        content = data.get("content").asText();

        pinned = data.get("pinned").asBoolean(false);

        lastEditTime = data.has("edited_timestamp") && !data.get("edited_timestamp").isNull()
                ? OffsetDateTime.parse(data.get("edited_timestamp").asText()).toInstant()
                : null;

        type = MessageType.byType(data.get("type").asInt(), data.has("webhook_id"));

        Long webhookId = data.has("webhook_id") ? data.get("webhook_id").asLong() : null;
        author = new MessageAuthorImpl(this, webhookId, data.get("author"));

        MessageCacheImpl cache = (MessageCacheImpl) channel.getMessageCache();
        cache.addMessage(this);

        if (data.has("embeds")) {
            for (JsonNode embedJson : data.get("embeds")) {
                Embed embed = new EmbedImpl(embedJson);
                embeds.add(embed);
            }
        }

        if (data.has("reactions")) {
            for (JsonNode reactionJson : data.get("reactions")) {
                Reaction reaction = new ReactionImpl(this, reactionJson);
                reactions.add(reaction);
            }
        }

        if (data.has("attachments")) {
            for (JsonNode attachmentJson : data.get("attachments")) {
                MessageAttachment attachment = new MessageAttachmentImpl(this, attachmentJson);
                attachments.add(attachment);
            }
        }

        if (data.has("mentions")) {
            for (JsonNode mentionJson : data.get("mentions")) {
                User user = api.getOrCreateUser(mentionJson);
                mentions.add(user);
            }
        }

        if (data.has("mention_roles") && !data.get("mention_roles").isNull()) {
            getServer().ifPresent(server -> {
                for (JsonNode roleMentionJson : data.get("mention_roles")) {
                    server.getRoleById(roleMentionJson.asText()).ifPresent(roleMentions::add);
                }
            });
        }

    }

    /**
     * Sets the content of the message.
     *
     * @param content The content to set.
     */
    public void setContent(String content) {
        this.content = content;
    }

    /**
     * Sets the pinned flag if the message.
     *
     * @param pinned The pinned flag to set.
     */
    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    /**
     * Sets the last edit time of the message.
     *
     * @param lastEditTime The last edit time of the message.
     */
    public void setLastEditTime(Instant lastEditTime) {
        this.lastEditTime = lastEditTime;
    }

    /**
     * Sets the embeds of the message.
     *
     * @param embeds The embeds to set.
     */
    public void setEmbeds(List<Embed> embeds) {
        this.embeds.clear();
        this.embeds.addAll(embeds);
    }

    /**
     * Adds an emoji to the list of reactions.
     *
     * @param emoji The emoji.
     * @param you Whether this reaction is used by you or not.
     */
    public void addReaction(Emoji emoji, boolean you) {
        Optional<Reaction> reaction = reactions.stream().filter(r -> emoji == r.getEmoji()).findAny();
        reaction.ifPresent(r -> ((ReactionImpl) r).incrementCount(you));
        if (!reaction.isPresent()) {
            reactions.add(new ReactionImpl(this, emoji, 1, you));
        }
    }

    /**
     * Removes an emoji from the list of reactions.
     *
     * @param emoji The emoji.
     * @param you Whether this reaction is used by you or not.
     */
    public void removeReaction(Emoji emoji, boolean you) {
        Optional<Reaction> reaction = reactions.stream().filter(r -> emoji == r.getEmoji()).findAny();
        reaction.ifPresent(r -> ((ReactionImpl) r).decrementCount(you));
        reactions.removeIf(r -> r.getCount() <= 0);
    }

    /**
     * Removes all reaction from this message.
     */
    public void removeAllReactionsFromCache() {
        reactions.clear();
    }

    @Override
    public DiscordApi getApi() {
        return api;
    }

    @Override
    public long getId() {
        return id;
    }

    @Override
    public String getContent() {
        return content;
    }

    @Override
    public Optional<Instant> getLastEditTimestamp() {
        return Optional.ofNullable(lastEditTime);
    }

    @Override
    public List<MessageAttachment> getAttachments() {
        return Collections.unmodifiableList(attachments);
    }

    @Override
    public List<CustomEmoji> getCustomEmojis() {
        String content = getContent();
        List<CustomEmoji> emojis = new ArrayList<>();
        Matcher customEmoji = DiscordRegexPattern.CUSTOM_EMOJI.matcher(content);
        while (customEmoji.find()) {
            long id = Long.parseLong(customEmoji.group("id"));
            String name = customEmoji.group("name");
            boolean animated = customEmoji.group(0).charAt(1) == 'a';
            // TODO Maybe it would be better to cache the custom emoji objects inside the message object
            CustomEmoji emoji = ((DiscordApiImpl) getApi()).getKnownCustomEmojiOrCreateCustomEmoji(id, name, animated);
            emojis.add(emoji);
        }
        return Collections.unmodifiableList(emojis);
    }

    @Override
    public MessageType getType() {
        return type;
    }

    @Override
    public TextChannel getChannel() {
        return channel;
    }

    @Override
    public boolean isPinned() {
        return pinned;
    }

    @Override
    public List<Embed> getEmbeds() {
        return Collections.unmodifiableList(new ArrayList<>(embeds));
    }

    @Override
    public MessageAuthor getAuthor() {
        return author;
    }

    @Override
    public Optional<User> getUserAuthor() {
        return author.asUser();
    }

    @Override
    public boolean isCachedForever() {
        return cacheForever;
    }

    @Override
    public void setCachedForever(boolean cachedForever) {
        this.cacheForever = cachedForever;
        if (cachedForever) {
            // Just make sure it's in the cache
            ((MessageCacheImpl) channel.getMessageCache()).addMessage(this);
        }
    }

    @Override
    public List<Reaction> getReactions() {
        return Collections.unmodifiableList(new ArrayList<>(reactions));
    }

    @Override
    public List<User> getMentionedUsers() {
        return Collections.unmodifiableList(new ArrayList<>(mentions));
    }

    @Override
    public List<Role> getMentionedRoles() {
        return Collections.unmodifiableList(new ArrayList<>(roleMentions));
    }

    @Override
    public CompletableFuture<Void> addReactions(String... unicodeEmojis) {
        return addReactions(Arrays.stream(unicodeEmojis).map(UnicodeEmojiImpl::fromString).toArray(Emoji[]::new));
    }

    @Override
    public CompletableFuture<Void> removeReactionByEmoji(User user, String unicodeEmoji) {
        return removeReactionByEmoji(user, UnicodeEmojiImpl.fromString(unicodeEmoji));
    }

    @Override
    public CompletableFuture<Void> removeReactionByEmoji(String unicodeEmoji) {
        return removeReactionByEmoji(UnicodeEmojiImpl.fromString(unicodeEmoji));
    }

    @Override
    public CompletableFuture<Void> removeReactionsByEmoji(User user, String... unicodeEmojis) {
        return removeReactionsByEmoji(user,
                Arrays.stream(unicodeEmojis).map(UnicodeEmojiImpl::fromString).toArray(Emoji[]::new));
    }

    @Override
    public CompletableFuture<Void> removeReactionsByEmoji(String... unicodeEmojis) {
        return removeReactionsByEmoji(
                Arrays.stream(unicodeEmojis).map(UnicodeEmojiImpl::fromString).toArray(Emoji[]::new));
    }

    @Override
    public CompletableFuture<Void> removeOwnReactionByEmoji(String unicodeEmoji) {
        return removeOwnReactionByEmoji(UnicodeEmojiImpl.fromString(unicodeEmoji));
    }

    @Override
    public CompletableFuture<Void> removeOwnReactionsByEmoji(String... unicodeEmojis) {
        return removeOwnReactionsByEmoji(
                Arrays.stream(unicodeEmojis).map(UnicodeEmojiImpl::fromString).toArray(Emoji[]::new));
    }

    @Override
    public ListenerManager<MessageDeleteListener> addMessageDeleteListener(MessageDeleteListener listener) {
        return MessageAttachableListenerManager.addMessageDeleteListener(getApi(), getId(), listener);
    }

    @Override
    public List<MessageDeleteListener> getMessageDeleteListeners() {
        return MessageAttachableListenerManager.getMessageDeleteListeners(getApi(), getId());
    }

    @Override
    public ListenerManager<MessageEditListener> addMessageEditListener(MessageEditListener listener) {
        return MessageAttachableListenerManager.addMessageEditListener(getApi(), getId(), listener);
    }

    @Override
    public List<MessageEditListener> getMessageEditListeners() {
        return MessageAttachableListenerManager.getMessageEditListeners(getApi(), getId());
    }

    @Override
    public ListenerManager<ReactionAddListener> addReactionAddListener(ReactionAddListener listener) {
        return MessageAttachableListenerManager.addReactionAddListener(getApi(), getId(), listener);
    }

    @Override
    public List<ReactionAddListener> getReactionAddListeners() {
        return MessageAttachableListenerManager.getReactionAddListeners(getApi(), getId());
    }

    @Override
    public ListenerManager<ReactionRemoveListener> addReactionRemoveListener(ReactionRemoveListener listener) {
        return MessageAttachableListenerManager.addReactionRemoveListener(getApi(), getId(), listener);
    }

    @Override
    public List<ReactionRemoveListener> getReactionRemoveListeners() {
        return MessageAttachableListenerManager.getReactionRemoveListeners(getApi(), getId());
    }

    @Override
    public ListenerManager<ReactionRemoveAllListener> addReactionRemoveAllListener(
            ReactionRemoveAllListener listener) {
        return MessageAttachableListenerManager.addReactionRemoveAllListener(getApi(), getId(), listener);
    }

    @Override
    public List<ReactionRemoveAllListener> getReactionRemoveAllListeners() {
        return MessageAttachableListenerManager.getReactionRemoveAllListeners(getApi(), getId());
    }

    @Override
    public <T extends MessageAttachableListener & ObjectAttachableListener> Collection<ListenerManager<T>>
            addMessageAttachableListener(T listener) {
        return MessageAttachableListenerManager.addMessageAttachableListener(getApi(), getId(), listener);
    }

    @Override
    public <T extends MessageAttachableListener & ObjectAttachableListener> void removeListener(
            Class<T> listenerClass, T listener) {
        MessageAttachableListenerManager.removeListener(getApi(), getId(), listenerClass, listener);
    }

    @Override
    public <T extends MessageAttachableListener & ObjectAttachableListener> void removeMessageAttachableListener(
            T listener) {
        MessageAttachableListenerManager.removeMessageAttachableListener(getApi(), getId(), listener);
    }

    @Override
    public <T extends MessageAttachableListener & ObjectAttachableListener> Map<T, List<Class<T>>>
            getMessageAttachableListeners() {
        return MessageAttachableListenerManager.getMessageAttachableListeners(getApi(), getId());
    }

    @Override
    public ListenerManager<CachedMessagePinListener> addCachedMessagePinListener(CachedMessagePinListener listener) {
        return ((DiscordApiImpl) getApi())
                .addObjectListener(Message.class, getId(), CachedMessagePinListener.class, listener);
    }

    @Override
    public List<CachedMessagePinListener> getCachedMessagePinListeners() {
        return ((DiscordApiImpl) getApi()).getObjectListeners(Message.class, getId(), CachedMessagePinListener.class);
    }

    @Override
    public ListenerManager<CachedMessageUnpinListener> addCachedMessageUnpinListener(
            CachedMessageUnpinListener listener) {
        return ((DiscordApiImpl) getApi())
                .addObjectListener(Message.class, getId(), CachedMessageUnpinListener.class, listener);
    }

    @Override
    public List<CachedMessageUnpinListener> getCachedMessageUnpinListeners() {
        return ((DiscordApiImpl) getApi()).getObjectListeners(Message.class, getId(), CachedMessageUnpinListener.class);
    }

    @Override
    public int compareTo(Message otherMessage) {
        return Long.compareUnsigned(getId(), otherMessage.getId());
    }

    @Override
    public boolean equals(Object o) {
        return (this == o)
               || !((o == null)
                    || (getClass() != o.getClass())
                    || (getId() != ((DiscordEntity) o).getId()));
    }

    @Override
    public int hashCode() {
        return Objects.hash(getId());
    }

    @Override
    public String toString() {
        return String.format("Message (id: %s, content: %s)", getIdAsString(), getContent());
    }

}
