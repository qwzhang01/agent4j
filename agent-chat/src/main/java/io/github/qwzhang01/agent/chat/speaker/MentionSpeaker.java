package io.github.qwzhang01.agent.chat.speaker;

import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.model.Room;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * First {@code @personaId} that matches a room member. Optional fallback
 * (often {@link SoloSpeaker}) runs when nobody was mentioned.
 */
public final class MentionSpeaker implements SpeakerPolicy {

    private static final Pattern MENTION = Pattern.compile("@([A-Za-z0-9_-]+)");

    private final SpeakerPolicy fallback;

    public MentionSpeaker() {
        this(null);
    }

    public MentionSpeaker(SpeakerPolicy fallback) {
        this.fallback = fallback;
    }

    @Override
    public Optional<ChatPersona> pick(Room room, String userText) {
        if (userText != null) {
            Matcher matcher = MENTION.matcher(userText);
            while (matcher.find()) {
                Optional<ChatPersona> hit = room.member(matcher.group(1));
                if (hit.isPresent()) {
                    return hit;
                }
            }
        }
        return fallback == null ? Optional.empty() : fallback.pick(room, userText);
    }
}
