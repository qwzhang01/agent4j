package io.github.qwzhang01.agent.mcp.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/**
 * JSON-RPC 2.0 response (Stage 10 D3).
 * <p>
 * Carries either a result (success) or an error (failure) -- never both.
 * The id matches the request id for correlation.
 *
 * @param id     echoes the request id
 * @param result result payload (nullable when error is set)
 * @param error  error object (nullable when result is set)
 */
public record JsonRpcResponse(
        Object id,
        JsonNode result,
        JsonNode error
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public JsonRpcResponse {
        Objects.requireNonNull(id, "response id must not be null");
    }

    /**
     * Whether this response carries an error.
     */
    public boolean isError() {
        return error != null;
    }

    /**
     * Parse a JSON string into a JsonRpcResponse.
     * Tolerates responses that only set one of result/error.
     */
    public static JsonRpcResponse fromJson(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            Object id = MAPPER.treeToValue(root.get("id"), Object.class);
            JsonNode result = root.get("result");
            JsonNode error = root.get("error");
            return new JsonRpcResponse(id, result, error);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON-RPC response: " + e.getMessage(), e);
        }
    }

    /**
     * Construct a success response (helper).
     */
    public static JsonRpcResponse success(Object id, JsonNode result) {
        return new JsonRpcResponse(id, result, null);
    }

    /**
     * Construct an error response (helper).
     */
    public static JsonRpcResponse error(Object id, JsonNode error) {
        return new JsonRpcResponse(id, null, error);
    }
}
