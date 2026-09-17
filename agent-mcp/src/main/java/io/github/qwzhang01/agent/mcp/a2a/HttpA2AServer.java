package io.github.qwzhang01.agent.mcp.a2a;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.run.RunContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.UnaryOperator;

/**
 * A2A server over real HTTP (JDK HttpServer, zero new dependencies).
 * <p>
 * This is the ear: it wraps ONE existing {@link Agent} instance as a
 * protocol endpoint -- the agent itself is untouched, what changes is how it
 * answers the phone. It is not a separate process or framework; in a Spring
 * Boot host you would start this next to your beans, in a plain host from
 * main().
 * <p>
 * Spec shape served:
 * <ul>
 *   <li>{@code GET /.well-known/agent.json} -- the agent card. The url is
 *       filled with the bound address unless the card already carries a real
 *       http(s) url (e.g. behind a reverse proxy).</li>
 *   <li>{@code POST /} -- JSON-RPC: {@code message/send} (run the agent,
 *       synchronous, return the task) and {@code tasks/get} (poll a stored
 *       task).</li>
 * </ul>
 * <p>
 * One agent per endpoint: in the spec the URL IS the agent's identity, so
 * exposing N agents means N servers on N ports. The in-process client
 * registers many agents by name instead -- same model, different identity
 * physics.
 * <p>
 * Inbound defense (the a2a sibling of ExternalAgentWorker's outbound D5):
 * the wire text is untrusted input to OUR agent's prompt. Wire a
 * {@code UnaryOperator<String>} inbound sanitizer; a sanitizer that THROWS
 * rejects the task outright (stored and returned as {@code rejected},
 * the agent never runs). The framework deliberately does not depend on
 * agent-security from this module (same boundary discipline as the mcp
 * client) -- plug Stage 9's sanitizer in at the assembly layer.
 * <p>
 * v2: {@code message/stream} (SSE), {@code tasks/pushNotification/set}
 * (webhook on terminal / input-required), and {@code message.taskId}
 * continues an {@code input-required} task. Text parts only, 127.0.0.1
 * binding. The advertised card always reports
 * {@link A2ACapabilities#v2()}.
 * <p>
 * Stage 6.3: tasks live in a pluggable {@link A2ATaskStore} (default
 * in-memory; plug Redis/Postgres behind the same surface to survive
 * restarts — {@code beginTask} resumes from the stored serialized
 * {@link AgentState}). Optional bearer auth gates every route except the
 * public agent card; push webhooks are HMAC-signed when a shared secret is
 * configured (see {@link A2ASecurity}).
 */
