package io.github.qwzhang01.agent.mcp.a2a;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentState;
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
import java.util.concurrent.ConcurrentHashMap;
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
 * v1 honest boundaries: synchronous execution only (no working-state
 * streaming, no push notifications), in-memory task store (lost on
 * restart), no task continuation (a message carrying {@code message.taskId}
 * is refused loudly, not silently re-run), binds 127.0.0.1 only (a
 * production deployment behind a reverse proxy needs a host parameter --
 * v2), and the agent runs on the handler thread.
 */
public class HttpA2AServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HttpA2AServer.class);

    public static final String WELL_KNOWN_PATH = "/.well-known/agent.json";

    private final AgentCard card;
    private final Agent agent;
    private final UnaryOperator<String> inboundSanitizer;  // nullable = raw passthrough
    private final int requestedPort;
    private final Map<String, StoredTask> tasks = new ConcurrentHashMap<>();

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
        this.card = Objects.requireNonNull(card, "card must not be null");
        this.agent = Objects.requireNonNull(agent, "agent must not be null");
        this.requestedPort = port;
        this.inboundSanitizer = inboundSanitizer;
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
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod().toUpperCase();
            if (WELL_KNOWN_PATH.equals(path)) {
                if ("GET".equals(method)) {
                    byte[] body = A2AJson.cardJson(card, wellKnownUrlOverride())
                            .toString().getBytes(StandardCharsets.UTF_8);
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
                case "tasks/get" -> result = handleGet(request.path("params"));
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
        JsonNode message = params.path("message");
        if (!message.isObject()) {
            throw new ProtocolError(-32602, "params.message is required");
        }
        JsonNode continuationId = message.get("taskId");
        if (continuationId != null && !continuationId.isNull()) {
            throw new ProtocolError(-32001,
                    "v1: task continuation is not supported; send a fresh message without message.taskId");
        }
        String text = A2AJson.firstTextPart(message.path("parts"));
        if (text == null) {
            throw new ProtocolError(-32602,
                    "message must contain a text part (v1 understands text parts only)");
        }

        String taskId = UUID.randomUUID().toString();
        String contextId = message.path("contextId").isTextual()
                ? message.get("contextId").asText() : UUID.randomUUID().toString();

        // Inbound defense: wire text is untrusted input to our agent's prompt.
        try {
            if (inboundSanitizer != null) {
                text = inboundSanitizer.apply(text);
            }
        } catch (RuntimeException e) {
            log.warn("[A2A] inbound sanitizer rejected task {} on agent '{}': {}",
                    taskId, card.name(), e.getMessage());
            tasks.put(taskId, new StoredTask(taskId, contextId, A2ATaskStatus.REJECTED,
                    "rejected by inbound policy: " + e.getMessage(), List.of()));
            return A2AJson.taskJson(taskId, contextId, A2ATaskStatus.REJECTED,
                    "rejected by inbound policy: " + e.getMessage(), List.of());
        }

        AgentState state = new AgentState();
        try {
            String output = agent.run(text, state);
            if (state.getStatus() == AgentState.Status.ERROR
                    || state.getStatus() == AgentState.Status.MAX_STEPS_EXCEEDED) {
                String reason = state.getLastError() == null
                        ? state.getStatus().toString() : state.getLastError();
                tasks.put(taskId, new StoredTask(taskId, contextId, A2ATaskStatus.FAILED,
                        reason, List.of()));
                return A2AJson.taskJson(taskId, contextId, A2ATaskStatus.FAILED, reason, List.of());
            }
            A2AArtifact artifact = A2AArtifact.text("artifact-1", output == null ? "" : output);
            tasks.put(taskId, new StoredTask(taskId, contextId, A2ATaskStatus.COMPLETED,
                    null, List.of(artifact)));
            return A2AJson.taskJson(taskId, contextId, A2ATaskStatus.COMPLETED,
                    null, List.of(artifact));
        } catch (RuntimeException e) {
            log.warn("[A2A] agent '{}' threw on task {}: {}", card.name(), taskId, e.getMessage());
            tasks.put(taskId, new StoredTask(taskId, contextId, A2ATaskStatus.FAILED,
                    e.getMessage(), List.of()));
            return A2AJson.taskJson(taskId, contextId, A2ATaskStatus.FAILED, e.getMessage(), List.of());
        }
    }

    // ============ tasks/get ============

    private ObjectNode handleGet(JsonNode params) {
        JsonNode idNode = params.get("id");
        if (idNode == null || idNode.isNull()) {
            throw new ProtocolError(-32602, "params.id is required");
        }
        StoredTask task = tasks.get(idNode.asText());
        if (task == null) {
            return null;  // mapped to -32001 by the caller
        }
        return A2AJson.taskJson(task.taskId(), task.contextId(), task.status(),
                task.statusMessage(), task.artifacts());
    }

    // ============ Internal ============

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

    private record StoredTask(String taskId, String contextId, A2ATaskStatus status,
                              String statusMessage, List<A2AArtifact> artifacts) {
    }
}
