package io.github.qwzhang01.agent.tavern.character;

import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.memory.store.InMemoryMemoryStore;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryProvenance;
import io.github.qwzhang01.agent.memory.MemoryRetriever;
import io.github.qwzhang01.agent.memory.MemoryStatus;
import io.github.qwzhang01.agent.memory.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 16 M16.1 core test: the character memory whitelist.
 * <p>
 * The blueprint's claims under test (D1/D2): sharing is a scope value, not a
 * separate system. The two-scene whitelist [agent:{charId}, session:{gameId}]
 * must give exactly:
 * <ul>
 *   <li>cross-game character memory visible (a character "remembers you"),</li>
 *   <li>this game's plot visible,</li>
 *   <li>another game's plot invisible (cross-game isolation),</li>
 *   <li>another character's private memory invisible (cross-character isolation).</li>
 * </ul>
 * Same store, same retriever, no new scope kinds - the Stage 8 mechanism carries
 * the whole game memory semantics. This is the M15.1 TenantIsolationTest pattern
 * applied to the second Profile.
 */
class CharacterMemoryTest {

    private InMemoryMemoryStore store;
    private MemoryRetriever retriever;

    @BeforeEach
    void setUp() {
        store = new InMemoryMemoryStore();
        retriever = new MemoryRetriever(store);
    }

    private MemoryEntry episode(String scope, String subject, String content) {
        return new MemoryEntry(
                null, scope, MemoryType.EPISODE, subject, content, 0.8,
                MemoryProvenance.userSaid("player-1", "turn-1", Instant.now()),
                MemoryStatus.ACTIVE, Instant.now(), null
        );
    }

    // ============ Scope Format ============

    @Test
    @DisplayName("scopesFor produces [agent:{charId}, session:{gameId}] with existing kinds")
    void scopesFormat() {
        List<String> scopes = CharacterMemory.scopesFor("marcus", "game-1");

        assertEquals(List.of("agent:marcus", "session:game-1"), scopes);
    }

