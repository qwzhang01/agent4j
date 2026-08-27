package io.github.qwzhang01.agent.chat.context;

import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.model.Room;
import io.github.qwzhang01.agent.core.model.ChatMessage;

import java.util.List;

/**
 * Injects the speaker's {@link ChatPersona#systemPrompt()} verbatim.
 * Blank prompts contribute nothing.
 */
public final class PersonaSource implements ContextSource {

    @Override
    public List<ChatMessage> contribute(Room room, ChatPersona speaker, String userText) {
        String prompt = speaker.systemPrompt();
        if (prompt == null || prompt.isBlank()) {
            return List.of();
        }
        return List.of(ChatMessage.system(prompt));
    }
}