public class HttpA2AServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HttpA2AServer.class);

    public static final String WELL_KNOWN_PATH = "/.well-known/agent.json";

    private final AgentCard card;
    private final Agent agent;
    private final UnaryOperator<String> inboundSanitizer;  // nullable = raw passthrough
    private final int requestedPort;
    private final A2ATaskStore taskStore;
    private final String bearerToken;           // null = auth off (dev/tests only)
    private final String pushSharedSecret;      // null = unsigned pushes (legacy)
    private final java.security.KeyPair cardSigningKeys;  // null = unsigned card (legacy)

    private HttpServer server;
    private ExecutorService executor;
    private volatile boolean started;

    /** Ephemeral port, no inbound sanitizer. */
    public HttpA2AServer(AgentCard card, Agent agent) {
        this(card, agent, 0, null);
    }

    /** Fixed port, no inbound sanitizer. */
    public HttpA2AServer(AgentCard card, Agent agent, int port) {
        this(card, agent, port, null);
    }

    /**
     * @param port               0 = ephemeral (pick a free port)
     * @param inboundSanitizer   nullable; see class javadoc. A throwing
     *                           sanitizer rejects the task.
     */
    public HttpA2AServer(AgentCard card, Agent agent, int port,
                         UnaryOperator<String> inboundSanitizer) {
        this(card, agent, port, inboundSanitizer,
                new InMemoryA2ATaskStore(), null, null);
    }

    /**
     * Stage 6.3 full form: pluggable task store + bearer auth + signed pushes.
     *
     * @param taskStore       where tasks live (plug Redis/Postgres here)
     * @param bearerToken     required Authorization token; null disables auth
     * @param pushSharedSecret HMAC secret for push notifications; null sends
     *                        legacy unsigned pushes
     */
    public HttpA2AServer(AgentCard card, Agent agent, int port,
                         UnaryOperator<String> inboundSanitizer,
                         A2ATaskStore taskStore,
                         String bearerToken,
                         String pushSharedSecret) {
        this(card, agent, port, inboundSanitizer, taskStore,
                bearerToken, pushSharedSecret, null);
    }

    /**
     * Batch 4 full form: card identity signing on top of the Stage 6.3
     * form. When {@code cardSigningKeys} is set, every well-known card
     * response carries {@link A2ACardIdentity#CARD_SIGNATURE_HEADER} — a
     * signature over the card body bytes made with this server's private
     * key, so a caller with the matching public key in its trust store can
     * verify WHO it is talking to (name/url/capabilities/version ride the
     * signed payload). Null keeps the legacy unsigned card.
     *
     * @param cardSigningKeys key pair that signs the card (the public half
     *                        feeds the keyId fingerprint); null = unsigned
     */
    public HttpA2AServer(AgentCard card, Agent agent, int port,
                         UnaryOperator<String> inboundSanitizer,
                         A2ATaskStore taskStore,
                         String bearerToken,
                         String pushSharedSecret,
                         java.security.KeyPair cardSigningKeys) {
        this.card = Objects.requireNonNull(card, "card must not be null");
        this.agent = Objects.requireNonNull(agent, "agent must not be null");
        this.requestedPort = port;
        this.inboundSanitizer = inboundSanitizer;
        this.taskStore = taskStore != null ? taskStore : new InMemoryA2ATaskStore();
        this.bearerToken = bearerToken;
        this.pushSharedSecret = pushSharedSecret;
        this.cardSigningKeys = cardSigningKeys;
    }

    /** Public key id of the card signing key (null when the card is unsigned). */
    public String cardSigningKeyId() {
        return cardSigningKeys == null ? null
                : A2ACardIdentity.keyIdOf(cardSigningKeys.getPublic());
    }

    // ============ Lifecycle ============

    /** Start and return the bound port (useful when constructed with port 0). */
    public synchronized int start() throws IOException {
        if (started) {
            throw new IllegalStateException("server already started on port " + port());
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", requestedPort), 0);
        server.createContext("/", this::handle);
        executor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "a2a-server-" + card.name());
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.start();
        started = true;
        log.info("A2A server for agent '{}' listening on {}", card.name(), wellKnownUrl());
        return port();
    }

    /** Stop accepting and kill handler threads. Idempotent. */
    public synchronized void stop() {
        if (!started) {
            return;
        }
        server.stop(0);
        executor.shutdownNow();
        started = false;
        log.info("A2A server for agent '{}' stopped", card.name());
    }

    @Override
    public void close() {
        stop();
    }

    /** Bound port; the requested one (possibly 0) before start(). */
    public synchronized int port() {
        return started ? server.getAddress().getPort() : requestedPort;
    }

    // ============ HTTP routing ============

    private void handle(HttpExchange exchange) throws IOException {
        try {
            // Stage 6.3: bearer gate before anything else (agent.json stays
            // public — it is the discovery surface, carrying no task data).
            if (bearerToken != null) {
                String path0 = exchange.getRequestURI().getPath();
                if (!WELL_KNOWN_PATH.equals(path0)) {
                    String presented = exchange.getRequestHeaders().getFirst("Authorization");
                    if (!A2ASecurity.bearerMatches(presented, bearerToken)) {
                        respond(exchange, 401, plainError("unauthorized"));
                        return;
                    }
                }
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod().toUpperCase();
            if (WELL_KNOWN_PATH.equals(path)) {
                if ("GET".equals(method)) {
                    byte[] body = A2AJson.cardJson(cardWithV2Caps(), wellKnownUrlOverride())
                            .toString().getBytes(StandardCharsets.UTF_8);
                    if (cardSigningKeys != null) {
                        String signature = A2ACardIdentity.sign(
                                body, cardSigningKeys.getPrivate());
                        exchange.getResponseHeaders().set(
                                A2ACardIdentity.CARD_SIGNATURE_HEADER,
                                A2ACardIdentity.headerValue(
                                        A2ACardIdentity.keyIdOf(
                                                cardSigningKeys.getPublic()),
                                        A2ACardIdentity.algorithmFor(
                                                cardSigningKeys.getPrivate()
                                                        .getAlgorithm()),
                                        signature));
                    }
                    respond(exchange, 200, body);
                } else {
                    respond(exchange, 405, plainError("GET only"));
                }
                return;
            }
            if ("/".equals(path) || path.isEmpty()) {
                if ("POST".equals(method)) {
                    handleRpc(exchange);
                } else {
                    respond(exchange, 405, plainError("POST only"));
                }
                return;
            }
            respond(exchange, 404, plainError("not found: " + path));
        } finally {
            exchange.close();
        }
    }

    private void handleRpc(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode request;
        try {
            request = A2AJson.mapper().readTree(body);
        } catch (JsonProcessingException e) {
            respondJson(exchange, A2AJson.rpcError(null, -32700, "parse error: " + e.getMessage()));
            return;
        }
        if (!request.isObject()) {
            respondJson(exchange, A2AJson.rpcError(null, -32600, "invalid request: expected an object"));
            return;
        }
        JsonNode id = request.get("id");
        if (id == null || id.isNull()) {
            // A JSON-RPC notification. The A2A subset we speak is request/response only.
            respondJson(exchange, A2AJson.rpcError(null, -32600,
                    "v1 speaks request/response only; notifications are not supported"));
            return;
        }
        String method = request.path("method").asText("");
        ObjectNode result;
        try {
            switch (method) {
                case "message/send" -> result = handleSend(request.path("params"));
                case "message/stream" -> {
                    handleStream(exchange, id, request.path("params"));
                    return;
                }
                case "tasks/get" -> result = handleGet(request.path("params"));
                case "tasks/pushNotification/set" -> result = handlePushSet(request.path("params"));
                default -> {
                    respondJson(exchange, A2AJson.rpcError(id, -32601, "method not found: " + method));
                    return;
                }
            }
        } catch (ProtocolError e) {
            respondJson(exchange, A2AJson.rpcError(id, e.code(), e.getMessage()));
            return;
        }
        if (result == null) {
            respondJson(exchange, A2AJson.rpcError(id, -32001, "task not found"));
            return;
        }
        respondJson(exchange, A2AJson.rpcResult(id, result));
    }

    // ============ message/send ============

    private ObjectNode handleSend(JsonNode params) {
        A2ATaskStore.StoredA2ATask stored = acceptAndRun(params, null);
        return toTaskJson(stored);
    }

    /**
     * {@code message/stream}: same accept/run as send, but the response is SSE.
     * Events: {@code status} (working / terminal) and {@code artifact} (final text).
     * JSON-RPC id is not replayed on the stream — the events ARE the result.
     */
    private void handleStream(HttpExchange exchange, JsonNode id, JsonNode params) throws IOException {
        A2ATaskStore.StoredA2ATask working;
        try {
            working = beginTask(params);
        } catch (ProtocolError e) {
            respondJson(exchange, A2AJson.rpcError(id, e.code(), e.getMessage()));
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            if (working.status() == A2ATaskStatus.REJECTED) {
                writeSse(out, "status", toTaskJson(working));
                out.flush();
                return;
            }
            writeSse(out, "status", A2AJson.taskJson(working.taskId(), working.contextId(),
                    A2ATaskStatus.WORKING, null, List.of()));
            A2ATaskStore.StoredA2ATask done = finishTask(working, working.pendingText());
            writeSse(out, "artifact", toTaskJson(done));
            writeSse(out, "status", toTaskJson(done));
            out.flush();
        }
    }

    private ObjectNode handlePushSet(JsonNode params) {
        JsonNode idNode = params.get("id");
        if (idNode == null || idNode.isNull()) {
            throw new ProtocolError(-32602, "params.id is required");
        }
        String url = params.path("pushNotificationConfig").path("url").asText("");
        if (url.isBlank()) {
            throw new ProtocolError(-32602, "params.pushNotificationConfig.url is required");
        }
        A2ATaskStore.StoredA2ATask existing = taskStore.find(idNode.asText()).orElse(null);
        if (existing == null) {
            return null;
        }
        A2ATaskStore.StoredA2ATask updated = existing.withPushUrl(url);
        taskStore.save(updated);
        notifyPush(updated);
        ObjectNode ok = A2AJson.mapper().createObjectNode();
        ok.put("id", updated.taskId());
        ok.put("url", url);
        return ok;
    }

    /**
     * Parse / sanitize / allocate (or resume) without running the agent yet.
     * Used by SSE so we can emit {@code working} before {@code agent.run}.
     */
    private A2ATaskStore.StoredA2ATask beginTask(JsonNode params) {
        JsonNode message = params.path("message");
        if (!message.isObject()) {
            throw new ProtocolError(-32602, "params.message is required");
        }
        String text = A2AJson.firstTextPart(message.path("parts"));
        if (text == null) {
            throw new ProtocolError(-32602,
                    "message must contain a text part (v1 understands text parts only)");
        }

        JsonNode continuationId = message.get("taskId");
        String taskId;
        String contextId;
        AgentState state;
        String pushUrl;
        if (continuationId != null && !continuationId.isNull()) {
            A2ATaskStore.StoredA2ATask existing = taskStore.find(continuationId.asText()).orElse(null);
            if (existing == null) {
                throw new ProtocolError(-32001, "task not found: " + continuationId.asText());
            }
            if (existing.status() != A2ATaskStatus.INPUT_REQUIRED) {
                throw new ProtocolError(-32602,
                        "task '" + existing.taskId() + "' is " + existing.status().label()
                                + "; only input-required tasks can be continued");
            }
            taskId = existing.taskId();
            contextId = existing.contextId();
            state = deserializeState(existing.serializedState());
            pushUrl = existing.pushUrl();
        } else {
            taskId = UUID.randomUUID().toString();
            contextId = message.path("contextId").isTextual()
                    ? message.get("contextId").asText() : UUID.randomUUID().toString();
            state = new AgentState();
            pushUrl = null;
        }

        try {
            if (inboundSanitizer != null) {
                text = inboundSanitizer.apply(text);
            }
        } catch (RuntimeException e) {
            log.warn("[A2A] inbound sanitizer rejected task {} on agent '{}': {}",
                    taskId, card.name(), e.getMessage());
            A2ATaskStore.StoredA2ATask rejected = new A2ATaskStore.StoredA2ATask(taskId,
                    contextId, A2ATaskStatus.REJECTED,
                    "rejected by inbound policy: " + e.getMessage(), List.of(),
                    serializeState(state), pushUrl, null, null, null);
            taskStore.save(rejected);
            notifyPush(rejected);
            return rejected.withPendingText(null);
        }
        A2ATaskStore.StoredA2ATask skeleton = new A2ATaskStore.StoredA2ATask(taskId,
                contextId, A2ATaskStatus.WORKING, null, List.of(),
                serializeState(state), pushUrl, text, null, null);
        taskStore.save(skeleton);
        return skeleton;
    }

    private A2ATaskStore.StoredA2ATask acceptAndRun(JsonNode params, OutputStream ignored) {
        A2ATaskStore.StoredA2ATask begun = beginTask(params);
        if (begun.status() == A2ATaskStatus.REJECTED) {
            return begun;
        }
        return finishTask(begun, begun.pendingText());
    }

    private A2ATaskStore.StoredA2ATask finishTask(A2ATaskStore.StoredA2ATask begun, String text) {
        if (begun.status() == A2ATaskStatus.REJECTED) {
            return begun;
        }
        AgentState state = deserializeState(begun.serializedState());
        try {
            // Stage 1.2 (harness roadmap): every A2A task runs inside a
            // RunContext so trace/run correlation survives the protocol hop.
            // taskId becomes runId, contextId becomes traceId — an A2A task
            // continuation (same contextId) stays on the same trace.
            // Opt-in fallback: agents that only implement the legacy
            // overloads (mock / custom Agents) still run unchanged.
            RunContext ctx = RunContext.builder()
                    .runId(begun.taskId())
                    .traceId(begun.contextId())
                    .agentId(card.name())
                    .build();
            String output;
            try {
                output = agent.run(text == null ? "" : text, state, ctx);
            } catch (UnsupportedOperationException legacyAgent) {
                output = agent.run(text == null ? "" : text, state);
            }
            if (state.getStatus() == AgentState.Status.ERROR
                    || state.getStatus() == AgentState.Status.MAX_STEPS_EXCEEDED) {
                String reason = state.getLastError() == null
                        ? state.getStatus().toString() : state.getLastError();
                return store(begun, A2ATaskStatus.FAILED, reason, List.of(), state);
            }
            if (state.getStatus() == AgentState.Status.CANCELLED) {
                return store(begun, A2ATaskStatus.FAILED,
                        "cancelled", List.of(), state);
            }
            A2AArtifact artifact = A2AArtifact.text("artifact-1", output == null ? "" : output);
            return store(begun, A2ATaskStatus.COMPLETED, null, List.of(artifact), state);
        } catch (A2AInputRequiredException e) {
            return store(begun, A2ATaskStatus.INPUT_REQUIRED, e.getMessage(), List.of(), state);
        } catch (RuntimeException e) {
            log.warn("[A2A] agent '{}' threw on task {}: {}", card.name(), begun.taskId(), e.getMessage());
            return store(begun, A2ATaskStatus.FAILED, e.getMessage(), List.of(), state);
        }
    }

    private A2ATaskStore.StoredA2ATask store(A2ATaskStore.StoredA2ATask begun,
                                             A2ATaskStatus status, String message,
                                             List<A2AArtifact> artifacts, AgentState state) {
        A2ATaskStore.StoredA2ATask stored = new A2ATaskStore.StoredA2ATask(begun.taskId(),
                begun.contextId(), status, message, artifacts, serializeState(state),
                begun.pushUrl(), null, begun.createdAt(), null);
        taskStore.save(stored);
        notifyPush(stored);
        return stored;
    }

    private ObjectNode toTaskJson(A2ATaskStore.StoredA2ATask task) {
        return A2AJson.taskJson(task.taskId(), task.contextId(), task.status(),
                task.statusMessage(), task.artifacts());
    }

    /**
     * Fire the push webhook on terminal / input-required states. When a
     * pushSharedSecret is configured the push is SIGNED ({@code X-Signature}
     * / {@code X-Timestamp} / {@code X-Nonce}) so receivers can reject
     * forgeries and replays with {@link A2ASecurity#verifyPushSignature};
     * without it we send the legacy unsigned body (tests / trusted nets).
     */
    private void notifyPush(A2ATaskStore.StoredA2ATask task) {
        if (task.pushUrl() == null || task.pushUrl().isBlank()) {
            return;
        }
        if (task.status() == A2ATaskStatus.WORKING || task.status() == A2ATaskStatus.SUBMITTED) {
            return;
        }
        try {
            String body = toTaskJson(task).toString();
            java.net.http.HttpRequest.Builder request =
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(task.pushUrl()))
                            .timeout(java.time.Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
            if (pushSharedSecret != null) {
                A2ASecurity.PushEnvelope signed =
                        A2ASecurity.signPush(task.taskId(), body, pushSharedSecret);
                request.header("X-Signature", signed.signature())
                        .header("X-Timestamp", signed.timestamp())
                        .header("X-Nonce", signed.nonce());
            }
            java.net.http.HttpClient.newHttpClient().send(request.build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warn("[A2A] push to {} failed for task {}: {}", task.pushUrl(), task.taskId(), e.toString());
        }
    }

    private static void writeSse(OutputStream out, String event, ObjectNode data) throws IOException {
        String payload = "event: " + event + "\ndata: " + data + "\n\n";
        out.write(payload.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private AgentCard cardWithV2Caps() {
        return new AgentCard(card.name(), card.description(), card.skills(),
                card.endpoint(), card.version(), card.url(), A2ACapabilities.v2());
    }

    // ============ tasks/get ============

    private ObjectNode handleGet(JsonNode params) {
        JsonNode idNode = params.get("id");
        if (idNode == null || idNode.isNull()) {
            throw new ProtocolError(-32602, "params.id is required");
        }
        A2ATaskStore.StoredA2ATask task = taskStore.find(idNode.asText()).orElse(null);
        if (task == null) {
            return null;  // mapped to -32001 by the caller
        }
        return A2AJson.taskJson(task.taskId(), task.contextId(), task.status(),
                task.statusMessage(), task.artifacts());
    }

    // ============ Internal ============

    /**
     * AgentState is a plain Jackson POJO (no static toJson/fromJson) — the
     * store holds it as a JSON string so a remote store (Redis/Postgres)
     * needs no framework classes on its side.
     */
    private static String serializeState(AgentState state) {
        try {
            return state == null ? null : A2AJson.mapper().writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("agent state not serializable", e);
        }
    }

    private static AgentState deserializeState(String serialized) {
        if (serialized == null || serialized.isBlank()) {
            return new AgentState();
        }
        try {
            return A2AJson.mapper().readValue(serialized, AgentState.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored agent state not parseable", e);
        }
    }

    /** Server fills the card url with its bound address unless the card carries a real one. */
    private String wellKnownUrlOverride() {
        String url = card.url();
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return wellKnownUrl();
        }
        return null;
    }

    private String wellKnownUrl() {
        return "http://127.0.0.1:" + port() + "/";
    }

    private void respondJson(HttpExchange exchange, ObjectNode node) throws IOException {
        respond(exchange, 200, node.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private byte[] plainError(String message) {
        return ("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    /** Internal control-flow for JSON-RPC-level param/method errors. */
    private static final class ProtocolError extends RuntimeException {
        private final int code;

        ProtocolError(int code, String message) {
            super(message);
            this.code = code;
        }

        int code() {
            return code;
        }
    }
}
