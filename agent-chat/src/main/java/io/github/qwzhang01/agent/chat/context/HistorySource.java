package io.github.qwzhang01.agent.chat.context;

import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.model.Room;
import io.github.qwzhang01.agent.chat.model.RoomMessage;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;

import java.util.ArrayList;
import java.util.List;

/**
 * Recent room transcript. The current user turn is omitted because
 * {@code SimpleAgent.prepare} appends it once the engine calls stream.
 * <p>
 * Ensemble rooms (multiple personas that see each other's replies) set
 * {@code speakerLabel} to render assistant history as
 * {@code [Name]: text} so each persona can tell who said what. Without
 * the label the output is unchanged (backward-compatible).
 */
public final class HistorySource implements ContextSource {

    public static final int DEFAULT_LIMIT = 20;

    private final int limit;
    private final boolean speakerLabel;

    public HistorySource() {
        this(DEFAULT_LIMIT);
    }

    public HistorySource(int limit) {
        this(limit, false);
    }

    /**
     * @param speakerLabel true renders assistant turns as "[Name]: text"
     *                     (ensemble rooms); false keeps plain history.
     */
    public HistorySource(int limit, boolean speakerLabel) {
        if (limit < 1) {
            throw new IllegalArgumentException("history limit must be >= 1");
        }
        this.limit = limit;
        this.speakerLabel = speakerLabel;
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
                if (speakerLabel) {
                    messages.add(ChatMessage.assistant(label(room, message, speaker)));
                } else {
                    messages.add(ChatMessage.assistant(message.content()));
                }
            }
        }
        return List.copyOf(messages);
    }

    /** "[Name]: " prefix for assistant lines; the speaker's own stays plain. */
    private String label(Room room, RoomMessage message, ChatPersona speaker) {
        if (speaker == null || message.speakerId().equals(speaker.personaId())) {
            return "";
        }
        return room.member(message.speakerId())
                .map(p -> "[" + p.displayName() + "]: ")
                .orElse("");
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
