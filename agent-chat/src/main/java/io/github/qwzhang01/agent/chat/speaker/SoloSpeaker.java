package io.github.qwzhang01.agent.chat.speaker;

import io.github.qwzhang01.agent.chat.ChatPersona;
import io.github.qwzhang01.agent.chat.Room;

import java.util.Optional;

/**
 * 1:1 rooms: the only member always answers. Throws if the room has
 * more than one persona so a group room cannot silently pick the first.
 */
public final class SoloSpeaker implements SpeakerPolicy {

    @Override
    public Optional<ChatPersona> pick(Room room, String userText) {
        var members = room.members();
        if (members.size() != 1) {
            throw new IllegalStateException(
                    "SoloSpeaker requires exactly one persona, room '" + room.roomId()
                            + "' has " + members.size());
        }
        return Optional.of(members.get(0));
    }
}
