package io.github.qwzhang01.agent.mcp.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/**
 * JSON-RPC 2.0 request (Stage 10 D3).
 * <p>
 * Every request has an id that the server echoes back in the response for correlation.
 * Method names follow MCP convention: "initialize" / "tools/list" / "tools/call" / "shutdown".
 *
 * @param id     client-assigned id (matches response id)
 * @param method MCP method name
 * @param params method-specific parameters (nullable for parameterless calls)
 */
public record JsonRpcRequest(
        Object id,
        String method,
        JsonNode params
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public JsonRpcRequest {
        Objects.requireNonNull(id, "request id must not be null");
        Objects.requireNonNull(method, "method must not be null");
    }

    /**
     * Serialize to a JSON string for sending over the transport.
     */
    public String toJson() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.set("id", MAPPER.valueToTree(id));
        node.put("method", method);
        if (params != null) {
            node.set("params", params);
        }
        return node.toString();
    }
}
