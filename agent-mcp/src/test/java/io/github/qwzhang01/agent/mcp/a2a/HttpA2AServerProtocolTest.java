package io.github.qwzhang01.agent.mcp.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Protocol-level tests straight at the wire: raw JSON POSTs against
 * HttpA2AServer, no client class involved. These pin the JSON-RPC semantics
 * the round-trip tests cannot express (continuation refusal, notification
 * refusal, parse errors, method routing, HTTP verb rules).
 */
class HttpA2AServerProtocolTest {

    private HttpA2AServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private static Agent noopAgent(AtomicInteger runs) {
        return new Agent() {
            @Override public String run(String in) { return "ok"; }
            @Override public String run(String in, AgentState s) {
                if (runs != null) {
                    runs.incrementAndGet();
                }
                return "ok";
            }
            @Override public AgentConfig getConfig() { return null; }
        };
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.port();
    }

    private JsonNode post(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return A2AJson.mapper().readTree(
                http.send(request, HttpResponse.BodyHandlers.ofString()).body());
    }

    private HttpResponse<String> raw(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path));
        if (body == null) {
            builder = builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder = builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void start() throws Exception {
        server = new HttpA2AServer(new AgentCard("svc", "test", List.of("x"), "u", "1.0"),
                noopAgent(null), 0, null);
        server.start();
    }

    @Test
    void wellKnown_servedOnGet_rejectedOnPost() throws Exception {
        start();

        HttpResponse<String> get = raw("GET", HttpA2AServer.WELL_KNOWN_PATH, null);
        assertEquals(200, get.statusCode());
        JsonNode card = A2AJson.mapper().readTree(get.body());
        assertEquals("svc", card.path("name").asText());
        assertEquals("JSONRPC", card.path("preferredTransport").asText());
        assertTrue(card.path("url").asText().startsWith("http://127.0.0.1:"));

        assertEquals(405, raw("POST", HttpA2AServer.WELL_KNOWN_PATH, "{}").statusCode());
    }

    @Test
    void unknownMethod_minus32601() throws Exception {
        start();

        JsonNode response = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"message/stream\"}");

        assertEquals(-32601, response.path("error").path("code").asInt());
        assertTrue(response.path("error").path("message").asText().contains("message/stream"));
    }

    @Test
    void parseError_minus32700() throws Exception {
        start();

        JsonNode response = post("not json at all {");

        assertEquals(-32700, response.path("error").path("code").asInt());
    }

    @Test
    void notification_withoutId_refused_minus32600() throws Exception {
        start();

        JsonNode response = post("{\"jsonrpc\":\"2.0\",\"method\":\"message/send\",\"params\":{}}");

        assertEquals(-32600, response.path("error").path("code").asInt());
    }

    @Test
    void taskContinuation_messageWithTaskId_refused_minus32001() throws Exception {
        start();

        String body = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"message/send\","
                + "\"params\":{\"message\":{\"messageId\":\"m1\",\"role\":\"user\","
                + "\"taskId\":\"some-existing-task\","
                + "\"parts\":[{\"kind\":\"text\",\"text\":\"continue\"}]}}}";
        JsonNode response = post(body);

        assertEquals(-32001, response.path("error").path("code").asInt());
        assertTrue(response.path("error").path("message").asText().contains("continuation"));
    }

    @Test
    void messageWithoutTextPart_minus32602() throws Exception {
        start();

        String body = "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"message/send\","
                + "\"params\":{\"message\":{\"messageId\":\"m2\",\"role\":\"user\","
                + "\"parts\":[{\"kind\":\"file\",\"fileUri\":\"http://x\"}]}}}";
        JsonNode response = post(body);

        assertEquals(-32602, response.path("error").path("code").asInt());
        assertTrue(response.path("error").path("message").asText().contains("text part"));
    }

    @Test
    void tasksGet_unknownTask_minus32001_thenKnownTask_succeeds() throws Exception {
        start();

        JsonNode missing = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tasks/get\","
                + "\"params\":{\"id\":\"nope\"}}");
        assertEquals(-32001, missing.path("error").path("code").asInt());

        // Create a task for real, then fetch it back.
        JsonNode created = post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"message/send\","
                + "\"params\":{\"message\":{\"messageId\":\"m3\",\"role\":\"user\","
                + "\"parts\":[{\"kind\":\"text\",\"text\":\"hi\"}]}}}");
        String taskId = created.path("result").path("id").asText();
        assertFalse(taskId.isEmpty());

        JsonNode fetched = post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tasks/get\","
                + "\"params\":{\"id\":\"" + taskId + "\"}}");
        assertEquals("completed", fetched.path("result").path("status").path("state").asText());
        assertEquals(taskId, fetched.path("result").path("id").asText());
    }

    @Test
    void rootPath_postOnly_getIs405_unknownIs404() throws Exception {
        start();

        assertEquals(405, raw("GET", "/", null).statusCode());
        assertEquals(404, raw("POST", "/other", "{}").statusCode());
    }

    @Test
    void sendRequest_taskStatusShape_matchesSpecDialect() throws Exception {
        start();

        JsonNode response = post("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"message/send\","
                + "\"params\":{\"message\":{\"messageId\":\"m4\",\"role\":\"user\","
                + "\"contextId\":\"ctx-42\","
                + "\"parts\":[{\"kind\":\"text\",\"text\":\"hello\"}]}}}");
        JsonNode task = response.path("result");

        assertFalse(task.path("id").asText().isEmpty());
        assertEquals("ctx-42", task.path("contextId").asText());
        assertEquals("completed", task.path("status").path("state").asText());
        assertEquals("ok", task.path("artifacts").get(0).path("parts").get(0).path("text").asText());
        assertEquals("text", task.path("artifacts").get(0).path("parts").get(0).path("kind").asText());
    }
}
