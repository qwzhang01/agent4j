package io.github.qwzhang01.agent.observability.metrics;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.run.RunContext;
import io.github.qwzhang01.agent.observability.cost.BudgetBook;
import io.github.qwzhang01.agent.observability.cost.BudgetExhaustedException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 7.2 acceptance for {@link BudgetedModelClient}: the model boundary
 * enforces budgets derived from the RunContext the loop already propagates -
 * no business-side requireBudget call. DENIED must throw BEFORE the delegate
 * is reached; the honest ledger records actual usage after the call.
 */
class BudgetedModelClientTest {

    // ============ chat: the five-dimension gate ============

    @Test
    void chatDeniesBeforeCallingTheModelWhenProjectionExceedsTenantBudget() {
        BudgetBook book = BudgetBook.builder()
                .budget(io.github.qwzhang01.agent.observability.cost.BudgetDimension.TENANT,
                        "acme", 100)
                .build();
        book.recordUsage(io.github.qwzhang01.agent.observability.cost.BudgetDimension.TENANT,
                "acme", 90);  // 90 used, 100 limit: any call >10 tokens is DENIED

        CountingModel delegate = new CountingModel();
        BudgetedModelClient client = BudgetedModelClient.wrap(delegate, book);

        // 40 chars of prompt = 10 estimated tokens + 50 maxTokens headroom = 60 > 10 remaining
        ModelRequest request = request(40, 50);
        BudgetExhaustedException ex = assertThrows(BudgetExhaustedException.class,
                () -> client.chat(request, ctx("acme", "alice", "run-1")));

        assertEquals(0, delegate.chatCalls.get(), "denial must happen BEFORE the delegate");
        assertTrue(ex.getMessage().contains("TENANT"), ex.getMessage());
        assertTrue(ex.getMessage().contains("acme"), ex.getMessage());
        assertEquals(100, ex.limit());
    }

    @Test
    void chatRecordsActualUsageAcrossAllConfiguredDimensions() {
        BudgetBook book = BudgetBook.builder()
                .budget(io.github.qwzhang01.agent.observability.cost.BudgetDimension.TENANT,
                        "acme", 10_000)
                .budget(io.github.qwzhang01.agent.observability.cost.BudgetDimension.USER,
                        "alice", 10_000)
                .build();
        CountingModel delegate = new CountingModel();
        BudgetedModelClient client = BudgetedModelClient.wrap(delegate, book);

        client.chat(request(40, null), ctx("acme", "alice", "run-1"));

        assertEquals(1, delegate.chatCalls.get());
        // the delegate reports 77 actual tokens: recorded on every configured axis
        assertEquals(77, book.usedOf(
                io.github.qwzhang01.agent.observability.cost.BudgetDimension.TENANT, "acme"));
        assertEquals(77, book.usedOf(
                io.github.qwzhang01.agent.observability.cost.BudgetDimension.USER, "alice"));
        assertEquals(77, book.usedOf(
                io.github.qwzhang01.agent.observability.cost.BudgetDimension.RUN, "run-1"));
    }

    @Test
    void unconfiguredDimensionsAreUnlimitedAndStillCounted() {
        BudgetBook book = BudgetBook.builder().build();  // no limits at all
        CountingModel delegate = new CountingModel();
        BudgetedModelClient client = BudgetedModelClient.wrap(delegate, book);

        ModelResponse response = client.chat(request(400, null), ctx("acme", "alice", "run-1"));

        assertEquals(77, response.usage().totalTokens());
        assertEquals(77, book.usedOf(
                io.github.qwzhang01.agent.observability.cost.BudgetDimension.RUN, "run-1"));
        assertEquals(-1, book.limitOf(
                io.github.qwzhang01.agent.observability.cost.BudgetDimension.RUN, "run-1"));
    }

    @Test
    void warnNeverBlocksTheCall() {
        BudgetBook book = BudgetBook.builder()
                .budget(io.github.qwzhang01.agent.observability.cost.BudgetDimension.USER,
                        "alice", 100)
                .warnAtPercent(80)
                .build();
        book.recordUsage(io.github.qwzhang01.agent.observability.cost.BudgetDimension.USER,
                "alice", 85);  // 85% used: WARN territory, but 15 tokens still fit

        CountingModel delegate = new CountingModel();
        BudgetedModelClient client = BudgetedModelClient.wrap(delegate, book);

        ModelResponse response = client.chat(request(20, null), ctx("acme", "alice", "run-1"));
        assertEquals(1, delegate.chatCalls.get(), "WARN must not block (D3 separation)");
        assertEquals(77, response.usage().totalTokens());
    }

