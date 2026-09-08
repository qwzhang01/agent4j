package io.github.qwzhang01.agent.chat.context;

import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.model.Room;
import io.github.qwzhang01.agent.core.model.ChatMessage;

import java.util.List;

/**
 * One slice of model context. The engine concatenates sources in
 * registration order and does not interpret the text.
 * <p>
 * Implementations that track "most recent call" telemetry (e.g. for
 * {@code AgentEvent.TurnTrace}, see {@code MemorySource} / {@code ExtraTextSource})
 * are inherently stateful and must be owned by exactly one room. Do not register
 * the same source instance on more than one {@code ChatRoom}, and do not invoke a
 * room's {@code stream()} concurrently from multiple threads — both would let one
 * turn observe another turn's telemetry.
 */
public interface ContextSource {

    List<ChatMessage> contribute(Room room, ChatPersona speaker, String userText);
}
