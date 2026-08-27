package io.github.qwzhang01.agent.chat;

import io.github.qwzhang01.agent.chat.guard.ConsistencyGuard;
import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.model.Room;

/**
 * Business hook after a room turn. The engine never writes memories or
 * changes relationship state; the host does that here.
 */
public interface ChatListener {

    /**
     * A persona finished a reply that was written into room history.
     */
    default void onReplied(Room room, ChatPersona speaker, String userText, String reply) {
    }

    /**
     * Speaker policy picked nobody (e.g. group room, no @mention).
     * The user utterance is already on the room history.
     */
    default void onNoSpeaker(Room room, String userText) {
    }

    /**
     * Model or loop failed. The user utterance stays; no assistant line is stored.
     */
    default void onError(Room room, ChatPersona speaker, String userText, String message, Throwable cause) {
    }

    /**
     * Optional {@link ConsistencyGuard} flagged this turn. History already
     * has the original reply; the engine does not rewrite it.
     */
    default void onConsistencyWarning(Room room, ChatPersona speaker, String userText,
                                      String reply, String warning) {
    }
}
