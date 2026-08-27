package io.github.qwzhang01.agent.chat.speaker;

import io.github.qwzhang01.agent.chat.ChatPersona;
import io.github.qwzhang01.agent.chat.Room;

import java.util.Locale;
import java.util.Optional;

/**
 * Maps a director model reply to a room member id. The engine does not
 * own routing vocabulary; hosts may supply their own parser.
 */
@FunctionalInterface
public interface DirectorChoiceParser {

    Optional<String> parse(String modelText, Room room);

    /**
     * Trim, then exact id, then case-insensitive exact, then first
     * substring hit in member order.
     */
    static DirectorChoiceParser memberId() {
        return (text, room) -> {
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }
            String trimmed = text.trim();
            for (ChatPersona persona : room.members()) {
                if (persona.personaId().equals(trimmed)) {
                    return Optional.of(persona.personaId());
                }
            }
            String folded = trimmed.toLowerCase(Locale.ROOT);
            for (ChatPersona persona : room.members()) {
                if (persona.personaId().toLowerCase(Locale.ROOT).equals(folded)) {
                    return Optional.of(persona.personaId());
                }
            }
            for (ChatPersona persona : room.members()) {
                if (folded.contains(persona.personaId().toLowerCase(Locale.ROOT))) {
                    return Optional.of(persona.personaId());
                }
            }
            return Optional.empty();
        };
    }
}
