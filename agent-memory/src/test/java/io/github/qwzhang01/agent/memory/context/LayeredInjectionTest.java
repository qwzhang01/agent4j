package io.github.qwzhang01.agent.memory.context;

import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryProvenance;
import io.github.qwzhang01.agent.memory.MemoryRetriever;
import io.github.qwzhang01.agent.memory.MemoryStatus;
import io.github.qwzhang01.agent.memory.MemoryType;
import io.github.qwzhang01.agent.memory.store.InMemoryMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layered injection (memory roadmap step 3): core tier resident at the head,
 * archival tier paged in beside the current turn.
 * <p>
 * Flagship scenario per the design note: a user with one core identity fact
 * (home city, importance 0.95) and two archival facts (diet at 0.8, episodic
 * purchase at 0.6). The core block must appear in EVERY turn regardless of
 * the query; the archival block must follow the query (diet surfaces for a
 * lunch question, the purchase surfaces for a shopping question); placement
 * must be core-head + archival-beside-the-turn; the legacy six-arg path must
 * keep its single-block behaviour bit-for-bit.
 */
class LayeredInjectionTest {

    private InMemoryMemoryStore store;
    private MemoryRetriever retriever;

    @BeforeEach
    void setUp() {
        store = new InMemoryMemoryStore();
        retriever = new MemoryRetriever(store);
    }

    // Flagship: two tiers, different physics

    @Test
    @DisplayName("flagship: core resident at head, archival beside the turn, ranked by query")
    void flagship_coreAndArchival_layersSeparated() {
        store.write(entry("user:u1", MemoryType.FACT, "home-city", "lives in Shenzhen", 0.95));
        store.write(entry("user:u1", MemoryType.PREFERENCE, "diet", "allergic to peanuts", 0.8));
        store.write(entry("user:u1", MemoryType.EPISODE, "purchase", "bought a mechanical keyboard", 0.6));

        MemoryContextBuilder builder = new MemoryContextBuilder(
                retriever, List.of("user:u1"), null, null, null, 0,
                MemoryLayering.of(0.9, 0), 4);

        AgentState state = new AgentState();
        state.addMessage(ChatMessage.user("What should I eat for lunch?"));

        List<ChatMessage> built = builder.build(null, state);

        // Core block at the head, then (no prior history) the archival block,
        // then the user message. Query "eat lunch" ranks diet above purchase.
        assertEquals(3, built.size());
        assertTrue(built.get(0).content().startsWith("[Core memories]"));
        assertTrue(built.get(0).content().contains("lives in Shenzhen"));
        assertTrue(built.get(1).content().startsWith("[Known memories]"));
        int dietIndex = built.get(1).content().indexOf("peanuts");
        int keyboardIndex = built.get(1).content().indexOf("keyboard");
        assertTrue(dietIndex >= 0 && keyboardIndex >= 0,
                "both archival entries enter (limit 4 covers all)");
        assertTrue(dietIndex < keyboardIndex,
                "diet must outrank the keyboard episode for a lunch question");
        assertEquals("What should I eat for lunch?", built.get(2).content());

        // Injection never enters state.
        assertEquals(1, state.getMessages().size());
    }

    @Test
    @DisplayName("archival follows the query: a shopping question pages in the purchase instead")
    void archivalFollowsQuery_notFixedToImportance() {
        store.write(entry("user:u1", MemoryType.FACT, "home-city", "lives in Shenzhen", 0.95));
        store.write(entry("user:u1", MemoryType.PREFERENCE, "diet", "allergic to peanuts", 0.7));
        store.write(entry("user:u1", MemoryType.EPISODE, "purchase", "bought a mechanical keyboard", 0.65));

        MemoryContextBuilder builder = new MemoryContextBuilder(
                retriever, List.of("user:u1"), null, null, null, 0,
                MemoryLayering.of(0.9, 0), 4);

        AgentState state = new AgentState();
        state.addMessage(ChatMessage.assistant("hi, ask me anything about shopping or keyboards"));
        state.addMessage(ChatMessage.user("Which keyboard should I buy next?"));

        List<ChatMessage> built = builder.build(null, state);

        assertEquals(4, built.size());
        assertTrue(built.get(0).content().startsWith("[Core memories]"));
        assertTrue(built.get(0).content().contains("lives in Shenzhen"));
        // Archival block sits immediately BEFORE the last USER message.
        assertTrue(built.get(2).content().startsWith("[Known memories]"));
        int keyboardIdx = built.get(2).content().indexOf("mechanical keyboard");
        int peanutIdx = built.get(2).content().indexOf("peanuts");
        assertTrue(keyboardIdx >= 0 && peanutIdx >= 0,
                "both archival entries enter (limit 4 covers all)");
        assertTrue(keyboardIdx < peanutIdx,
                "the purchase episode must outrank diet for a keyboard question");
        assertEquals("Which keyboard should I buy next?", built.get(3).content());
    }

