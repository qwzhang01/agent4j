package io.github.qwzhang01.agent.chat.model;

import io.github.qwzhang01.agent.core.model.ChatRole;

import java.time.Instant;
import java.util.Objects;

/**
 * One utterance stored on a {@link Room}.
 *
 * @param speakerId {@link #USER_SPEAKER_ID} or a {@link ChatPersona#personaId()}
 * @param role      USER or ASSISTANT
 * @param content   text
 * @param at        when it was appended
 */
public record RoomMessage(String speakerId, ChatRole role, String content, Instant at) {

    public static final String USER_SPEAKER_ID = "user";

    public RoomMessage {
        if (speakerId == null || speakerId.isBlank()) {
            throw new IllegalArgumentException("speakerId must not be null or blank");
        }
        Objects.requireNonNull(role, "role");
        if (role != ChatRole.USER && role != ChatRole.ASSISTANT) {
            throw new IllegalArgumentException("room messages must be USER or ASSISTANT, got: " + role);
        }
        content = content == null ? "" : content;
        at = at == null ? Instant.now() : at;
    }

    public static RoomMessage user(String content) {
        return new RoomMessage(USER_SPEAKER_ID, ChatRole.USER, content, Instant.now());
    }

    public static RoomMessage assistant(String personaId, String content) {
        return new RoomMessage(personaId, ChatRole.ASSISTANT, content, Instant.now());
    }
}
