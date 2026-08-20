package io.github.qwzhang01.agent.mcp.jsonrpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 10 M10.1 tests: JSON-RPC 2.0 message serialization/deserialization.
 */
class JsonRpcTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ============ JsonRpcRequest ============

    @Test
    void request_withParams_serializesToJson() {
        ObjectNode params = mapper.createObjectNode().put("city", "Beijing");
        JsonRpcRequest req = new JsonRpcRequest(1L, "tools/call", params);

        String json = req.toJson();

        assertTrue(json.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(json.contains("\"id\":1"));
        assertTrue(json.contains("\"method\":\"tools/call\""));
        assertTrue(json.contains("\"params\""));
        assertTrue(json.contains("\"city\":\"Beijing\""));
    }

    @Test
    void request_nullParams_omitsParamsField() {
        JsonRpcRequest req = new JsonRpcRequest(2L, "tools/list", null);

        String json = req.toJson();

        assertTrue(json.contains("\"method\":\"tools/list\""));
        assertFalse(json.contains("\"params\""));
    }

    @Test
    void request_stringId_supported() {
        JsonRpcRequest req = new JsonRpcRequest("abc-123", "initialize", null);
        String json = req.toJson();
        assertTrue(json.contains("\"id\":\"abc-123\""));
    }

    @Test
    void request_nullId_throws() {
        assertThrows(NullPointerException.class, () -> new JsonRpcRequest(null, "ping", null));
    }

    // ============ JsonRpcResponse ============

    @Test
    void response_success_parsesResult() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}";

        JsonRpcResponse resp = JsonRpcResponse.fromJson(json);

        assertEquals(1, resp.id());
        assertNotNull(resp.result());
        assertNull(resp.error());
        assertFalse(resp.isError());
    }

    @Test
    void response_error_parsesError() {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}";

        JsonRpcResponse resp = JsonRpcResponse.fromJson(json);

        assertEquals(2, resp.id());
        assertNull(resp.result());
        assertNotNull(resp.error());
        assertTrue(resp.isError());
        assertTrue(resp.error().get("message").asText().contains("not found"));
    }

    @Test
    void response_helpers() {
        ObjectNode result = mapper.createObjectNode().put("ok", true);
        JsonRpcResponse success = JsonRpcResponse.success(1, result);
        assertEquals(1, success.id());
        assertFalse(success.isError());

        ObjectNode err = mapper.createObjectNode().put("code", -1);
        JsonRpcResponse error = JsonRpcResponse.error(2, err);
        assertEquals(2, error.id());
        assertTrue(error.isError());
    }

    @Test
    void response_invalidJson_throws() {
        assertThrows(IllegalArgumentException.class, () -> JsonRpcResponse.fromJson("not json"));
    }

    // ============ JsonRpcNotification ============

    @Test
    void notification_withParams_serializes() {
        ObjectNode params = mapper.createObjectNode().put("version", "1.0");
        JsonRpcNotification notif = new JsonRpcNotification("notifications/initialized", params);

        String json = notif.toJson();

        assertTrue(json.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(json.contains("\"method\":\"notifications/initialized\""));
        assertTrue(json.contains("\"params\""));
        assertFalse(json.contains("\"id\""));  // notifications have no id
    }

    @Test
    void notification_nullParams_omitsField() {
        JsonRpcNotification notif = new JsonRpcNotification("ping", null);
        String json = notif.toJson();
        assertFalse(json.contains("\"params\""));
        assertFalse(json.contains("\"id\""));
    }

    @Test
    void notification_nullMethod_throws() {
        assertThrows(NullPointerException.class, () -> new JsonRpcNotification(null, null));
    }
}