    // Token budget

    @Test
    @DisplayName("token budget trims archival, never core")
    void tokenBudget_trimsArchivalNeverCore() {
        store.write(entry("user:u1", MemoryType.FACT, "home-city", "lives in Shenzhen", 0.95));
        store.write(entry("user:u1", MemoryType.PREFERENCE, "diet",
                "allergic to peanuts; also dislikes cilantro; prefers spicy food; avoids sugar", 0.8));
        store.write(entry("user:u1", MemoryType.EPISODE, "purchase",
                "bought a mechanical keyboard last month and it broke within two weeks", 0.6));

        // Budget small enough that core + diet fits, but the episode does not.
        // home-city: "home-city: lives in Shenzhen" = 25 chars -> 6 tokens.
        // diet: "diet: allergic to peanuts; also dislikes cilantro; prefers spicy food; avoids sugar" = 85 chars -> 21 tokens.
        // purchase: ~90 chars -> 22 tokens. Budget 30 keeps core(6) + diet(21) = 27, drops purchase.
        MemoryContextBuilder builder = new MemoryContextBuilder(
                retriever, List.of("user:u1"), null, null, null, 0,
                MemoryLayering.of(0.9, 30), 4);

        AgentState state = new AgentState();
        state.addMessage(ChatMessage.user("What should I eat?"));

        List<ChatMessage> built = builder.build(null, state);

        assertTrue(built.get(0).content().contains("lives in Shenzhen"),
                "core is never dropped by the budget");
        String archivalBlock = built.stream()
                .filter(m -> m.content() != null && m.content().startsWith("[Known memories]"))
                .findFirst().orElseThrow().content();
        assertTrue(archivalBlock.contains("peanuts"), "diet fits the remaining budget");
        assertFalse(archivalBlock.contains("keyboard"),
                "the episode exceeds the remaining budget and is dropped");
    }

    // Soft failure

    @Test
    @DisplayName("recall failure degrades to no injection, never breaks the turn")
    void recallFailure_degradesToNoInjection() {
        MemoryRetriever throwing = new MemoryRetriever(store) {
            @Override
            public List<MemoryEntry> recallForContext(List<String> scopes, int limit, String query) {
                throw new IllegalStateException("store down");
            }
        };
        MemoryContextBuilder builder = new MemoryContextBuilder(
                throwing, List.of("user:u1"), null, null, null, 0,
                MemoryLayering.of(0.9, 0), 4);

        AgentState state = new AgentState();
        state.addMessage(ChatMessage.user("hello"));

        List<ChatMessage> built = builder.build(null, state);

        assertEquals(1, built.size());
        assertEquals("hello", built.get(0).content());
    }

    // Legacy path bit-for-bit

    @Test
    @DisplayName("six-arg constructor keeps the legacy single-block behaviour")
    void legacyPath_unchanged() {
        store.write(entry("user:u1", MemoryType.FACT, "home-city", "lives in Shenzhen", 0.95));
        store.write(entry("user:u1", MemoryType.PREFERENCE, "diet", "allergic to peanuts", 0.8));

        MemoryContextBuilder builder = new MemoryContextBuilder(
                retriever, List.of("user:u1"), null, null, null, 0);

        AgentState state = new AgentState();
        state.addMessage(ChatMessage.user("What should I eat?"));

        List<ChatMessage> built = builder.build(null, state);

        // Legacy: ONE block at the head, [Known memories], both entries in
        // importance order, no [Core memories] anywhere.
        assertEquals(2, built.size());
        assertTrue(built.get(0).content().startsWith("[Known memories]"));
        assertTrue(built.get(0).content().contains("lives in Shenzhen"));
        assertTrue(built.get(0).content().contains("allergic to peanuts"));
        assertFalse(built.stream().anyMatch(m ->
                m.content() != null && m.content().contains("[Core memories]")));
        assertEquals("What should I eat?", built.get(1).content());
    }

    private static MemoryEntry entry(String scope, MemoryType type, String subject,
                                     String content, double importance) {
        return new MemoryEntry(null, scope, type, subject, content, importance,
                MemoryProvenance.userSaid("u1", "r1", Instant.now()),
                MemoryStatus.ACTIVE, Instant.now(), null);
    }
}
