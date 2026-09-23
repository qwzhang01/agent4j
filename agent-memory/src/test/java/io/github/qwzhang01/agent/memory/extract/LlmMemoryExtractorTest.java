package io.github.qwzhang01.agent.memory.extract;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.client.ModelException;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryExtractor;
import io.github.qwzhang01.agent.memory.MemoryLifecycle;
import io.github.qwzhang01.agent.memory.MemoryPolicy;
import io.github.qwzhang01.agent.memory.MemoryProvenance;
import io.github.qwzhang01.agent.memory.MemoryQuery;
import io.github.qwzhang01.agent.memory.MemoryType;
import io.github.qwzhang01.agent.memory.store.InMemoryMemoryStore;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmMemoryExtractorTest {

    private static final MemoryProvenance PROV =
            MemoryProvenance.modelDerived("mock", "run-1", Instant.parse("2026-08-25T10:00:00Z"));

    @Test
    void extractsArbitrarySubjectAndStoresIt() {
        MockModelClient model = MockModelClient.scripted().respondText("""
                {"memories":[{"type":"EVENT","subject":"xyz-widget-9","content":"user mentioned widget 9","importance":0.8}]}
                """);
        MemoryExtractor extractor = new LlmMemoryExtractor(model);
        InMemoryMemoryStore store = new InMemoryMemoryStore();

        int stored = extractor.extractAndStore(
                List.of(ChatMessage.user("random chatter about widget 9")),
                "user:u1", PROV, new MemoryPolicy(0.5), store);

        assertEquals(1, stored);
        List<MemoryEntry> hits = store.query(MemoryQuery.builder()
                .scopes(List.of("user:u1"))
                .subject("xyz-widget-9")
                .build());
        assertEquals(1, hits.size());
        assertEquals(MemoryType.EVENT, hits.get(0).type());
        assertEquals("user mentioned widget 9", hits.get(0).content());
        assertEquals(0.8, hits.get(0).importance());
    }

    @Test
    void hostInstructionsAreSentToTheModel() {
        RecordingClient model = new RecordingClient(
                ModelResponse.text("{\"memories\":[]}"));
        LlmMemoryExtractor extractor = new LlmMemoryExtractor(model, "Look for color preferences only.");

        extractor.extract(List.of(ChatMessage.user("I like teal")), "user:u1", PROV);

        String system = model.lastRequest.messages().get(0).content();
        assertTrue(system.contains("Look for color preferences only."));
        assertTrue(model.lastRequest.messages().get(1).content().contains("I like teal"));
    }

    @Test
    void blankInstructionsFallBackToDefault() {
        LlmMemoryExtractor extractor = new LlmMemoryExtractor(
                MockModelClient.scripted().respondText("{\"memories\":[]}"), "  ");
        assertEquals(LlmMemoryExtractor.DEFAULT_INSTRUCTIONS, extractor.instructions());
    }

    @Test
    void unknownTypeBecomesFact_subjectUntouched() {
        List<MemoryEntry> entries = LlmMemoryExtractor.parseMemories(
                """
                        {"memories":[{"type":"NOT_A_TYPE","subject":"fav-color","content":"teal"}]}
                        """,
                "user:u1", PROV);
        assertEquals(1, entries.size());
        assertEquals(MemoryType.FACT, entries.get(0).type());
        assertEquals("fav-color", entries.get(0).subject());
    }

    @Test
    void markdownFenceAndRootArrayAreAccepted() {
        List<MemoryEntry> entries = LlmMemoryExtractor.parseMemories(
                """
                        ```json
                        [{"type":"PREFERENCE","subject":"k","content":"v"}]
                        ```
                        """,
                "user:u1", PROV);
        assertEquals(1, entries.size());
        assertEquals("k", entries.get(0).subject());
    }

    @Test
    void invalidJsonOrModelFailureYieldsNothing() {
        MemoryExtractor badJson = new LlmMemoryExtractor(
                MockModelClient.scripted().respondText("not json"));
        assertTrue(badJson.extract(List.of(ChatMessage.user("hi")), "user:u1", PROV).isEmpty());

        MemoryExtractor boom = new LlmMemoryExtractor(new ModelClient() {
            @Override
            public ModelResponse chat(ModelRequest request) {
                throw new ModelException(ModelException.ErrorCode.MODEL_ERROR, "down");
            }

            @Override
            public Stream<StreamEvent> stream(ModelRequest request) {
                return Stream.empty();
            }
        });
        assertTrue(boom.extract(List.of(ChatMessage.user("hi")), "user:u1", PROV).isEmpty());
    }

    @Test
    void optionalDueAtIsParsedAsInstant() {
        List<MemoryEntry> entries = LlmMemoryExtractor.parseMemories(
                """
                        {"memories":[{"type":"EVENT","subject":"later-ask","content":"ask how it went","dueAt":"2026-08-26T11:00:00Z"}]}
                        """,
                "user:u1", PROV);
        assertEquals(1, entries.size());
        assertEquals(Instant.parse("2026-08-26T11:00:00Z"), entries.get(0).dueAt());
        assertEquals("later-ask", entries.get(0).subject());
    }

    @Test
    void offsetDueAtIsAccepted() {
        List<MemoryEntry> entries = LlmMemoryExtractor.parseMemories(
                "{\"memories\":[{\"type\":\"EVENT\",\"subject\":\"k\",\"content\":\"v\",\"dueAt\":\"2026-08-26T19:00:00+08:00\"}]}",
                "user:u1", PROV);
        assertEquals(Instant.parse("2026-08-26T11:00:00Z"), entries.get(0).dueAt());
    }

    @Test
    void invalidDueAtDoesNotDropTheEntry() {
        List<MemoryEntry> entries = LlmMemoryExtractor.parseMemories(
                "{\"memories\":[{\"type\":\"FACT\",\"subject\":\"k\",\"content\":\"keep me\",\"dueAt\":\"not-a-time\"}]}",
                "user:u1", PROV);
        assertEquals(1, entries.size());
        assertEquals("keep me", entries.get(0).content());
        assertNull(entries.get(0).dueAt());
    }

    @Test
    void lifecycleEvolveIsParsed() {
        List<MemoryEntry> entries = LlmMemoryExtractor.parseMemories(
                """
                        {"memories":[{"type":"FACT","subject":"home-city","content":"moved to Shanghai","importance":0.8,"lifecycle":"EVOLVE"}]}
                        """,
                "user:u1", PROV);
        assertEquals(1, entries.size());
        assertEquals(MemoryLifecycle.EVOLVE, entries.get(0).lifecycle());
    }

    @Test
    void lifecycleConflictIsParsed() {
        List<MemoryEntry> entries = LlmMemoryExtractor.parseMemories(
                "{\"memories\":[{\"type\":\"FACT\",\"subject\":\"k\",\"content\":\"v\",\"lifecycle\":\"CONFLICT\"}]}",
                "user:u1", PROV);
        assertEquals(MemoryLifecycle.CONFLICT, entries.get(0).lifecycle());
    }

    @Test
    void invalidLifecycleBecomesNull_unknownValuesDoNotDropTheEntry() {
        List<MemoryEntry> entries = LlmMemoryExtractor.parseMemories(
                "{\"memories\":[{\"type\":\"FACT\",\"subject\":\"k\",\"content\":\"keep me\",\"lifecycle\":\"MAYBE\"}]}",
                "user:u1", PROV);
        assertEquals(1, entries.size());
        assertNull(entries.get(0).lifecycle(), "unknown lifecycle = not judged");
    }

    @Test
    void missingLifecycleStaysNull() {
        List<MemoryEntry> entries = LlmMemoryExtractor.parseMemories(
                "{\"memories\":[{\"type\":\"FACT\",\"subject\":\"k\",\"content\":\"v\"}]}",
                "user:u1", PROV);
        assertEquals(1, entries.size());
        assertNull(entries.get(0).lifecycle());
    }

    @Test
    void blankContentIsSkipped() {
        List<MemoryEntry> entries = LlmMemoryExtractor.parseMemories(
                "{\"memories\":[{\"type\":\"FACT\",\"subject\":\"x\",\"content\":\"  \"}]}",
                "user:u1", PROV);
        assertTrue(entries.isEmpty());
    }

    /** sampleRate=100 → extracts every session; result is stored asynchronously. */
    @Test
    void extractAsync_sampleRate100_alwaysExtracts() throws ExecutionException, InterruptedException {
        MockModelClient model = MockModelClient.scripted().respondText(
                "{\"memories\":[{\"type\":\"FACT\",\"subject\":\"color\",\"content\":\"likes blue\",\"importance\":0.8}]}");
        LlmMemoryExtractor extractor = new LlmMemoryExtractor(model, null, 100, 0L);
        InMemoryMemoryStore store = new InMemoryMemoryStore();

        int stored = extractor.extractAsync(
                List.of(ChatMessage.user("I like blue")),
                "user:u1", PROV, new MemoryPolicy(0.5), store, "session-1").get();

        assertEquals(1, stored);
        assertFalse(store.query(
                io.github.qwzhang01.agent.memory.MemoryQuery.builder()
                        .scopes(List.of("user:u1")).build()).isEmpty());
    }

    /**
     * sampleRate=0 → extraction is skipped; model must not be called at all.
     * The future completes immediately with {@code 0}.
     */
    @Test
    void extractAsync_sampleRate0_neverCallsModel() throws ExecutionException, InterruptedException {
        ModelClient failIfCalled = new ModelClient() {
            @Override
            public ModelResponse chat(ModelRequest r) {
                throw new AssertionError("model must not be called when sampleRate=0");
            }

            @Override
            public Stream<StreamEvent> stream(ModelRequest r) {
                return Stream.empty();
            }
        };
        LlmMemoryExtractor extractor = new LlmMemoryExtractor(failIfCalled, null, 0, 0L);
        InMemoryMemoryStore store = new InMemoryMemoryStore();

        int result = extractor.extractAsync(
                List.of(ChatMessage.user("hello")),
                "user:u1", PROV, new MemoryPolicy(0.5), store, "any-session").get();

        assertEquals(0, result);
        assertTrue(store.query(
                io.github.qwzhang01.agent.memory.MemoryQuery.builder()
                        .scopes(List.of("user:u1")).build()).isEmpty());
    }

    /**
     * When the store throws during write, the future must complete normally
     * (not exceptionally) and return {@code 0}.
     */
    @Test
    void extractAsync_onRuntimeException_completesNormally() throws ExecutionException, InterruptedException {
        MockModelClient model = MockModelClient.scripted().respondText(
                "{\"memories\":[{\"type\":\"FACT\",\"subject\":\"x\",\"content\":\"something\",\"importance\":0.8}]}");
        LlmMemoryExtractor extractor = new LlmMemoryExtractor(model, null, 100, 0L,
                Runnable::run);  // inline executor for deterministic test

        InMemoryMemoryStore throwingStore = new InMemoryMemoryStore() {
            @Override
            public MemoryEntry write(MemoryEntry entry) {
                throw new RuntimeException("store unavailable");
            }
        };

        CompletableFuture<Integer> future = extractor.extractAsync(
                List.of(ChatMessage.user("hi")),
                "user:u1", PROV, new MemoryPolicy(0.5), throwingStore, "session-fail");

        assertEquals(0, future.get(), "future must complete normally with 0 on failure");
        assertFalse(future.isCompletedExceptionally(),
                "future must not complete exceptionally");
    }

    private static final class RecordingClient implements ModelClient {
        private final ModelResponse response;
        ModelRequest lastRequest;

        RecordingClient(ModelResponse response) {
            this.response = response;
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            lastRequest = request;
            return response;
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            return Stream.empty();
        }
    }
}
