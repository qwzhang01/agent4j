package io.github.qwzhang01.agent.mcp.a2a;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * wire-level tests for the new HttpA2AServer surface: bearer auth
 * (401 on protected routes, agent card stays public), signed push
 * notifications (X-Signature / X-Timestamp / X-Nonce headers), and task
 * persistence through the pluggable store (a shared store survives a
 * server restart — the cross-instance recovery story).
 *
 * <p>A2A Integration Profile: tagged {@code a2a-it} (see
 * {@link HttpA2ARoundTripTest} for the profile entry point).</p>
 */
@Tag("a2a-it")
class HttpA2AServerSecurityTest {

    private static final String TOKEN = "test-bearer-token";
    private static final String PUSH_SECRET = "push-shared-secret";

    private HttpA2AServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private static Agent noopAgent() {
        return new Agent() {
            @Override public String run(String in) { return "ok"; }
            @Override public String run(String in, AgentState s) { return "ok"; }
            @Override public AgentConfig getConfig() { return null; }
        };
    }

    private HttpA2AServer startSecure(InMemoryA2ATaskStore sharedStore) throws Exception {
        server = new HttpA2AServer(
                new AgentCard("secure-svc", "test", List.of("x"), "u", "1.0"),
                noopAgent(), 0, null,
                sharedStore, TOKEN, PUSH_SECRET);
        server.start();
        return server;
    }

    private JsonNodeHolder post(String path, String body, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.port() + path))
                .header("Content-Type", "application/json");
        if (bearer != null) {
            builder.header("Authorization", bearer);
        }
        HttpResponse<String> response = http.send(
                builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        return new JsonNodeHolder(response.statusCode(), response.body());
    }

    /** Minimal status+body pair so tests can assert both. */
    private record JsonNodeHolder(int status, String body) {
    }

    private static final String SEND_BODY = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"message/send\","
            + "\"params\":{\"message\":{\"messageId\":\"m1\",\"role\":\"user\","
            + "\"parts\":[{\"kind\":\"text\",\"text\":\"hi\"}]}}}";

    @Test
    void bearer_missingOrWrong_401_correctToken_200() throws Exception {
        startSecure(new InMemoryA2ATaskStore());

        JsonNodeHolder noAuth = post("/", SEND_BODY, null);
        assertEquals(401, noAuth.status());

        JsonNodeHolder wrong = post("/", SEND_BODY, "Bearer wrong-token");
        assertEquals(401, wrong.status());

        JsonNodeHolder right = post("/", SEND_BODY, "Bearer " + TOKEN);
        assertEquals(200, right.status());
        assertEquals("completed",
                A2AJson.mapper().readTree(right.body()).path("result")
                        .path("status").path("state").asText());
    }

    @Test
    void agentCard_staysPublic_withoutBearer() throws Exception {
        startSecure(new InMemoryA2ATaskStore());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.port()
                        + HttpA2AServer.WELL_KNOWN_PATH))
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("secure-svc",
                A2AJson.mapper().readTree(response.body()).path("name").asText());
    }

    @Test
    void pushNotification_signedHeaders_verifiable() throws Exception {
        CompletableFuture<Map<String, String>> headers = new CompletableFuture<>();
        CompletableFuture<String> body = new CompletableFuture<>();
        com.sun.net.httpserver.HttpServer hook = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        hook.createContext("/", exchange -> {
            Map<String, String> collected = new java.util.HashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> collected.put(k, v.get(0)));
            headers.complete(collected);
            body.complete(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        hook.start();
        try {
            startSecure(new InMemoryA2ATaskStore());
            String hookUrl = "http://127.0.0.1:" + hook.getAddress().getPort() + "/";

            // Create a task, then attach the webhook (both need the bearer).
            JsonNodeHolder created = post("/", SEND_BODY, "Bearer " + TOKEN);
            String taskId = A2AJson.mapper().readTree(created.body())
                    .path("result").path("id").asText();
            String pushBody = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tasks/pushNotification/set\","
                    + "\"params\":{\"id\":\"" + taskId + "\","
                    + "\"pushNotificationConfig\":{\"url\":\"" + hookUrl + "\"}}}";
            JsonNodeHolder pushSet = post("/", pushBody, "Bearer " + TOKEN);
            assertEquals(200, pushSet.status());

            Map<String, String> receivedHeaders = headers.get(5, java.util.concurrent.TimeUnit.SECONDS);
            String receivedBody = body.get(5, java.util.concurrent.TimeUnit.SECONDS);

            // Signed push: the three integrity headers ride the webhook.
            String signature = receivedHeaders.get("X-signature");
            String timestamp = receivedHeaders.get("X-timestamp");
            String nonce = receivedHeaders.get("X-nonce");
            assertNotNull(signature, "signed push must carry X-Signature");
            assertNotNull(timestamp, "signed push must carry X-Timestamp");
            assertNotNull(nonce, "signed push must carry X-Nonce");

            // The receiver side verifies exactly like the primitive defines.
            A2ASecurity.PushEnvelope envelope = new A2ASecurity.PushEnvelope(
                    signature, timestamp, nonce, receivedBody);
            A2ASecurity.Verification verified = A2ASecurity.verifyPushSignature(
                    taskId, envelope, PUSH_SECRET, new A2ASecurity.ReplayCache());
            assertTrue(verified.valid(),
                    "push must verify against the shared secret: " + verified.reason());
            assertTrue(receivedBody.contains("completed"), receivedBody);
        } finally {
            hook.stop(0);
        }
    }

    @Test
    void sharedStore_survivesServerRestart_tasksGetStillFindsTask() throws Exception {
        InMemoryA2ATaskStore shared = new InMemoryA2ATaskStore();
        startSecure(shared);

        JsonNodeHolder created = post("/", SEND_BODY, "Bearer " + TOKEN);
        String taskId = A2AJson.mapper().readTree(created.body())
                .path("result").path("id").asText();
        assertFalse(taskId.isEmpty());

        // Same store, NEW server instance: the restart loses nothing.
        int port = server.port();
        server.stop();
        server = new HttpA2AServer(
                new AgentCard("secure-svc", "test", List.of("x"), "u", "1.0"),
                noopAgent(), port, null, shared, TOKEN, PUSH_SECRET);
        server.start();

        String getBody = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tasks/get\","
                + "\"params\":{\"id\":\"" + taskId + "\"}}";
        JsonNodeHolder fetched = post("/", getBody, "Bearer " + TOKEN);
        assertEquals(200, fetched.status());
        assertEquals(taskId, A2AJson.mapper().readTree(fetched.body())
                .path("result").path("id").asText());
        assertEquals("completed", A2AJson.mapper().readTree(fetched.body())
                .path("result").path("status").path("state").asText());
    }
}
