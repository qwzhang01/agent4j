package io.github.qwzhang01.agent.chat.speaker;

import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.model.Room;
import io.github.qwzhang01.agent.chat.model.RoomMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;

import java.util.List;
import java.util.Optional;

/**
 * Group rooms: rotate the answering persona in member order. The host
 * supplies member order via {@link Room#members()}; this policy does not
 * inject protagonist or narrative text (use {@code ExtraTextSource}).
 * <p>
 * Rotation is derived from the last assistant utterance already in
 * {@link Room#history()}. The current user line is appended before
 * {@link SpeakerPolicy#pick} runs, so it is ignored when locating the
 * previous speaker.
 */
public final class RoundRobinSpeaker implements SpeakerPolicy {

    @Override
    public Optional<ChatPersona> pick(Room room, String userText) {
        List<ChatPersona> members = room.members();
        if (members.isEmpty()) {
            return Optional.empty();
        }
        if (members.size() == 1) {
            return Optional.of(members.get(0));
        }

        String lastAssistantId = lastAssistantSpeakerId(room);
        if (lastAssistantId == null) {
            return Optional.of(members.get(0));
        }

        int lastIndex = indexOf(members, lastAssistantId);
        int nextIndex = lastIndex >= 0 ? (lastIndex + 1) % members.size() : 0;
        return Optional.of(members.get(nextIndex));
    }

    static String lastAssistantSpeakerId(Room room) {
        List<RoomMessage> history = room.history();
        int end = history.size();
        if (end > 0 && history.get(end - 1).role() == ChatRole.USER) {
            end--;
        }
        for (int i = end - 1; i >= 0; i--) {
            RoomMessage message = history.get(i);
            if (message.role() == ChatRole.ASSISTANT) {
                return message.speakerId();
            }
        }
        return null;
    }

    private static int indexOf(List<ChatPersona> members, String personaId) {
        for (int i = 0; i < members.size(); i++) {
            if (members.get(i).personaId().equals(personaId)) {
                return i;
            }
        }
        return -1;
    }
}
