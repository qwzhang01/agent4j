package io.github.qwzhang01.agent.channel;

import java.time.Instant;
import java.util.Objects;

/**
 * A message spoken in a channel, attributed to a speaker (Stage 12 M12.2).
 * <p>
 * Unlike a plain user input, a channel message carries WHO said it and
 * WHETHER the channel agent was addressed. Both matter for routing:
 * <ul>
 *   <li>mention -> the agent runs and replies</li>
 *   <li>no mention -> history only, the agent is not invoked (the channel
 *       is not the agent's whole world; humans talk to humans too)</li>
 * </ul>
 * Mention detection convention: the text starts with {@code @<agentId>}
 * (optionally followed by a space, colon, or fullwidth comma).
 *
 * @param channelId     where the message was spoken
 * @param userId        who spoke
 * @param text          raw text (mention prefix kept; strip via {@link #textWithoutMention(String)})
 * @param mentionsAgent whether the channel agent was addressed
 * @param timestamp     when the message was spoken
 */
public record ChannelMessage(
        String channelId,
        String userId,
        String text,
        boolean mentionsAgent,
        Instant timestamp
) {

    public ChannelMessage {
        Objects.requireNonNull(channelId, "channelId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(text, "text must not be null");
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    // ============ Factory Methods ============

    /**
     * A plain (non-mention) message: human-to-human talk, history only.
     */
    public static ChannelMessage of(String channelId, String userId, String text) {
        return new ChannelMessage(channelId, userId, text, false, Instant.now());
    }

    /**
     * An explicit mention: the text addresses the agent (prefix optional).
     */
    public static ChannelMessage mention(String channelId, String userId, String text) {
        return new ChannelMessage(channelId, userId, text, true, Instant.now());
    }

    /**
     * Auto-detect mention by "@agentId" prefix; the raw text is preserved
     * (use {@link #textWithoutMention(String)} when forwarding to the agent).
     * <p>
     * The prefix must be followed by a separator (space, colon, fullwidth
     * comma) or end-of-text: "@eng-bots" is a mention of ANOTHER agent id,
     * not of "eng-bot".
     */
    public static ChannelMessage autoDetect(String channelId, String userId, String text, String agentId) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        String prefix = "@" + agentId;
        boolean mentions = text.startsWith(prefix)
                && (text.length() == prefix.length() || isSeparator(text.charAt(prefix.length())));
        return new ChannelMessage(channelId, userId, text, mentions, Instant.now());
    }

    // ============ Helpers ============

    private static boolean isSeparator(char c) {
        return c == ' ' || c == ':' || c == '，' || c == '　';
    }

    /**
     * The text with the "@agentId" mention prefix (and one following
     * separator) stripped. Returns the raw text when no prefix matches.
     */
    public String textWithoutMention(String agentId) {
        String prefix = "@" + agentId;
        if (!mentionsAgent || !text.startsWith(prefix)) {
            return text;
        }
        String rest = text.substring(prefix.length());
        while (!rest.isEmpty() && (rest.charAt(0) == ' '
                || rest.charAt(0) == ':'
                || rest.charAt(0) == '，')) {
            rest = rest.substring(1);
        }
        return rest;
    }
}
