package io.github.qwzhang01.agent.chat.context;

import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.model.Room;
import io.github.qwzhang01.agent.chat.model.RoomIdentity;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryRetriever;
import io.github.qwzhang01.agent.memory.MemoryType;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Optional recall slice: {@link MemoryRetriever} + host-supplied scopes + limit.
 * <p>
 * Not registered by {@link ContextAssembler#defaults} or
 * {@link io.github.qwzhang01.agent.chat.ChatRoom.Builder} unless the host
 * calls {@code .source(new MemorySource(...))}. Calling {@code .source}
 * replaces the default Persona + History pair; register those explicitly
 * if they are still wanted.
 * <p>
 * Scopes may be set on the source, or inherited from {@link Room#scopes}
 * when the source was built without an explicit list. An explicit empty
 * list still means "recall nothing".
 * <p>
 * This class does not extract, schedule, or interpret {@code subject}.
 * The host decides what is in the store and which scopes are visible.
 * <p>
 * <b>Not safe to share across concurrent rooms/turns.</b> {@link #lastRecalledSubjects}
 * is a "last {@code contribute} call" snapshot used by
 * {@link io.github.qwzhang01.agent.chat.ChatEngine} to build {@code TurnTrace} right
 * after {@code contribute} returns on the same thread. Each {@code ChatRoom}/
 * {@code ChatEngine} must own its own {@code MemorySource} instance; if the same
 * instance is registered on two rooms, or the same room's {@code stream} is invoked
 * concurrently from multiple threads, one turn's {@code TurnTrace} can observe another
 * turn's recalled subjects.
 */
public final class MemorySource implements ContextSource {

    private final MemoryRetriever retriever;
    /** {@code null} = inherit from {@link Room#scopes} */
    private final List<String> scopes;
    private final int limit;
    /**
     * Subjects of memory entries injected in the most recent {@link #contribute} call.
     * See the class-level thread-safety note: one instance = one room, one turn at a time.
     */
    private volatile List<String> lastRecalledSubjects = List.of();

    /**
     * Inherit scopes from the room identity. No topN cut-off.
     */
    public MemorySource(MemoryRetriever retriever) {
        this(retriever, null, 0);
    }

    /**
     * Inherit scopes from the room identity.
     *
     * @param limit max entries after importance-then-recency rank;
     *              {@code <= 0} means no cut-off
     */
    public MemorySource(MemoryRetriever retriever, int limit) {
        this(retriever, null, limit);
    }

    /**
     * Recall every ACTIVE entry in {@code scopes} (no topN cut-off).
     * {@code null} scopes inherit from the room; empty list recalls nothing.
     */
    public MemorySource(MemoryRetriever retriever, List<String> scopes) {
        this(retriever, scopes, 0);
    }

    /**
     * @param scopes explicit list, or {@code null} to inherit from the room
     * @param limit max entries after importance-then-recency rank;
     *               {@code <= 0} means no cut-off
     */
    public MemorySource(MemoryRetriever retriever, List<String> scopes, int limit) {
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        this.scopes = scopes == null ? null : List.copyOf(scopes);
        this.limit = limit;
    }

    /**
     * Explicit scopes, or {@code null} when this source inherits from the room.
     */
    public List<String> scopes() {
        return scopes;
    }

    public int limit() {
        return limit;
    }

    /**
     * Subject keys of all entries injected during the most recent {@link #contribute} call.
     * Empty list if the source has never been called or contributed nothing.
     * Used by {@link io.github.qwzhang01.agent.chat.ChatEngine} to populate
     * {@link io.github.qwzhang01.agent.core.agent.AgentEvent.TurnTrace#recalledSubjects}.
     */
    public List<String> lastRecalledSubjects() {
        return lastRecalledSubjects;
    }

    public List<String> resolveScopes(Room room) {
        if (scopes != null) {
            return scopes;
        }
        return room == null ? List.of() : room.scopes();
    }

    @Override
    public List<ChatMessage> contribute(Room room, ChatPersona speaker, String userText) {
        List<String> visible = resolveScopes(room);
        if (visible.isEmpty()) {
            return List.of();
        }

        // SUMMARY pool: always capped at 1 slot so that high-importance summaries
        // never crowd out topic-specific FACT / EPISODE / PREFERENCE entries.
        List<MemoryEntry> summaries = retriever.recallSummaries(visible);
        List<MemoryEntry> usedSummaries = summaries.isEmpty()
                ? List.of()
                : List.of(summaries.get(0));

        // Fact/Event pool: remaining slots, SUMMARY type excluded.
        // Fetch all entries (respects subclass overrides such as Moonlit's log_* filter),
        // strip any SUMMARY that slipped through, then cap at the remaining slot count.
        // NOTE: "no cut-off" (limit <= 0) and "zero slots left" (limit == summary slots
        // used) are both expressed with the integer 0 in different places, so they must
        // NOT share a single sentinel check — otherwise "zero slots left" would be
        // misread as "unlimited" and SUMMARY's dedicated slot would be defeated by an
        // unbounded FACT pool. Use a long with Long.MAX_VALUE as the one true "no
        // cut-off" sentinel instead.
        long factLimit = (limit <= 0)
                ? Long.MAX_VALUE
                : Math.max(0, limit - usedSummaries.size());
        List<MemoryEntry> facts = retriever.recallForContext(visible, 0, userText).stream()
                .filter(e -> e.type() != MemoryType.SUMMARY)
                .limit(factLimit)
                .toList();

        List<MemoryEntry> memories = Stream.concat(usedSummaries.stream(), facts.stream()).toList();
        lastRecalledSubjects = memories.stream()
                .map(MemoryEntry::subject)
                .filter(Objects::nonNull)
                .toList();
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
