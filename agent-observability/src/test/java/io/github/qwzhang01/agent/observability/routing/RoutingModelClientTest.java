package io.github.qwzhang01.agent.observability.routing;

import io.github.qwzhang01.agent.core.client.FallbackModelClient;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.client.ModelException;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.observability.cost.BudgetBook;
import io.github.qwzhang01.agent.observability.cost.BudgetDimension;
import io.github.qwzhang01.agent.observability.cost.BudgetExhaustedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class RoutingModelClientTest {

    // ============ Test helpers ============

    /** Captures the request and answers with canned objects - proves zero-touch forwarding. */
    static final class RecordingClient implements ModelClient {
        ModelRequest lastRequest;
        int calls;
        ModelResponse nextResponse = ModelResponse.text("recording");
        Stream<StreamEvent> nextStream = Stream.empty();

        @Override
        public ModelResponse chat(ModelRequest request) {
            this.lastRequest = request;
            this.calls++;
            return nextResponse;
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            this.lastRequest = request;
            this.calls++;
            return nextStream;
        }
    }

    /** Fixed-decision router with a call counter. */
    static final class StubRouter implements ModelRouter {
        int routeCalls;
        private final RouteDecision decision;

        StubRouter(String modelId) {
            this.decision = new RouteDecision(modelId, "stub decision");
        }

        @Override
        public RouteDecision route(ModelRequest request, BudgetSnapshot budget) {
            routeCalls++;
            return decision;
        }
    }

    private static ModelRequest request() {
        return ModelRequest.builder()
                .model("requested-model")
                .addMessage(ChatMessage.user("hi"))
                .build();
    }

    // ============ forwarding: zero-touch contract ============

    @Test
    @DisplayName("chat: forwards to the selected candidate - SAME request instance in, SAME response instance out")
    void chatZeroTouchForwarding() {
        RecordingClient premium = new RecordingClient();
        RecordingClient cheap = new RecordingClient();
        RoutingModelClient client = new RoutingModelClient(
                Map.of("premium", premium, "cheap", cheap), new StubRouter("premium"));
        ModelRequest req = request();

        ModelResponse out = client.chat(req);

        assertSame(req, premium.lastRequest, "selected client receives the ORIGINAL request instance");
        assertEquals(0, cheap.calls);
        assertSame(premium.nextResponse, out, "response instance passes through as-is");
    }

    @Test
    @DisplayName("stream: forwards to the selected candidate - SAME stream instance, request untouched")
    void streamZeroTouchForwarding() {
        RecordingClient premium = new RecordingClient();
        Stream<StreamEvent> canned = Stream.empty();
        premium.nextStream = canned;
        StubRouter router = new StubRouter("premium");
        RoutingModelClient client = new RoutingModelClient(
                Map.of("premium", premium), router);

        Stream<StreamEvent> out = client.stream(request());

        assertSame(canned, out, "the selected client's stream is returned as-is");
        assertEquals(1, router.routeCalls, "router consulted once per call");
        assertNotNull(premium.lastRequest);
    }

    @Test
    @DisplayName("router is consulted on EVERY call - fresh snapshot as the ledger drains")
    void routesPerCall() {
        RecordingClient premium = new RecordingClient();
        StubRouter router = new StubRouter("premium");
        RoutingModelClient client = new RoutingModelClient(Map.of("premium", premium), router);

        client.chat(request());
        client.chat(request());

        assertEquals(2, router.routeCalls);
        assertEquals(2, premium.calls);
    }

    // ============ the acceptance: two models, automatic switch ============

    @Test
    @DisplayName("budget-driven switch: healthy -> premium answers, ledger drains below threshold -> cheap answers")
    void budgetDrivenSwitch() {
        BudgetBook book = BudgetBook.builder()
                .budget(BudgetDimension.USER, "alice", 10_000)
                .build();
        MockModelClient premium = MockModelClient.scripted().respondText("from premium");
        MockModelClient cheap = MockModelClient.scripted().respondText("from cheap");
        RoutingModelClient client = new RoutingModelClient(
                Map.of("premium", premium, "cheap", cheap),
                new BudgetAwareRouter("premium", "cheap", 25),
                () -> ModelRouter.BudgetSnapshot.of(book.remainingOf(BudgetDimension.USER, "alice"),
                        book.limitOf(BudgetDimension.USER, "alice")));

        // healthy: 100% remaining -> premium
        assertEquals("from premium", client.chat(request()).content());

        // drain to 15% remaining (1,500 of 10,000) -> below 25% threshold -> cheap
        book.recordUsage(BudgetDimension.USER, "alice", 8_500);
        assertEquals("from cheap", client.chat(request()).content(),
                "the SAME client instance switched tiers as the budget drained");
    }

    @Test
    @DisplayName("budget exhausted mid-run: next call fails closed with BudgetExhaustedException")
    void budgetExhaustedFailsClosed() {
        BudgetBook book = BudgetBook.builder()
                .budget(BudgetDimension.USER, "alice", 10_000)
                .build();
        book.recordUsage(BudgetDimension.USER, "alice", 10_000);
        RoutingModelClient client = new RoutingModelClient(
                Map.of("premium", MockModelClient.scripted().respondText("p"),
                        "cheap", MockModelClient.scripted().respondText("c")),
                new BudgetAwareRouter("premium", "cheap", 25),
                () -> ModelRouter.BudgetSnapshot.of(book.remainingOf(BudgetDimension.USER, "alice"),
                        book.limitOf(BudgetDimension.USER, "alice")));

        BudgetExhaustedException e = assertThrows(BudgetExhaustedException.class,
                () -> client.chat(request()));

        assertEquals(10_000, e.limit());
    }

    // ============ composition with Stage 1: Routing(Fallback(...)) ============

    @Test
    @DisplayName("Routing(Fallback(...)): cheap tier dies -> Stage 1 chain catches (zero-change reuse)")
    void fallbackCompositionCatches() {
        MockModelClient deadCheap = MockModelClient.scripted();  // empty -> ModelException on chat
        MockModelClient backup = MockModelClient.scripted().respondText("backup answers");
        FallbackModelClient cheapTier = new FallbackModelClient(deadCheap, backup);
        RoutingModelClient client = new RoutingModelClient(
                Map.of("cheap", cheapTier), new StubRouter("cheap"));

        assertEquals("backup answers", client.chat(request()).content(),
                "routing picked cheap, cheap died, the Stage 1 fallback chain recovered");
    }

    @Test
    @DisplayName("whole chain dead: 'All fallback clients exhausted' propagates untouched (honest failure)")
    void allExhaustedPropagates() {
        FallbackModelClient deadTier = new FallbackModelClient(
                MockModelClient.scripted(), MockModelClient.scripted());
        RoutingModelClient client = new RoutingModelClient(
                Map.of("cheap", deadTier), new StubRouter("cheap"));

        ModelException e = assertThrows(ModelException.class, () -> client.chat(request()));

        assertTrue(e.getMessage().contains("All fallback clients exhausted"),
                "Stage 1's exhaustion message must surface unchanged: " + e.getMessage());
    }

    // ============ failure semantics: routing is the main path ============

    @Test
    @DisplayName("router exception propagates untouched - routing is NOT a side channel, no masking")
    void routerExceptionPropagates() {
        BudgetExhaustedException boom = new BudgetExhaustedException("gone", 0, 1);
        ModelRouter exploding = (req, budget) -> {
            throw boom;
        };
        RoutingModelClient client = new RoutingModelClient(
                Map.of("premium", new RecordingClient()), exploding);

        BudgetExhaustedException thrown = assertThrows(BudgetExhaustedException.class,
                () -> client.chat(request()));

        assertSame(boom, thrown, "the router's own exception instance surfaces");
    }

    @Test
    @DisplayName("router returns an unknown model id: fail-loud ISE naming the id, reason and candidates")
    void unknownModelIdFailsLoud() {
        RoutingModelClient client = new RoutingModelClient(
                Map.of("premium", new RecordingClient()), new StubRouter("ghost"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> client.chat(request()));

        assertTrue(e.getMessage().contains("ghost"), e.getMessage());
        assertTrue(e.getMessage().contains("stub decision"), "the decision's reason is included: " + e.getMessage());
        assertTrue(e.getMessage().contains("premium"), e.getMessage());
    }

    // ============ guards / misc ============

    @Test
    @DisplayName("constructor guards: empty candidates, null router, null budget source, blank key")
    void constructorGuards() {
        RecordingClient ok = new RecordingClient();
        StubRouter router = new StubRouter("premium");

        assertThrows(IllegalArgumentException.class,
                () -> new RoutingModelClient(Map.of(), router));
        assertThrows(NullPointerException.class,
                () -> new RoutingModelClient(Map.of("premium", ok), null));
        assertThrows(NullPointerException.class,
                () -> new RoutingModelClient(Map.of("premium", ok), router, null));
        assertThrows(IllegalArgumentException.class,
                () -> new RoutingModelClient(Map.of(" ", ok), router));
    }

    @Test
    @DisplayName("candidateIds(): assembly-time visibility into the candidate keys")
    void candidateIds() {
        RoutingModelClient client = new RoutingModelClient(
                Map.of("premium", new RecordingClient(), "cheap", new RecordingClient()),
                new StubRouter("premium"));

        assertEquals(2, client.candidateIds().size());
        assertTrue(client.candidateIds().containsAll(List.of("premium", "cheap")));
    }
}
