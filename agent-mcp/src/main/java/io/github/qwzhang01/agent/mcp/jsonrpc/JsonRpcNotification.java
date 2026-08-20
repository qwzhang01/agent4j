package io.github.qwzhang01.agent.mcp.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/**
 * JSON-RPC 2.0 notification (Stage 10 D3).
 * <p>
 * Like a request but with no id -- the server is not expected to reply.
 * Used for one-way signals like "notifications/initialized" (MCP handshake step 2).
 *
 * @param method notification method name
 * @param params notification parameters (nullable)
 */
public record JsonRpcNotification(
        String method,
        JsonNode params
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public JsonRpcNotification {
        Objects.requireNonNull(method, "method must not be null");
    }

    /**
     * Serialize to a JSON string for sending over the transport.
     */
    public String toJson() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("method", method);
        if (params != null) {
            node.set("params", params);
        }
        return node.toString();
    }
}
