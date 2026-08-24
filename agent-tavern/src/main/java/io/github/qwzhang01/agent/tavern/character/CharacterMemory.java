package io.github.qwzhang01.agent.tavern.character;

import io.github.qwzhang01.agent.core.agent.ContextBuilder;
import io.github.qwzhang01.agent.memory.MemoryContextBuilder;
import io.github.qwzhang01.agent.memory.MemoryRetriever;
import io.github.qwzhang01.agent.memory.MemoryScope;
import io.github.qwzhang01.agent.memory.MemoryStore;

import java.util.List;
import java.util.Objects;

/**
 * Character memory factory (Stage 16 M16.1): the scope whitelist is the whole design.
 * <p>
 * Two scopes per character-in-a-game:
 * <ul>
 *   <li>{@code agent:{characterId}} - the character's cross-game memory ("this player
 *       bought me a mead last time"). Survives across games: a character remembers
 *       you by memory, not by re-reading old chat transcripts (blueprint D2:
 *       memory, not history).</li>
 *   <li>{@code session:{gameId}} - this game's plot memory. A new game starts with
 *       a fresh session scope; last game's plot is invisible (cross-game isolation).</li>
 * </ul>
 * Sharing is a scope value, not a separate system (Stage 8 D3, the same trick as
 * Stage 12's {@code channelMemoryContext}). Cross-character isolation comes free:
 * lyra's whitelist never lists {@code agent:marcus}.
 * <p>
 * Zero new scope kinds are introduced - AGENT and SESSION already exist in
 * {@link MemoryScope.Kind}. This is the strongest form of the "same Runtime"
 * claim (blueprint D1: zero changes to existing modules).
 */
public final class CharacterMemory {

    /**
     * Default max memories injected per turn. Keeps persona + dialogue room in
     * the context; game memories are short lines, not documents.
     */
    public static final int DEFAULT_RECALL_LIMIT = 8;

    private final MemoryStore store;
    private final int recallLimit;

    public CharacterMemory(MemoryStore store) {
        this(store, DEFAULT_RECALL_LIMIT);
    }

    public CharacterMemory(MemoryStore store, int recallLimit) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        if (recallLimit < 0) {
            throw new IllegalArgumentException("recallLimit must be >= 0, got: " + recallLimit);
        }
        this.recallLimit = recallLimit;
    }

    /**
     * The scope whitelist for a character in a game:
     * {@code [agent:{characterId}, session:{gameId}]}.
     */
    public static List<String> scopesFor(String characterId, String gameId) {
        if (characterId == null || characterId.isBlank()) {
            throw new IllegalArgumentException("characterId must not be null or blank");
        }
        if (gameId == null || gameId.isBlank()) {
            throw new IllegalArgumentException("gameId must not be null or blank");
        }
        return List.of(
                MemoryScope.agent(characterId).value(),
                MemoryScope.session(gameId).value());
    }

    /**
     * Context builder bound to the character-in-a-game whitelist.
     * <p>
     * Compaction is deliberately off (null compressor) - the blueprint's honest
     * boundary (D2): v1 keeps full in-game history; end-of-game summarization is
     * a v2 refinement over Stage 8's ContextCompressor.
     */
    public ContextBuilder contextBuilder(String characterId, String gameId) {
        return new MemoryContextBuilder(
                new MemoryRetriever(store),
                scopesFor(characterId, gameId),
                null, null, null,
                recallLimit);
    }
}
