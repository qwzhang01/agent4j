package io.github.qwzhang01.agent.product.trigger;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.product.AgentRegistry;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M13.5 webhook tests (D8): the three-piece contract - verify (HMAC),
 * idempotent (eventId), 202 semantics (async dispatch).
 */
class WebhookControllerTest {

    private static final String SECRET = "webhook-secret";

    /** Executes tasks on the CALLING thread - tests observe runs synchronously. */
    private static final Executor DIRECT = Runnable::run;

    private final AgentRegistry agents = new AgentRegistry();

    private WebhookController controller() {
        return WebhookController.builder()
                .route(new WebhookRoute("alerting", "support-bot",
                        "告警：{$.alert.title} 严重度 {$.alert.severity}", SECRET))
                .agents(agents)
                .executor(DIRECT)
                .build();
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private record CapturingAgent(AtomicReference<String> lastInput,
                                  CountDownLatch latch) implements Agent {
        @Override
        public String run(String userInput) {
            lastInput.set(userInput);
            latch.countDown();
            return "handled";
        }

        @Override
        public String run(String userInput, io.github.qwzhang01.agent.core.agent.AgentState state) {
            return run(userInput);
        }

        @Override
        public AgentConfig getConfig() {
            return new AgentConfig("support-bot", null, null, null, 1, null);
        }
    }

    // ============ Verify (HMAC) ============

    @Test
    void validSignatureIsAccepted() throws Exception {
        AtomicReference<String> lastInput = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        agents.register("support-bot", new CapturingAgent(lastInput, latch));

        String body = "{\"eventId\":\"e-1\",\"alert\":{\"title\":\"CPU 高\",\"severity\":\"P1\"}}";
        WebhookResult result = controller().handle("alerting",
                Map.of("X-Signature", sign(body)), body);

        assertEquals(WebhookResult.Status.ACCEPTED, result.status());
        assertTrue(latch.await(1, TimeUnit.SECONDS));
        // The payload template rendered against the JSON.
        assertEquals("告警：CPU 高 严重度 P1", lastInput.get());
    }

    @Test
    void badSignatureIsRejectedBeforeAnyAgentRuns() throws Exception {
        AtomicReference<String> lastInput = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        agents.register("support-bot", new CapturingAgent(lastInput, latch));

        String body = "{\"eventId\":\"e-2\"}";
        WebhookResult result = controller().handle("alerting",
                Map.of("X-Signature", "deadbeef"), body);

        assertEquals(WebhookResult.Status.UNAUTHORIZED, result.status());
        assertEquals(1, latch.getCount(), "a rejected webhook must never reach the agent");
    }

    @Test
    void missingSignatureIsRejected() {
        agents.register("support-bot", new CapturingAgent(new AtomicReference<>(),
                new CountDownLatch(1)));
        WebhookResult result = controller().handle("alerting", Map.of(),
                "{\"eventId\":\"e-3\"}");
        assertEquals(WebhookResult.Status.UNAUTHORIZED, result.status());
    }

    // ============ Idempotent (eventId) ============

    @Test
    void sameEventIdIsAnsweredDuplicateWithoutRerunning() {
        AtomicReference<String> lastInput = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        agents.register("support-bot", new CapturingAgent(lastInput, latch));

        WebhookController controller = controller();
        String body = "{\"eventId\":\"e-4\"}";
        Map<String, String> headers = Map.of("X-Signature", sign(body));

        assertEquals(WebhookResult.Status.ACCEPTED, controller.handle("alerting", headers, body).status());
        assertEquals(0, latch.getCount(), "first delivery runs the agent exactly once");
        // Second delivery of the same event id: replay-safe.
        assertEquals(WebhookResult.Status.DUPLICATE,
                controller.handle("alerting", headers, body).status());
        assertEquals(0, latch.getCount(), "replay must not re-run the agent (latch stays at 0)");
    }

    @Test
    void payloadWithoutEventIdIsRejected() {
        agents.register("support-bot", new CapturingAgent(new AtomicReference<>(),
                new CountDownLatch(1)));
        String body = "{\"alert\":\"x\"}";
        WebhookResult result = controller().handle("alerting",
                Map.of("X-Signature", sign(body)), body);
        assertEquals(WebhookResult.Status.NO_EVENT_ID, result.status());
    }

    // ============ Routing ============

    @Test
    void unknownSourceIsAnsweredNotFound() {
        WebhookResult result = controller().handle("ghost", Map.of(), "{}");
        assertEquals(WebhookResult.Status.UNKNOWN_SOURCE, result.status());
        assertTrue(result.message().contains("alerting"));
    }

    @Test
    void routeToMissingAgentIsAnsweredAgentNotFound() {
        // Registry registered, agent not.
        String body = "{\"eventId\":\"e-5\"}";
        WebhookResult result = controller().handle("alerting",
                Map.of("X-Signature", sign(body)), body);
        assertEquals(WebhookResult.Status.AGENT_NOT_FOUND, result.status());
    }

    @Test
    void badJsonBodyIsRejected() {
        agents.register("support-bot", new CapturingAgent(new AtomicReference<>(),
                new CountDownLatch(1)));
        String body = "not-json";
        WebhookResult result = controller().handle("alerting",
                Map.of("X-Signature", sign(body)), body);
        assertEquals(WebhookResult.Status.BAD_PAYLOAD, result.status());
    }

    // ============ 202 semantics ============

    @Test
    void handleReturnsBeforeTheRunOnARealExecutor() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Agent slowAgent = new Agent() {
            @Override
            public String run(String userInput) {
                started.countDown();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
                finished.countDown();
                return "done";
            }

            @Override
            public String run(String userInput, io.github.qwzhang01.agent.core.agent.AgentState state) {
                return run(userInput);
            }

            @Override
            public AgentConfig getConfig() {
                return new AgentConfig("support-bot", null, null, null, 1, null);
            }
        };
        agents.register("support-bot", slowAgent);

        WebhookController controller = WebhookController.builder()
                .route(new WebhookRoute("alerting", "support-bot", null, SECRET))
                .agents(agents)
                .executor(java.util.concurrent.Executors.newSingleThreadExecutor())
                .build();

        String body = "{\"eventId\":\"e-6\"}";
        long start = System.currentTimeMillis();
        WebhookResult result = controller.handle("alerting",
                Map.of("X-Signature", sign(body)), body);
        long handleLatency = System.currentTimeMillis() - start;

        assertEquals(WebhookResult.Status.ACCEPTED, result.status());
        assertTrue(handleLatency < 150,
                "handle() must return fast (202 semantics), took " + handleLatency + "ms");
        assertTrue(finished.await(2, TimeUnit.SECONDS), "the run completes asynchronously");
    }

    // ============ Route discipline ============

    @Test
    void duplicateRouteSourceIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                WebhookController.builder()
                        .route(new WebhookRoute("s", "a", null, "k"))
                        .route(new WebhookRoute("s", "b", null, "k2")));
    }

    @Test
    void blankSecretIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new WebhookRoute("s", "a", null, " "));
    }
}