    @Test
    @DisplayName("blank characterId or gameId is rejected fail-fast")
    void blankIdsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CharacterMemory.scopesFor(" ", "game-1"));
        assertThrows(IllegalArgumentException.class,
                () -> CharacterMemory.scopesFor("marcus", null));
    }

    // ============ Whitelist Semantics ============

    @Test
    @DisplayName("both scopes are visible: cross-game character memory + this game's plot")
    void bothScopesVisible() {
        store.write(episode("agent:marcus", "mead", "The player bought me a mead last visit"));
        store.write(episode("session:game-1", "dice", "The player won the dice game tonight"));

        List<io.github.qwzhang01.agent.memory.MemoryEntry> visible =
                retriever.recall(CharacterMemory.scopesFor("marcus", "game-1"));

        assertEquals(2, visible.size(), "exactly the whitelist: agent:marcus + session:game-1");
        assertTrue(visible.stream().anyMatch(e -> e.content().contains("mead")));
        assertTrue(visible.stream().anyMatch(e -> e.content().contains("dice")));
    }

    @Test
    @DisplayName("another character's private memory is invisible (cross-character isolation)")
    void crossCharacterIsolation() {
        store.write(episode("agent:marcus", "mead", "The player bought me a mead last visit"));
        store.write(episode("agent:lyra", "secret", "I secretly dislike loud crowds"));

        List<io.github.qwzhang01.agent.memory.MemoryEntry> lyraView =
                retriever.recall(CharacterMemory.scopesFor("lyra", "game-1"));

        assertEquals(1, lyraView.size());
        assertTrue(lyraView.get(0).content().contains("loud crowds"));
        assertFalse(lyraView.stream().anyMatch(e -> e.content().contains("mead")),
                "marcus's private memory must never leak into lyra's whitelist");
    }

    @Test
    @DisplayName("another game's plot is invisible (cross-game isolation)")
    void crossGameIsolation() {
        store.write(episode("session:game-1", "dice", "The player won the dice game in game one"));
        store.write(episode("session:game-2", "brawl", "A stranger argued with the bard in game two"));

        List<io.github.qwzhang01.agent.memory.MemoryEntry> gameOneView =
                retriever.recall(CharacterMemory.scopesFor("marcus", "game-1"));

        assertEquals(1, gameOneView.size());
        assertTrue(gameOneView.get(0).content().contains("dice"));
        assertFalse(gameOneView.stream().anyMatch(e -> e.content().contains("brawl")),
                "last game's plot must not bleed into a new game");
    }

    @Test
    @DisplayName("character memory survives across games - a character remembers you (D2)")
    void characterMemorySurvivesAcrossGames() {
        store.write(episode("agent:marcus", "mead", "The player bought me a mead last visit"));
        store.write(episode("session:game-1", "dice", "The player won the dice game in game one"));

        // a NEW game: marcus remembers the player, but not last game's plot
        List<io.github.qwzhang01.agent.memory.MemoryEntry> newGameView =
                retriever.recall(CharacterMemory.scopesFor("marcus", "game-2"));

        assertEquals(1, newGameView.size());
        assertTrue(newGameView.get(0).content().contains("mead"),
                "cross-game memory is the character's lasting impression");
        assertFalse(newGameView.stream().anyMatch(e -> e.content().contains("dice")),
                "the old game's session scope is gone with the game");
    }

    // ============ Context Builder Injection ============

    @Test
    @DisplayName("memories are injected right after the system prompt as [Known memories]")
    void memoriesInjectedAfterSystemPrompt() {
        store.write(episode("agent:marcus", "mead", "The player bought me a mead last visit"));

        CharacterMemory memory = new CharacterMemory(store);
        AgentState state = new AgentState();
        state.addMessage(ChatMessage.system("You are Marcus."));
        state.addMessage(ChatMessage.user("Hello!"));

        AgentConfig config = new AgentConfig("marcus", "You are Marcus.", null, null);
        List<ChatMessage> built = memory.contextBuilder("marcus", "game-1").build(config, state);

        assertEquals(3, built.size());
        assertEquals(ChatRole.SYSTEM, built.get(0).role());
        assertEquals(ChatRole.USER, built.get(1).role());
        assertTrue(built.get(1).content().contains("[Known memories]"),
                "the injection block is a stable contract (changing it breaks replay/debug tooling)");
        assertTrue(built.get(1).content().contains("mead"));
        assertEquals("Hello!", built.get(2).content());
    }

    @Test
    @DisplayName("recallLimit bounds how many memories enter the context")
    void recallLimitBoundsInjection() {
        store.write(episode("agent:marcus", "a", "memory one"));
        store.write(episode("agent:marcus", "b", "memory two"));
        store.write(episode("agent:marcus", "c", "memory three"));

        CharacterMemory memory = new CharacterMemory(store, 2);
        AgentConfig config = new AgentConfig("marcus", "p", null, null);
        List<ChatMessage> built = memory.contextBuilder("marcus", "game-1").build(config, new AgentState());

        // all memory lines render inside ONE injected user message - count lines, not messages
        String injected = built.get(0).content();
        assertTrue(injected.contains("[Known memories]"));
        long memoryLines = injected.lines().filter(line -> line.startsWith("- [")).count();
        assertEquals(2, memoryLines, "only the first 2 of 3 memories enter the context");
    }

    @Test
    @DisplayName("no memories in scope = no injection block at all (empty store stays silent)")
    void noMemoriesNoInjection() {
        CharacterMemory memory = new CharacterMemory(store);
        AgentState state = new AgentState();
        state.addMessage(ChatMessage.system("p"));
        state.addMessage(ChatMessage.user("hi"));

        AgentConfig config = new AgentConfig("marcus", "p", null, null);
        List<ChatMessage> built = memory.contextBuilder("marcus", "game-1").build(config, state);

        assertEquals(2, built.size(), "nothing injected when the whitelist sees nothing");
    }
}
