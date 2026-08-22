package io.github.qwzhang01.agent.model.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal HTTP API mock for protocol-format tests, built on the JDK HttpServer.
 * <p>
 * Usage:
 * <pre>{@code
 * try (MockApiServer api = new MockApiServer()) {
 *     api.enqueue("/chat/completions", 200, "{\"choices\":[...]}");
 *     var client = new OpenAiModelClient(api.baseUrl(), "test-key", "gpt-4o-mini");
 *     client.chat(request);
 *     String bodySent = api.capturedBody("/chat/completions");
 *     // ... assert on the wire format
 * }
 * }</pre>
 * Responses are consumed in FIFO order per path; unscripted paths return 404.
 */
public class MockApiServer implements AutoCloseable {

    private record ResponseSpec(int status, String body) {
    }

    private final HttpServer server;
    private final Map<String, List<ResponseSpec>> scriptedResponses = new ConcurrentHashMap<>();
    private final Map<String, List<String>> capturedBodies = new ConcurrentHashMap<>();

    public MockApiServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String captured = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            capturedBodies.computeIfAbsent(path, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(captured);

            List<ResponseSpec> queue = scriptedResponses.get(path);
            ResponseSpec response = queue != null && !queue.isEmpty() ? queue.remove(0) : null;
            if (response == null) {
                respond(exchange, 404, "{\"error\":\"no scripted response for " + path + "\"}");
            } else {
                respond(exchange, response.status(), response.body());
            }
        });
        server.start();
    }

    // ============ Scripting ============

    /**
     * Enqueues a response for the given path (FIFO per path).
     */
    public void enqueue(String path, int status, String responseBody) {
        scriptedResponses.computeIfAbsent(path, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new ResponseSpec(status, responseBody));
    }

    /**
     * Enqueues a 200 response for the given path (FIFO per path).
     */
    public void enqueue(String path, String responseBody) {
        enqueue(path, 200, responseBody);
    }

    // ============ Capture ============

    /**
     * Returns the request bodies captured for the given path, in arrival order.
     */
    public List<String> capturedBodies(String path) {
        return capturedBodies.getOrDefault(path, List.of());
    }

    /**
     * Returns the last request body captured for the given path.
     */
    public String capturedBody(String path) {
        List<String> bodies = capturedBodies(path);
        return bodies.isEmpty() ? null : bodies.get(bodies.size() - 1);
    }

    /**
     * Returns the number of requests received for the given path.
     */
    public int requestCount(String path) {
        return capturedBodies(path).size();
    }

    // ============ Addressing ============

    /**
     * Base URL of the mock server, e.g. "http://localhost:12345".
     */
    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ============ Internals ============

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }
}
