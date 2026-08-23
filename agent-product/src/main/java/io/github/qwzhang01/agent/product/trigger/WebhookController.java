package io.github.qwzhang01.agent.product.trigger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.product.AgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Transport-agnostic webhook intake (Stage 13 M13.5, D8).
 * <p>
 * NOT an EventBroker subscriber by design (pre-checked in the blueprint's
 * reuse table): EventBroker.fire callbacks are bound to RunManager.resume,
 * but a webhook starts a NEW task. This controller goes straight to the
 * agent entry point - the Stage 12 D3 lesson applied prospectively.
 * <p>
 * The three-piece safety contract (D8):
 * <ol>
 *   <li><b>Verify:</b> HMAC-SHA256 over the raw body with the route's secret;
 *       failure rejects BEFORE any agent runs</li>
 *   <li><b>Idempotent:</b> payload must carry {@code eventId}; replays are
 *       answered DUPLICATE without re-running (in-memory set in v1)</li>
 *   <li><b>202 semantics:</b> handle() returns as soon as the work is queued;
 *       the run executes on an Executor - a slow agent must not turn the
 *       sender's timeout into a double delivery</li>
 * </ol>
 */
public final class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, WebhookRoute> routes;
    private final AgentRegistry agents;
    private final Executor executor;
    private final Set<String> seenEventIds = ConcurrentHashMap.newKeySet();

    private WebhookController(Map<String, WebhookRoute> routes, AgentRegistry agents,
                              Executor executor) {
        this.routes = Map.copyOf(routes);
        this.agents = agents;
        this.executor = executor;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Handle one webhook delivery.
     *
     * @param source  the external source identifier
     * @param headers request headers (signature read from X-Signature, hex)
     * @param rawBody the raw request body (signature input AND payload source)
     * @return outcome record (never throws for delivery-level problems)
     */
    public WebhookResult handle(String source, Map<String, String> headers, String rawBody) {
        WebhookRoute route = routes.get(source);
        if (route == null) {
            return new WebhookResult(WebhookResult.Status.UNKNOWN_SOURCE,
                    "no route for source '" + source + "', known: " + routes.keySet());
        }

        String signature = headers == null ? null : headers.get("X-Signature");
        if (signature == null || !MessageDigest.isEqual(
                signature.toLowerCase().getBytes(StandardCharsets.UTF_8),
                hmacSha256(rawBody, route.secret()).toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            log.warn("Webhook signature verification FAILED for source '{}' - rejected", source);
            return new WebhookResult(WebhookResult.Status.UNAUTHORIZED,
                    "signature verification failed");
        }

        JsonNode payload;
        try {
            payload = JSON.readTree(rawBody == null ? "" : rawBody);
        } catch (Exception e) {
            return new WebhookResult(WebhookResult.Status.BAD_PAYLOAD,
                    "body is not valid JSON: " + e.getMessage());
        }
        JsonNode eventIdNode = payload.get("eventId");
        if (eventIdNode == null || eventIdNode.isNull() || eventIdNode.asText().isBlank()) {
            return new WebhookResult(WebhookResult.Status.NO_EVENT_ID,
                    "payload must carry a top-level 'eventId' for idempotency");
        }
        String eventId = source + ":" + eventIdNode.asText();
        if (!seenEventIds.add(eventId)) {
            return new WebhookResult(WebhookResult.Status.DUPLICATE,
                    "event '" + eventId + "' already handled - replay ignored");
        }

        Agent agent = agents.get(route.agentName()).orElse(null);
        if (agent == null) {
            return new WebhookResult(WebhookResult.Status.AGENT_NOT_FOUND,
                    "route targets agent '" + route.agentName() + "' which is not running");
        }

        String input = PayloadRenderer.render(route.payloadTemplate(), payload);
        executor.execute(() -> {
            try {
                String output = agent.run(input);
                log.info("Webhook '{}' -> agent '{}' completed: {}",
                        source, route.agentName(), abbreviate(output));
            } catch (Exception e) {
                log.error("Webhook '{}' -> agent '{}' failed", source, route.agentName(), e);
            }
        });
        return WebhookResult.accepted("event '" + eventId + "' dispatched to '"
                + route.agentName() + "' asynchronously");
    }

    // --------------------------------------------
    // Internals
    // --------------------------------------------

    private static String hmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    // ============ Builder ============

    /**
     * Assembles a controller: routes in, agent registry and executor in.
     */
    public static final class Builder {

        private final Map<String, WebhookRoute> routes = new java.util.LinkedHashMap<>();
        private AgentRegistry agents;
        private Executor executor;

        /**
         * Register a route (one per source; duplicate source fails fast).
         */
        public Builder route(WebhookRoute route) {
            Objects.requireNonNull(route, "route must not be null");
            if (routes.containsKey(route.source())) {
                throw new IllegalArgumentException(
                        "Route for source '" + route.source() + "' is already registered");
            }
            routes.put(route.source(), route);
            return this;
        }

        /**
         * The agents webhook payloads are dispatched to.
         */
        public Builder agents(AgentRegistry agents) {
            this.agents = Objects.requireNonNull(agents, "agents must not be null");
            return this;
        }

        /**
         * Where the async runs execute; default is a cached daemon pool.
         */
        public Builder executor(Executor executor) {
            this.executor = Objects.requireNonNull(executor, "executor must not be null");
            return this;
        }

        public WebhookController build() {
            if (agents == null) {
                throw new IllegalArgumentException("agents(AgentRegistry) is required");
            }
            Executor actualExecutor = executor != null ? executor
                    : Executors.newCachedThreadPool(r -> {
                        Thread t = new Thread(r, "webhook-run");
                        t.setDaemon(true);
                        return t;
                    });
            return new WebhookController(routes, agents, actualExecutor);
        }
    }
}