    @Test
    void legacyPathWithoutContextSkipsAllGates() {
        BudgetBook book = BudgetBook.builder()
                .budget(io.github.qwzhang01.agent.observability.cost.BudgetDimension.TENANT,
                        "acme", 1)
                .build();
        book.recordUsage(io.github.qwzhang01.agent.observability.cost.BudgetDimension.TENANT,
                "acme", 1);  // tenant budget fully drained

        CountingModel delegate = new CountingModel();
        BudgetedModelClient client = BudgetedModelClient.wrap(delegate, book);

        // legacy single-arg call: no ctx = no identity = no gates (would have denied)
        ModelResponse response = client.chat(request(400, null));
        assertEquals(1, delegate.chatCalls.get());
        assertEquals(77, response.usage().totalTokens());
        // and no accounting either
        assertEquals(0, book.usedOf(
                io.github.qwzhang01.agent.observability.cost.BudgetDimension.RUN, "run-1"));
    }

    // ============ stream: terminal-event accounting ============

    @Test
    void streamDoneRecordsActualUsageExactlyOnce() {
        BudgetBook book = BudgetBook.builder()
                .budget(io.github.qwzhang01.agent.observability.cost.BudgetDimension.RUN,
                        "run-9", 10_000)
                .build();
        BudgetedModelClient client = BudgetedModelClient.wrap(new CountingModel(), book);

        List<StreamEvent> events = client.stream(request(40, null), ctx("acme", "alice", "run-9"))
                .toList();

        assertEquals(1, events.size());
        assertTrue(events.get(0) instanceof StreamEvent.Done);
        assertEquals(77, book.usedOf(
                io.github.qwzhang01.agent.observability.cost.BudgetDimension.RUN, "run-9"),
                "Done terminal must record the actual usage");
    }

    @Test
    void streamErrorRecordsTheEstimateAsHonestFloor() {
        BudgetBook book = BudgetBook.builder().build();
        BudgetedModelClient client = BudgetedModelClient.wrap(new FailingStreamModel(), book);

        // 40 chars prompt = 10 estimate; no maxTokens headroom
        List<StreamEvent> events = client.stream(request(40, null), ctx("acme", "alice", "run-e"))
                .toList();

        assertTrue(events.get(0) instanceof StreamEvent.Error);
        assertEquals(10, book.usedOf(
                io.github.qwzhang01.agent.observability.cost.BudgetDimension.RUN, "run-e"),
                "errored stream still burned tokens server-side: estimate is the honest floor");
    }

    @Test
    void streamDenialThrowsBeforeAnyEventIsEmitted() {
        BudgetBook book = BudgetBook.builder()
                .budget(io.github.qwzhang01.agent.observability.cost.BudgetDimension.TENANT,
                        "acme", 100)
                .build();
        book.recordUsage(io.github.qwzhang01.agent.observability.cost.BudgetDimension.TENANT,
                "acme", 95);
        BudgetedModelClient client = BudgetedModelClient.wrap(new CountingModel(), book);

        assertThrows(BudgetExhaustedException.class,
                () -> client.stream(request(400, null), ctx("acme", "alice", "run-x")));
    }

    // ============ estimate formula ============

    @Test
    void estimateIsCharsDividedByFourPlusMaxTokensHeadroom() {
        assertEquals(10, BudgetedModelClient.estimateTokens(request(40, null)));
        assertEquals(10 + 50, BudgetedModelClient.estimateTokens(request(40, 50)));
        assertEquals(0, BudgetedModelClient.estimateTokens(request(0, null)));
        assertEquals(7, BudgetedModelClient.estimateTokens(request(30, null)));  // 30/4 = 7
    }

    // ============ Helpers ============

    private static ModelRequest request(int promptChars, Integer maxTokens) {
        return new ModelRequest.Builder()
                .model("gpt-4o")
                .addMessage(ChatMessage.user("x".repeat(promptChars)))
                .maxTokens(maxTokens)
                .build();
    }

    private static RunContext ctx(String tenant, String user, String runId) {
        return RunContext.builder()
                .tenantId(tenant)
                .userId(user)
                .agentId("support")
                .runId(runId)
                .build();
    }

    /** Scripted model: reports 77 actual tokens, streams one Done. */
    private static final class CountingModel implements ModelClient {
        final AtomicInteger chatCalls = new AtomicInteger();

        @Override
        public ModelResponse chat(ModelRequest request) {
            chatCalls.incrementAndGet();
            return new ModelResponse("ok", null, "stop",
                    new ModelResponse.TokenUsage(50, 27, 77, 0));
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            return Stream.of(new StreamEvent.Done(
                    new ModelResponse("ok", null, "stop",
                            new ModelResponse.TokenUsage(50, 27, 77, 0))));
        }
    }

    /** Always-failing stream: the error-terminal accounting path. */
    private static final class FailingStreamModel implements ModelClient {
        @Override
        public ModelResponse chat(ModelRequest request) {
            throw new IllegalStateException("not used in this test");
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            return Stream.of(new StreamEvent.Error("provider exploded", null));
        }
    }
}
