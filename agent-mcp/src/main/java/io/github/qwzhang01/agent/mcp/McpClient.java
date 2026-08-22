package io.github.qwzhang01.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.mcp.jsonrpc.JsonRpcNotification;
import io.github.qwzhang01.agent.mcp.jsonrpc.JsonRpcRequest;
import io.github.qwzhang01.agent.mcp.jsonrpc.JsonRpcResponse;
import io.github.qwzhang01.agent.mcp.transport.McpTransport;
import io.github.qwzhang01.agent.mcp.transport.StdioTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * MCP client: manages connection lifecycle and protocol operations (Stage 10 D3/D5).
 * <p>
 * Operations:
 * <ol>
 *   <li>{@link #connect} -- initialize handshake (send capabilities, receive server capabilities)
 *   <li>{@link #listTools} -- discover tools the server exposes
 *   <li>{@link #callTool} -- invoke a tool and get its result
 *   <li>{@link #ping} -- MCP-standard liveness probe (process management)
 *   <li>{@link #reconnect} -- close dead transport, build a fresh one, redo the handshake
 *   <li>{@link #disconnect} -- graceful shutdown
 * </ol>
 * <p>
 * v1 uses synchronous request-response: send a request, block on receive() for
 * the matching response (by id). Notifications (no id) are fire-and-forget.
 * <p>
 * Designed for stdio transport (local subprocess). For SSE/HTTP, swap the
 * McpTransport implementation.
 */
public class McpClient {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Max unmatched notifications / out-of-order responses before sendRequest fails. */
    public static final int DEFAULT_MAX_STRAY_MESSAGES = 32;
    /** How long sendRequest waits for one receive() before failing. */
    public static final Duration DEFAULT_RECEIVE_TIMEOUT = Duration.ofSeconds(30);

    private final McpServerDescriptor descriptor;
    private final Supplier<McpTransport> transportFactory;
    private McpTransport transport;  // mutable: swapped on reconnect()
    private final AtomicLong nextId = new AtomicLong(1);
    private volatile boolean initialized = false;
    private volatile int maxStrayMessages = DEFAULT_MAX_STRAY_MESSAGES;
    private volatile Duration receiveTimeout = DEFAULT_RECEIVE_TIMEOUT;

    /**
     * Create a client with a stdio transport (descriptor.command -> StdioTransport).
     * <p>
     * Every {@link #reconnect} spawns a FRESH subprocess via the factory.
     */
    public McpClient(McpServerDescriptor descriptor) {
        this(descriptor, stdioFactory(descriptor));
    }

    /**
     * Create a client with a custom transport (for testing / SSE / etc.).
     * <p>
     * The same transport instance is reused across reconnects, so it must be
     * reopenable ({@code open()} after {@code close()}): mocks are, a real
     * {@link StdioTransport} is not -- use the factory constructor in production.
     */
    public McpClient(McpServerDescriptor descriptor, McpTransport transport) {
        this(descriptor, () -> transport);
    }

    /**
     * Create a client with a transport factory -- the recommended constructor
     * for reconnect-capable setups: every {@link #reconnect} calls the factory
     * to build a fresh transport (new subprocess / new connection).
     */
    public McpClient(McpServerDescriptor descriptor, Supplier<McpTransport> transportFactory) {
        this.descriptor = Objects.requireNonNull(descriptor);
        this.transportFactory = Objects.requireNonNull(transportFactory, "transportFactory must not be null");
        this.transport = transportFactory.get();
    }

    private static Supplier<McpTransport> stdioFactory(McpServerDescriptor descriptor) {
        if (!descriptor.isStdio()) {
            throw new UnsupportedOperationException(
                    "SSE transport not yet implemented (Stage 10 v2). " +
                            "Use McpServerDescriptor.stdio() for now.");
        }
        return () -> new StdioTransport(descriptor.command());
    }

    // ============ Connection Lifecycle ============

    /**
     * Initialize handshake with the MCP server (D5).
     * <p>
     * Step 1: send initialize request (client capabilities + protocol version)
     * Step 2: receive initialize response (server capabilities)
     * Step 3: send initialized notification (handshake complete)
     */
    public void connect() throws IOException {
        log.info("Connecting to MCP server '{}'...", descriptor.name());
        transport.open();

        // Step 1+2: initialize request-response
        ObjectNode initParams = MAPPER.createObjectNode();
        ObjectNode clientInfo = MAPPER.createObjectNode();
        clientInfo.put("name", "java-agent-framework");
        clientInfo.put("version", "0.1.0");
        initParams.set("clientInfo", clientInfo);
        initParams.put("protocolVersion", descriptor.version());
        ObjectNode clientCaps = MAPPER.createObjectNode();
        initParams.set("capabilities", clientCaps);

        JsonRpcResponse initResp = sendRequest("initialize", initParams);
        if (initResp.isError()) {
            throw new IOException("Initialize failed: " + initResp.error());
        }
        log.info("MCP server '{}' initialized: {}", descriptor.name(),
                initResp.result() != null ? initResp.result().get("serverInfo") : "(unknown)");

        // Step 3: initialized notification (fire-and-forget)
        JsonRpcNotification initNotif = new JsonRpcNotification("notifications/initialized", null);
        transport.send(initNotif.toJson());

        initialized = true;
        log.info("MCP server '{}' connected", descriptor.name());
    }

    /**
     * Graceful shutdown (D5).
     * Sends shutdown request, then closes the transport.
     */
    public void disconnect() {
        if (!initialized) {
            log.warn("Disconnect called but not initialized, just closing transport");
            closeTransport();
            return;
        }
        try {
            JsonRpcRequest shutdownReq = new JsonRpcRequest(
                    nextId.getAndIncrement(), "shutdown", null);
            transport.send(shutdownReq.toJson());
            // Best-effort: read response (server may have already exited)
            String respJson = transport.receive();
            log.info("MCP server '{}' shutdown response: {}", descriptor.name(), respJson);
        } catch (IOException e) {
            log.warn("Error during shutdown of '{}': {}", descriptor.name(), e.getMessage());
        } finally {
            closeTransport();
            initialized = false;
        }
    }

    // ============ Tool Operations ============

    /**
     * List all tools exposed by the server.
     */
    public List<McpToolSchema> listTools() throws IOException {
        ensureConnected();
        JsonRpcResponse resp = sendRequest("tools/list", null);

        if (resp.isError()) {
            throw new IOException("tools/list failed: " + resp.error());
        }

        JsonNode result = resp.result();
        if (result == null || !result.has("tools")) {
            return List.of();
        }

        JsonNode toolsArray = result.get("tools");
        List<McpToolSchema> tools = new ArrayList<>();
        for (JsonNode toolNode : toolsArray) {
            tools.add(McpToolSchema.from(toolNode));
        }
        log.info("Discovered {} tools from server '{}'", tools.size(), descriptor.name());
        return tools;
    }

    /**
     * Call a tool on the server and return the text result.
     * <p>
     * v1 only extracts "text" content from the MCP response. Other content types
     * (images, resource refs) are skipped.
     *
     * @param toolName the tool to call
     * @param args     the arguments (JSON node)
     * @return the concatenated text content from the tool result
     */
    public String callTool(String toolName, JsonNode args) throws IOException {
        ensureConnected();

        ObjectNode callParams = MAPPER.createObjectNode();
        callParams.put("name", toolName);
        if (args != null) {
            callParams.set("arguments", args);
        } else {
            callParams.set("arguments", MAPPER.createObjectNode());
        }

        JsonRpcResponse resp = sendRequest("tools/call", callParams);

        if (resp.isError()) {
            throw new IOException("tools/call failed for '" + toolName + "': " + resp.error());
        }

        return extractTextContent(resp.result());
    }

    // ============ Health & Reconnect (process management) ============

    /**
     * MCP-standard liveness probe: send a {@code ping} request; a healthy server
     * must reply with an (empty) result. Throws if the server is dead, the
     * connection is broken, or the server answers with an error.
     */
    public void ping() throws IOException {
        ensureConnected();
        JsonRpcResponse resp = sendRequest("ping", null);
        if (resp.isError()) {
            throw new IOException("ping failed: " + resp.error());
        }
    }

    /**
     * Re-establish the connection after the old one died: close the old
     * transport, build a fresh one from the factory, redo the initialize
     * handshake. The mechanism behind {@link ManagedMcpClient}'s auto-recovery.
     */
    public void reconnect() throws IOException {
        log.info("Reconnecting to MCP server '{}'...", descriptor.name());
        initialized = false;
        closeTransport();
        this.transport = transportFactory.get();
        connect();
    }

    /**
     * The current transport. Read-only usage (health checks, crash simulation);
     * the transport lifecycle is managed by this client.
     */
    public McpTransport getTransport() {
        return transport;
    }

    // ============ Internal Helpers ============

    public McpClient setMaxStrayMessages(int maxStrayMessages) {
        if (maxStrayMessages < 1) {
            throw new IllegalArgumentException("maxStrayMessages must be >= 1");
        }
        this.maxStrayMessages = maxStrayMessages;
        return this;
    }

    public McpClient setReceiveTimeout(Duration receiveTimeout) {
        this.receiveTimeout = Objects.requireNonNull(receiveTimeout, "receiveTimeout");
        return this;
    }

    private JsonRpcResponse sendRequest(String method, JsonNode params) throws IOException {
        long id = nextId.getAndIncrement();
        JsonRpcRequest request = new JsonRpcRequest(id, method, params);

        transport.send(request.toJson());

        // Synchronous: wait for the matching id. Stray notifications / out-of-order
        // responses are skipped up to maxStrayMessages; receive() is bounded by timeout.
        // Id comparison is String.valueOf so Integer vs Long still match.
        int stray = 0;
        int strayLimit = maxStrayMessages;
        while (true) {
            String respJson = transport.receive(receiveTimeout);
            JsonRpcResponse response;
            try {
                response = JsonRpcResponse.fromJson(respJson);
            } catch (RuntimeException e) {
                stray++;
                if (stray > strayLimit) {
                    throw new IOException("Too many stray MCP messages (limit="
                            + strayLimit + ") while waiting for id=" + id, e);
                }
                log.debug("Received unparseable/stray message (expected id={}): {}", id, respJson);
                continue;
            }

            if (response.id() != null
                    && String.valueOf(response.id()).equals(String.valueOf(id))) {
                return response;
            }
            stray++;
            if (stray > strayLimit) {
                throw new IOException("Too many stray MCP messages (limit="
                        + strayLimit + ") while waiting for id=" + id);
            }
            log.debug("Received out-of-order message (expected id={}): {}", id, respJson);
        }
    }

    private String extractTextContent(JsonNode result) {
        if (result == null) return "";

        JsonNode contentArray = result.get("content");
        if (contentArray == null || !contentArray.isArray()) return "";

        StringBuilder sb = new StringBuilder();
        for (JsonNode item : contentArray) {
            String type = item.has("type") ? item.get("type").asText() : "";
            if ("text".equals(type)) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(item.get("text").asText());
            }
            // Other types (image, resource) skipped in v1
        }
        return sb.toString();
    }

    private void ensureConnected() throws IOException {
        if (!initialized) {
            throw new IOException("MCP client not connected. Call connect() first.");
        }
    }

    private void closeTransport() {
        try {
            transport.close();
        } catch (IOException e) {
            log.warn("Error closing transport: {}", e.getMessage());
        }
    }

    // ============ Accessors ============

    public McpServerDescriptor getDescriptor() { return descriptor; }
    public boolean isConnected() { return initialized; }
}
