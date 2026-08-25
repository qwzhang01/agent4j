package io.github.qwzhang01.agent.chat.context;

import io.github.qwzhang01.agent.chat.ChatPersona;
import io.github.qwzhang01.agent.chat.Room;
import io.github.qwzhang01.agent.chat.RoomMessage;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;

import java.util.ArrayList;
import java.util.List;

/**
 * Recent room transcript. The current user turn is omitted because
 * {@code SimpleAgent.prepare} appends it once the engine calls stream.
 */
public final class HistorySource implements ContextSource {

    public static final int DEFAULT_LIMIT = 20;

    private final int limit;

    public HistorySource() {
        this(DEFAULT_LIMIT);
    }

    public HistorySource(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("history limit must be >= 1");
        }
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }

    @Override
    public List<ChatMessage> contribute(Room room, ChatPersona speaker, String userText) {
        List<RoomMessage> prior = omitCurrentUserTurn(room.history());
        if (prior.size() > limit) {
            prior = prior.subList(prior.size() - limit, prior.size());
        }
        List<ChatMessage> messages = new ArrayList<>(prior.size());
        for (RoomMessage message : prior) {
            if (message.role() == ChatRole.USER) {
                messages.add(ChatMessage.user(message.content()));
            } else if (message.role() == ChatRole.ASSISTANT) {
                messages.add(ChatMessage.assistant(message.content()));
            }
        }
        return List.copyOf(messages);
    }

    static List<RoomMessage> omitCurrentUserTurn(List<RoomMessage> history) {
        if (history.isEmpty()) {
            return List.of();
        }
        RoomMessage last = history.get(history.size() - 1);
        if (last.role() == ChatRole.USER) {
            return history.subList(0, history.size() - 1);
        }
        return history;
    }
}
