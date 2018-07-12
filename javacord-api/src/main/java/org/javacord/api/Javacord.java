package org.javacord.api;

/**
 * This class contains some static information about Javacord.
 */
public class Javacord {

    /**
     * The current javacord version.
     */
    public static final String VERSION = "3.0.0";

    /**
     * The github url of javacord.
     */
    public static final String GITHUB_URL = "https://github.com/Javacord/Javacord";

    /**
     * The user agent used for requests.
     */
    public static final String USER_AGENT = "DiscordBot (" + GITHUB_URL + ", v" + VERSION + ")";

    /**
     * The gateway version from Discord which we are using.
     * A list with all gateway versions can be found
     * <a href="https://discordapp.com/developers/docs/topics/gateway#gateways-gateway-versions">here</a>.
     */
    public static final String DISCORD_GATEWAY_VERSION = "6";

    /**
     * The voice gateway version from Discord which we are using.
     * A list with all voice gateway versions can be found
     * <a
     * href="https://discordapp.com/developers/docs/topics/voice-connections#voice-gateway-versioning-gateway-versions">
     * here</a>.
     */
    public static final String DISCORD_VOICE_GATEWAY_VERSION = "3";

    /**
     * The API version from Discord which we are using.
     * A list with all API versions can be found
     * <a href="https://discordapp.com/developers/docs/reference#api-versioning-api-versions">here</a>.
     */
    public static final String DISCORD_API_VERSION = "6";

    private Javacord() {
        throw new UnsupportedOperationException();
    }
}
