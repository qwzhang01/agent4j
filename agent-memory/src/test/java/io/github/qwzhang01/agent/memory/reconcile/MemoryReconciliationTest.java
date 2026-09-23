package io.github.qwzhang01.agent.memory.reconcile;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.memory.MemoryDecision;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryLifecycle;
import io.github.qwzhang01.agent.memory.MemoryPolicy;
import io.github.qwzhang01.agent.memory.MemoryProvenance;
import io.github.qwzhang01.agent.memory.MemoryQuery;
import io.github.qwzhang01.agent.memory.MemoryRetriever;
import io.github.qwzhang01.agent.memory.MemoryStatus;
import io.github.qwzhang01.agent.memory.MemoryStore;
import io.github.qwzhang01.agent.memory.MemoryType;
import io.github.qwzhang01.agent.memory.extract.LlmMemoryExtractor;
import io.github.qwzhang01.agent.memory.store.InMemoryMemoryStore;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reconciliation loop tests (memory route step 2): read side feeds write side,
 * bi-temporal stamps land on supersede, and every write decision is reported.
 * <p>
 * Flagship scenario mirrors the design doc's acceptance assertion: seed
 * "home-city: lives in Shenzhen (ACTIVE)", feed "I moved to Shanghai", assert
 * Shenzhen becomes HISTORICAL with the business axis closed at the new fact's
 * business start, Shanghai is the only ACTIVE entry, default recall sees
 * Shanghai only, and history query sees Shenzhen.
 */
class MemoryReconciliationTest {

    private static final Instant MOVED_AT = Instant.parse("2026-09-03T00:00:00Z");
    private static final Instant RECORDED_AT = Instant.parse("2026-09-10T10:00:00Z");

    private final MemoryStore store = new InMemoryMemoryStore();
    private final MemoryPolicy policy = new MemoryPolicy(0.5);
    private final MemoryProvenance prov = MemoryProvenance.userSaid("u1", "run-1", RECORDED_AT);

    private MemoryEntry seed(String scope, String subject, String content, double importance) {
        return store.write(new MemoryEntry(null, scope, MemoryType.FACT, subject, content, importance,
                prov, MemoryStatus.ACTIVE, Instant.parse("2026-08-01T00:00:00Z"), null));
    }

    // Flagship: reconciled supersede with bi-temporal stamps

    @Test
    void reconciledEvolve_closesOldEntryOnBothAxes() {
        MemoryEntry shenzhen = seed("user:u1", "home-city", "lives in Shenzhen", 0.9);

        // Model sees the recalled old account and picks the existing key + EVOLVE + business time
        MockModelClient model = MockModelClient.scripted().respondText("""
                {"memories":[{"type":"FACT","subject":"home-city","content":"lives in Shanghai","importance":0.9,"lifecycle":"EVOLVE","validFrom":"2026-09-03T00:00:00Z"}]}
                """);
        LlmMemoryExtractor extractor = new LlmMemoryExtractor(model);
        MemoryReconciler reconciler = new MemoryReconciler(store, new MemoryRetriever(store));
        List<MemoryDecision> decisions = new ArrayList<>();
        RecordingListener listener = new RecordingListener(decisions);

        int stored = extractor.extractAndStore(
                List.of(ChatMessage.user("我上周搬到上海了")),
                "user:u1", prov, policy, store, reconciler, listener);

        assertEquals(1, stored);

        // Old entry: HISTORICAL, business axis closed at the new fact's business start,
        // system axis closed now. validAt must be MOVED_AT, not the recording time.
        MemoryEntry closed = store.findById(shenzhen.id()).orElseThrow();
        assertEquals(MemoryStatus.HISTORICAL, closed.status());
        assertEquals(MOVED_AT, closed.validAt(), "business axis closes at new fact's business start");
        assertNotNull(closed.invalidAt(), "system axis stamped on close");
        assertTrue(closed.invalidAt().isAfter(closed.createdAt()), "ledger closes after it recorded");

        // New entry: ACTIVE with the business start carried through
        List<MemoryEntry> active = store.query(MemoryQuery.builder()
                .scopes(List.of("user:u1")).subject("home-city").build());
        assertEquals(1, active.size());
        assertEquals("lives in Shanghai", active.get(0).content());
        assertEquals(MOVED_AT, active.get(0).validFrom());

        // Decision event: UPDATE with old/new ids and EVOLVE lifecycle
        assertEquals(1, decisions.size());
        MemoryDecision d = decisions.get(0);
        assertEquals(MemoryDecision.Operation.UPDATE, d.operation());
        assertEquals("home-city", d.subject());
        assertEquals(MemoryLifecycle.EVOLVE, d.lifecycle());
        assertEquals(shenzhen.id(), d.oldEntryId());
        assertEquals(active.get(0).id(), d.newEntryId());
    }

    // Evidence feeds the prompt (read side feeds write side)

