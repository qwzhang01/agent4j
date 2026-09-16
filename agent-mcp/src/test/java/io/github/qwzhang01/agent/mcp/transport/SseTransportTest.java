package io.github.qwzhang01.agent.mcp.transport;

import com.sun.net.httpserver.HttpServer;
import io.github.qwzhang01.agent.mcp.McpServerDescriptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 6.2: SseTransport against a REAL HTTP server (JDK's built-in
 * HttpServer) speaking the SSE dialect — the closest thing to a third-party
 * interop test without depending on an external vendor: the transport must
 * handshake (endpoint event), POST requests, and deliver SSE data frames
 * through the real java.net.http stack.
 */
class SseTransportTest {

    private HttpServer server;
    private final ConcurrentLinkedQueue<String> postedBodies = new ConcurrentLinkedQueue<>();
    private volatile CountDownLatch streamReady = new CountDownLatch(1);

    private void startSseDialectServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<OutputStream> sseStreamRef = new AtomicReference<>();
        streamReady = new CountDownLatch(1);

        // GET: open the SSE channel. The handler must NOT return while the
        // stream is alive — JDK HttpServer closes the response body when the
        // handler returns, killing the SSE channel. Block until the test's
        // transport closes the stream (readLine throws).
        server.createContext("/sse", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            sseStreamRef.set(out);
            writeFrame(out, "endpoint", "/message?sessionId=" + UUID.randomUUID());
            streamReady.countDown();
            // Hold the handler open for the life of the stream; the read
            // below returns only when the client closes/cancels the GET.
            try {
                // Any client close is surfaced as a read error on the socket
                exchange.getRequestBody().read();
            } catch (IOException ignored) {
                // client went away
            }
        });

        // POST: receive the JSON-RPC, answer as an SSE data frame
        server.createContext("/message", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            postedBodies.add(body);
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            OutputStream out = sseStreamRef.get();
            String reply = "{\"jsonrpc\":\"2.0\",\"id\":" + extractId(body)
                    + ",\"result\":{\"echo\":\"" + authHeader + "\"}}";
            writeFrame(out, null, reply);
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });

        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    private static void writeFrame(OutputStream out, String event, String data) {
        try {
            if (event != null) {
                out.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
            }
            out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            // stream closed: test teardown race, ignore
        }
    }

    private static String extractId(String json) {
        int idx = json.indexOf("\"id\":");
        if (idx < 0) {
            return "0";
        }
        int start = idx + 5;
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        return json.substring(start, end);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String sseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/sse";
    }

    @Test
    void handshake_discoversEndpointAndDeliversResponses() throws Exception {
        startSseDialectServer();
        McpServerDescriptor descriptor = McpServerDescriptor.sse("remote-test", sseUrl());
        SseTransport t = new SseTransport(descriptor, HttpClient.newHttpClient());

        t.open();
        assertTrue(t.isOpen());

        // Client-to-server: POST a JSON-RPC request
        String request = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}";
        t.send(request);
        assertEquals(1, postedBodies.size());
        assertEquals(request, postedBodies.peek());

        // Server-to-client: the SSE data frame with the matching response
        String response = t.receive();
        assertTrue(response.contains("\"id\":7"), "response must carry the request id, got: "
                + response);
        assertTrue(response.contains("echo"), "response payload must survive the trip");

        t.close();
        assertFalse(t.isOpen());
    }

    @Test
    void auth_headerFlowsOnBothDirections() throws Exception {
        startSseDialectServer();
        McpServerDescriptor descriptor = McpServerDescriptor.sse("auth-test", sseUrl());
        SseTransport t = new SseTransport(descriptor, HttpClient.newHttpClient(),
                null, () -> "Bearer secret-token");

        t.open();
        t.send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}");

        String response = t.receive();
        // The fake server echoes the Authorization header it saw on the POST;
        // the auth adapter must have stamped it.
        assertTrue(response.contains("Bearer secret-token"),
                "auth header must reach the server, got: " + response);
        t.close();
    }

    @Test
    void sendBeforeOpen_isRejected() {
        McpServerDescriptor descriptor = McpServerDescriptor.sse("nowhere",
                "http://127.0.0.1:1/sse");
        SseTransport t = new SseTransport(descriptor, HttpClient.newHttpClient());
        assertThrows(IOException.class, () -> t.send("{}"));
    }

    @Test
    void connectToDeadServer_failsLoud() {
        McpServerDescriptor descriptor = McpServerDescriptor.sse("dead",
                "http://127.0.0.1:1/sse");
        SseTransport t = new SseTransport(descriptor, HttpClient.newHttpClient(),
                java.time.Duration.ofMillis(500), null);
        IOException ex = assertThrows(IOException.class, t::open);
        assertTrue(ex.getMessage().contains("SSE connect"),
                "failure must say it was the connect, got: " + ex.getMessage());
    }

    @Test
    void stdioDescriptor_isRejected() {
        McpServerDescriptor descriptor = McpServerDescriptor.stdio("local", "echo", "hi");
        assertThrows(IllegalArgumentException.class,
                () -> new SseTransport(descriptor, HttpClient.newHttpClient()));
    }
}
