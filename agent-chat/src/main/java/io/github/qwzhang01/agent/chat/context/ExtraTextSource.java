package io.github.qwzhang01.agent.chat.context;

import io.github.qwzhang01.agent.chat.ChatPersona;
import io.github.qwzhang01.agent.chat.Room;
import io.github.qwzhang01.agent.core.model.ChatMessage;

import java.util.List;

/**
 * Host-supplied atmosphere / narrative text. Injected as a system
 * message after whatever sources were registered before it.
 * <p>
 * Structured relationship views use {@link RelationSource}. Worldbook
 * entries use {@link LoreSource}.
 */
public final class ExtraTextSource implements ContextSource {

    private final String text;

    public ExtraTextSource(String text) {
        this.text = text == null ? "" : text;
    }

    @Override
    public List<ChatMessage> contribute(Room room, ChatPersona speaker, String userText) {
        if (text.isBlank()) {
            return List.of();
        }
        return List.of(ChatMessage.system(text));
    }
}
