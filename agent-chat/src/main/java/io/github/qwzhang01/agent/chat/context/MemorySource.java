package io.github.qwzhang01.agent.chat.context;

import io.github.qwzhang01.agent.chat.ChatPersona;
import io.github.qwzhang01.agent.chat.Room;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryRetriever;

import java.util.List;
import java.util.Objects;

/**
 * Optional recall slice: {@link MemoryRetriever} + host-supplied scopes + limit.
 * <p>
 * Not registered by {@link ContextAssembler#defaults()} or
 * {@link io.github.qwzhang01.agent.chat.ChatRoom.Builder} unless the host
 * calls {@code .source(new MemorySource(...))}. Calling {@code .source()}
 * replaces the default Persona + History pair; register those explicitly
 * if they are still wanted.
 * <p>
 * This class does not extract, schedule, or interpret {@code subject}.
 * The host decides what is in the store and which scopes are visible.
 */
public final class MemorySource implements ContextSource {

    private final MemoryRetriever retriever;
    private final List<String> scopes;
    private final int limit;

    /**
     * Recall every ACTIVE entry in {@code scopes} (no topN cut-off).
     */
    public MemorySource(MemoryRetriever retriever, List<String> scopes) {
        this(retriever, scopes, 0);
    }

    /**
     * @param limit max entries after importance-then-recency rank;
     *              {@code <= 0} means no cut-off
     */
    public MemorySource(MemoryRetriever retriever, List<String> scopes, int limit) {
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        this.scopes = List.copyOf(Objects.requireNonNull(scopes, "scopes"));
        this.limit = limit;
    }

    public List<String> scopes() {
        return scopes;
    }

    public int limit() {
        return limit;
    }

    @Override
    public List<ChatMessage> contribute(Room room, ChatPersona speaker, String userText) {
        if (scopes.isEmpty()) {
            return List.of();
        }
        List<MemoryEntry> memories = retriever.recallForContext(scopes, limit);
        if (memories.isEmpty()) {
            return List.of();
        }
        return List.of(ChatMessage.system("[Known memories]\n" + render(memories)));
    }

    private static String render(List<MemoryEntry> memories) {
        StringBuilder sb = new StringBuilder();
        for (MemoryEntry memory : memories) {
            sb.append("- [").append(memory.type()).append("] ");
            if (memory.subject() != null) {
                sb.append(memory.subject()).append(": ");
            }
            sb.append(memory.content()).append('\n');
        }
        return sb.toString().trim();
    }
}
