package io.github.qwzhang01.agent.chat.speaker;

import io.github.qwzhang01.agent.chat.ChatPersona;
import io.github.qwzhang01.agent.chat.Room;

import java.util.Optional;

/**
 * Chooses who answers this user utterance. Empty means nobody speaks.
 */
public interface SpeakerPolicy {

    Optional<ChatPersona> pick(Room room, String userText);
}
