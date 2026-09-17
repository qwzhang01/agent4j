package io.github.qwzhang01.agent.chat.speaker;

import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.model.Room;
import io.github.qwzhang01.agent.chat.model.RoomMessage;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Director-driven beat continuation: after a persona's reply, a small
 * fast director model decides who (if anyone) naturally speaks next, or
 * STOP. Mirrors {@link DirectorSpeaker} for continuation beats.
 * <p>
 * Instructions are host-supplied and stay domain-neutral in the framework.
 * The reply vocabulary is a member id or the literal {@code STOP}.
 * Failure (empty / unknown id / exception) falls back to stopping the
 * turn — a single reply is always a complete turn.
 */
public final class DirectorBeatPolicy implements BeatPolicy {

    /** Literal the director replies to stop the turn. */
    public static final String STOP = "STOP";

    private final ModelClient directorClient;
    private final String instructions;
    private final DirectorChoiceParser parser;

    public DirectorBeatPolicy(ModelClient directorClient, String instructions) {
        this(directorClient, instructions, DirectorChoiceParser.memberId());
    }

    public DirectorBeatPolicy(ModelClient directorClient, String instructions,
                              DirectorChoiceParser parser) {
        this.directorClient = Objects.requireNonNull(directorClient, "directorClient");
        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("instructions must not be blank");
        }
        this.instructions = instructions.trim();
        this.parser = parser == null ? DirectorChoiceParser.memberId() : parser;
    }

    public String instructions() {
        return instructions;
    }

    @Override
    public Optional<ChatPersona> nextBeat(Room room, ChatPersona lastSpeaker, String lastReply) {
        List<ChatPersona> members = room.members();
        if (members.size() < 2) {
            return Optional.empty();
        }

        String text;
        try {
            ModelResponse response = directorClient.chat(buildRequest(room, lastSpeaker, lastReply));
            text = response == null ? "" : nullToEmpty(response.content());
        } catch (RuntimeException e) {
            return Optional.empty();
        }

        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty() || STOP.equalsIgnoreCase(trimmed)
                || trimmed.toUpperCase(java.util.Locale.ROOT).contains(STOP)) {
            return Optional.empty();
        }

        return parser.parse(trimmed, room)
                .flatMap(room::member)
                .filter(p -> !p.personaId().equals(lastSpeaker.personaId()));
    }

    private ModelRequest buildRequest(Room room, ChatPersona lastSpeaker, String lastReply) {
        String system = instructions
                + "\n\nMembers (reply with exactly one member id, or STOP):\n"
                + roster(room, lastSpeaker);
        String transcriptTail = transcriptTail(room);
        String user = "Last reply — " + lastSpeaker.displayName() + ": "
                + nullToEmpty(lastReply)
                + (transcriptTail.isEmpty() ? "" : "\n\nRecent transcript:\n" + transcriptTail);
        return ModelRequest.builder()
                .messages(List.of(
                        ChatMessage.system(system),
                        ChatMessage.user(user)))
                .temperature(0.0)
                .maxTokens(16)
                .build();
    }

    /**
     * Member roster marking the last speaker, so the director does not
     * re-pick them (no immediate self-followup).
     */
    private static String roster(Room room, ChatPersona lastSpeaker) {
        StringBuilder sb = new StringBuilder();
        for (ChatPersona persona : room.members()) {
            sb.append("- ").append(persona.personaId());
            if (!persona.displayName().equals(persona.personaId())) {
                sb.append(" (").append(persona.displayName()).append(')');
            }
            if (persona.personaId().equals(lastSpeaker.personaId())) {
                sb.append(" [just spoke]");
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /** Up to 6 recent messages, "[name]: text" one per line. */
    private static String transcriptTail(Room room) {
        List<RoomMessage> history = room.history();
        int from = Math.max(0, history.size() - 6);
        StringBuilder sb = new StringBuilder();
        for (RoomMessage message : history.subList(from, history.size())) {
            String name = message.role() == ChatRole.USER
                    ? "user"
                    : speakerName(room, message.speakerId());
            sb.append(name).append(": ").append(nullToEmpty(message.content())).append('\n');
        }
        return sb.toString().trim();
    }

    private static String speakerName(Room room, String personaId) {
        return room.member(personaId)
                .map(ChatPersona::displayName)
                .orElse(personaId);
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
