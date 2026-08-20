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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP client: manages connection lifecycle and protocol operations (Stage 10 D3/D5).
 * <p>
 * Operations:
 * <ol>
 *   <li>{@link #connect} -- initialize handshake (send capabilities, receive server capabilities)
 *   <li>{@link #listTools} -- discover tools the server exposes
 *   <li>{@link #callTool} -- invoke a tool and get its result
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

    private final McpServerDescriptor descriptor;
    private final McpTransport transport;
    private final AtomicLong nextId = new AtomicLong(1);
    private volatile boolean initialized = false;

    /**
     * Create a client with a stdio transport (descriptor.command -> StdioTransport).
     */
    public McpClient(McpServerDescriptor descriptor) {
        this.descriptor = Objects.requireNonNull(descriptor);
        if (descriptor.isStdio()) {
            this.transport = new StdioTransport(descriptor.command());
        } else {
            throw new UnsupportedOperationException(
                    "SSE transport not yet implemented (Stage 10 v2). " +
                            "Use McpServerDescriptor.stdio() for now.");
        }
    }

    /**
     * Create a client with a custom transport (for testing / SSE / etc.).
     */
    public McpClient(McpServerDescriptor descriptor, McpTransport transport) {
        this.descriptor = Objects.requireNonNull(descriptor);
        this.transport = Objects.requireNonNull(transport);
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

    // ============ Internal Helpers ============

    private JsonRpcResponse sendRequest(String method, JsonNode params) throws IOException {
        long id = nextId.getAndIncrement();
        JsonRpcRequest request = new JsonRpcRequest(id, method, params);

        transport.send(request.toJson());

        // v1: synchronous -- block until we get a response with matching id
        // (We assume responses come in order; for interleaved notifications,
        // v2 would need a response queue keyed by id)
        while (true) {
            String respJson = transport.receive();
            JsonRpcResponse response = JsonRpcResponse.fromJson(respJson);

            // Check if this is the response to our request (by id)
            // Compare as strings to handle Integer vs Long type mismatch
            // (Jackson parses small JSON numbers as Integer, our id is Long)
            if (response.id() != null
                    && String.valueOf(response.id()).equals(String.valueOf(id))) {
                return response;
            }
            // Otherwise it's a stray notification or out-of-order response
            // In v1, we just log and continue (notifications have no id)
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