    @Test
    void evidenceAppearsInExtractionPrompt() {
        seed("user:u1", "home-city", "lives in Shenzhen", 0.9);
        seed("user:u1", "diet", "no cilantro", 0.8);

        RecordingClient model = new RecordingClient(
                ModelResponse.text("{\"memories\":[]}"));
        LlmMemoryExtractor extractor = new LlmMemoryExtractor(model);
        MemoryReconciler reconciler = new MemoryReconciler(store, new MemoryRetriever(store));
        List<ChatMessage> msgs = List.of(ChatMessage.user("我搬到上海了"));

        extractor.extract(msgs, "user:u1", prov, reconciler.recallEvidence(msgs, "user:u1"));

        String system = model.lastRequest.messages().get(0).content();
        assertTrue(system.contains("Existing subjects:"), "evidence block present: " + system);
        assertTrue(system.contains("- home-city: lives in Shenzhen"),
                "old account visible to the extractor");
    }

    // Soft failure: recall failure never breaks the write

    @Test
    void recallFailure_degradesToPlainExtraction() {
        MemoryStore brokenStore = new InMemoryMemoryStore() {
            @Override
            public List<MemoryEntry> query(MemoryQuery query) {
                throw new RuntimeException("store down");
            }
        };
        brokenStore.write(new MemoryEntry(null, "user:u1", MemoryType.FACT, "home-city",
                "lives in Shenzhen", 0.9, prov, MemoryStatus.ACTIVE,
                Instant.parse("2026-08-01T00:00:00Z"), null));

        MockModelClient model = MockModelClient.scripted().respondText("""
                {"memories":[{"type":"FACT","subject":"home-city","content":"lives in Shanghai","importance":0.9,"lifecycle":"EVOLVE"}]}
                """);
        LlmMemoryExtractor extractor = new LlmMemoryExtractor(model);
        MemoryReconciler reconciler = new MemoryReconciler(brokenStore,
                new MemoryRetriever(brokenStore));

        int stored = extractor.extractAndStore(
                List.of(ChatMessage.user("我搬上海了")),
                "user:u1", prov, policy, brokenStore, reconciler, null);

        assertEquals(1, stored, "write proceeds without evidence (soft failure)");
    }

    // Scope isolation: evidence never crosses scopes

    @Test
    void evidenceNeverCrossesScopes() {
        seed("user:u2", "home-city", "lives in Beijing", 0.9);

        MemoryReconciler reconciler = new MemoryReconciler(store, new MemoryRetriever(store));
        List<MemoryEntry> evidence = reconciler.recallEvidence(
                List.of(ChatMessage.user("我搬上海了")), "user:u1");

        assertTrue(evidence.isEmpty(), "other user's old accounts are invisible");
    }


    @Test
    void historyQuerySeesClosedEntry() {
        MemoryEntry shenzhen = seed("user:u1", "home-city", "lives in Shenzhen", 0.9);
        MemoryRetriever retriever = new MemoryRetriever(store);

        MockModelClient model = MockModelClient.scripted().respondText("""
                {"memories":[{"type":"FACT","subject":"home-city","content":"lives in Shanghai","importance":0.9,"lifecycle":"EVOLVE"}]}
                """);
        LlmMemoryExtractor extractor = new LlmMemoryExtractor(model);
        MemoryReconciler reconciler = new MemoryReconciler(store, retriever);

        extractor.extractAndStore(List.of(ChatMessage.user("我搬到上海了")),
                "user:u1", prov, policy, store, reconciler, null);

        // Default recall: Shanghai only
        List<MemoryEntry> defaultView = retriever.recallForContext(List.of("user:u1"), 10, "home city");
        assertTrue(defaultView.stream().noneMatch(e -> e.id().equals(shenzhen.id())),
                "closed line invisible by default");

        // History query: Shenzhen back
        List<MemoryEntry> history = retriever.recallSubjectHistory(List.of("user:u1"), "home-city");
        assertTrue(history.stream().anyMatch(e -> e.id().equals(shenzhen.id())),
                "history query recovers the closed line");
    }

    private static final class RecordingListener implements MemoryDecisionListener {
        private final List<MemoryDecision> sink;

        RecordingListener(List<MemoryDecision> sink) {
            this.sink = sink;
        }

        @Override
        public void onDecision(MemoryDecision decision, MemoryStore store) {
            sink.add(decision);
        }
    }

    /** Captures the last request; mirrors LlmMemoryExtractorTest.RecordingClient. */
    private static final class RecordingClient implements ModelClient {
        private final ModelResponse scriptedResponse;
        private ModelRequest lastRequest;

        RecordingClient(ModelResponse scriptedResponse) {
            this.scriptedResponse = scriptedResponse;
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            this.lastRequest = request;
            return scriptedResponse;
        }

        @Override
        public java.util.stream.Stream<StreamEvent> stream(ModelRequest request) {
            throw new UnsupportedOperationException("not used in these tests");
        }
    }
}
