package io.github.qwzhang01.agent.chat.context;

import io.github.qwzhang01.agent.chat.ChatPersona;
import io.github.qwzhang01.agent.chat.Room;
import io.github.qwzhang01.agent.core.model.ChatMessage;

import java.util.List;

/**
 * One slice of model context. The engine concatenates sources in
 * registration order and does not interpret the text.
 */
public interface ContextSource {

    List<ChatMessage> contribute(Room room, ChatPersona speaker, String userText);
}
