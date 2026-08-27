package io.github.qwzhang01.agent.chat.speaker;

import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.model.Room;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Group rooms: a host-supplied director prompt plus {@link ModelClient}
 * picks one answering persona. Still one speaker per turn.
 * <p>
 * Use a dedicated {@code ModelClient} (often a small / fast model). The
 * chat {@link io.github.qwzhang01.agent.chat.ChatEngine} model is separate.
 * Compose as {@code new MentionSpeaker(new DirectorSpeaker(...))} when @
 * mentions should win.
 */
public final class DirectorSpeaker implements SpeakerPolicy {

    private final ModelClient directorClient;
    private final String instructions;
    private final SpeakerPolicy fallback;
    private final DirectorChoiceParser parser;

    public DirectorSpeaker(ModelClient directorClient, String instructions) {
        this(directorClient, instructions, null, DirectorChoiceParser.memberId());
    }

    public DirectorSpeaker(ModelClient directorClient, String instructions, SpeakerPolicy fallback) {
        this(directorClient, instructions, fallback, DirectorChoiceParser.memberId());
    }

    public DirectorSpeaker(ModelClient directorClient, String instructions,
                           SpeakerPolicy fallback, DirectorChoiceParser parser) {
        this.directorClient = Objects.requireNonNull(directorClient, "directorClient");
        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("instructions must not be blank");
        }
        this.instructions = instructions.trim();
        this.fallback = fallback;
        this.parser = parser == null ? DirectorChoiceParser.memberId() : parser;
    }

    public String instructions() {
        return instructions;
    }

    public SpeakerPolicy fallback() {
        return fallback;
    }

    @Override
    public Optional<ChatPersona> pick(Room room, String userText) {
        Objects.requireNonNull(room, "room");
        List<ChatPersona> members = room.members();
        if (members.isEmpty()) {
            return Optional.empty();
        }
        if (members.size() == 1) {
            return Optional.of(members.get(0));
        }

        try {
            ModelResponse response = directorClient.chat(buildRequest(room, userText));
            String text = response == null ? "" : nullToEmpty(response.content());
            Optional<String> chosen = parser.parse(text, room);
            if (chosen.isPresent()) {
                return room.member(chosen.get());
            }
        } catch (RuntimeException ignored) {
            // Host fallback handles director failure.
        }
        return fallback == null ? Optional.empty() : fallback.pick(room, userText);
    }

    private ModelRequest buildRequest(Room room, String userText) {
        String system = instructions + "\n\nMembers:\n" + roster(room);
        return ModelRequest.builder()
                .messages(List.of(
                        ChatMessage.system(system),
                        ChatMessage.user(userText == null ? "" : userText)))
                .temperature(0.0)
                .maxTokens(32)
                .build();
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    private static String roster(Room room) {
        StringBuilder sb = new StringBuilder();
        for (ChatPersona persona : room.members()) {
            sb.append("- ").append(persona.personaId());
            if (!persona.displayName().equals(persona.personaId())) {
                sb.append(" (").append(persona.displayName()).append(')');
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }
}
