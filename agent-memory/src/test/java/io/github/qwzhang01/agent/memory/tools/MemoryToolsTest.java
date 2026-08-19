package io.github.qwzhang01.agent.memory.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.memory.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 8 M8.5 tests: MemoryTools (save_memory / search_memory).
 */
class MemoryToolsTest {

    private InMemoryMemoryStore store;
    private MemoryRetriever retriever;
    private MemoryPolicy policy;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        store = new InMemoryMemoryStore();
        retriever = new MemoryRetriever(store);
        policy = new MemoryPolicy(0.5);
        mapper = new ObjectMapper();
    }

    @Test
    void saveMemory_storesEntry() throws Exception {
        Tool save = MemoryTools.saveMemory(store, "user:u1", policy, "test-model");

        ObjectNode args = mapper.createObjectNode();
        args.put("subject", "diet");
        args.put("content", "allergic to peanuts");
        args.put("type", "PREFERENCE");

        String result = save.execute(args);
        assertTrue(result.contains("Saved memory"));

        List<MemoryEntry> stored = store.listByScope("user:u1");
        assertEquals(1, stored.size());
        assertEquals("allergic to peanuts", stored.get(0).content());
        assertEquals(MemoryType.PREFERENCE, stored.get(0).type());
        assertEquals(1.0, stored.get(0).importance(), 0.001, "explicit save = max importance");
    }

    @Test
    void saveMemory_channelDefaultsToPending() throws Exception {
        Tool save = MemoryTools.saveMemory(store, "channel:c1", policy, "test-model");

        ObjectNode args = mapper.createObjectNode();
        args.put("subject", "team-event");
        args.put("content", "standup at 10am");

        save.execute(args);

        assertEquals(MemoryStatus.PENDING_REVIEW, store.listByScope("channel:c1").get(0).status());
    }

    @Test
    void saveMemory_supersedesOldSameSubject() throws Exception {
        // First save
        Tool save = MemoryTools.saveMemory(store, "user:u1", policy, "test-model");
        ObjectNode args1 = mapper.createObjectNode();
        args1.put("subject", "diet");
        args1.put("content", "allergic to peanuts");
        save.execute(args1);

        // Second save with different content -> supersede
        ObjectNode args2 = mapper.createObjectNode();
        args2.put("subject", "diet");
        args2.put("content", "not allergic actually");
        save.execute(args2);

        List<MemoryEntry> all = store.listByScope("user:u1");
        assertEquals(2, all.size(), "old + new kept");
        long active = all.stream().filter(e -> e.status() == MemoryStatus.ACTIVE).count();
        long superseded = all.stream().filter(e -> e.status() == MemoryStatus.SUPERSEDED).count();
        assertEquals(1, active);
        assertEquals(1, superseded);
    }

    @Test
    void saveMemory_defaultTypeIsFact() throws Exception {
        Tool save = MemoryTools.saveMemory(store, "user:u1", policy, "test-model");

        ObjectNode args = mapper.createObjectNode();
        args.put("subject", "x");
        args.put("content", "some fact");
        // no type specified

        save.execute(args);
        assertEquals(MemoryType.FACT, store.listByScope("user:u1").get(0).type());
    }

    @Test
    void searchMemory_returnsMatches() throws Exception {
        // Pre-populate
        store.write(new MemoryEntry(null, "user:u1", MemoryType.PREFERENCE, "diet", "allergic to peanuts", 0.9,
                MemoryProvenance.userSaid("u1", "r1", Instant.now()), MemoryStatus.ACTIVE, Instant.now(), null));
        store.write(new MemoryEntry(null, "user:u1", MemoryType.FACT, "tz", "timezone UTC+8", 0.6,
                MemoryProvenance.userSaid("u1", "r1", Instant.now()), MemoryStatus.ACTIVE, Instant.now(), null));

        Tool search = MemoryTools.searchMemory(retriever, List.of("user:u1"));

        ObjectNode args = mapper.createObjectNode();
        args.put("keyword", "peanut");

        String result = search.execute(args);
        assertTrue(result.contains("allergic to peanuts"));
        assertTrue(result.contains("Found 1"));
    }

    @Test
    void searchMemory_noMatches() throws Exception {
        Tool search = MemoryTools.searchMemory(retriever, List.of("user:u1"));

        ObjectNode args = mapper.createObjectNode();
        args.put("keyword", "nonexistent");

        String result = search.execute(args);
        assertTrue(result.contains("No memories found"));
    }

    @Test
    void searchMemory_respectsScopeIsolation() throws Exception {
        store.write(new MemoryEntry(null, "user:u1", MemoryType.FACT, "x", "user1 secret", 0.9,
                MemoryProvenance.userSaid("u1", "r1", Instant.now()), MemoryStatus.ACTIVE, Instant.now(), null));
        store.write(new MemoryEntry(null, "user:u2", MemoryType.FACT, "x", "user2 secret", 0.9,
                MemoryProvenance.userSaid("u2", "r1", Instant.now()), MemoryStatus.ACTIVE, Instant.now(), null));

        Tool search = MemoryTools.searchMemory(retriever, List.of("user:u1"));

        ObjectNode args = mapper.createObjectNode();
        args.put("keyword", "secret");

        String result = search.execute(args);
        assertTrue(result.contains("user1 secret"));
        assertFalse(result.contains("user2 secret"));
    }
}
