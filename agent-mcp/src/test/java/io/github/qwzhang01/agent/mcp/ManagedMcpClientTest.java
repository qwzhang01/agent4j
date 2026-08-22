package io.github.qwzhang01.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.mcp.transport.McpTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 10 process management tests: {@link ManagedMcpClient} auto-restart behavior.
 * <p>
 * Uses {@link CrashyTransport}, a scripted fake whose "generations" can be told
 * to die on their first tools/call -- simulating a crashed server subprocess.
 * The transport factory builds a fresh generation per (re)connection, exactly
 * like {@code () -> new StdioTransport(command)} in production.
 */
class ManagedMcpClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void resetCrashyState() {
        CrashyTransport.reset();
    }

    private static McpRestartPolicy noCooldown(int maxRestarts) {
        return new McpRestartPolicy(maxRestarts, 0, 60_000);
    }

    // ============ Auto-recovery ============

    @Test
    void callTool_recoversAfterServerCrash() throws IOException {
        CrashyTransport.crashGenerations = Set.of(1);  // only the 1st process crashes

        ManagedMcpClient client = new ManagedMcpClient(
                McpServerDescriptor.stdio("crashy", "crashy"),
                CrashyTransport::new, noCooldown(3));
        client.connect();

        ObjectNode args = MAPPER.createObjectNode().put("text", "hello");

        // gen 1 crashes on tools/call -> detected dead -> restart (gen 2) -> retry succeeds
        String result = client.callTool("echo", args);

        assertEquals("echoed: hello", result);
        assertEquals(1, client.getRestartCount());
        assertTrue(client.getLastRestartAt() > 0);
        assertTrue(client.isHealthy());
        client.disconnect();
    }

    @Test
    void listTools_alsoRecoversAfterCrash() throws IOException {
        // make the crash trigger on listTools instead: generation 1 dies on tools/call,
        // but we never call tools here -- use a crash on the FIRST request after connect.
        // Simplest scripted variant: gen 1 dies on tools/list via crashGenerations too.
        CrashyTransport.crashGenerations = Set.of(1);
        CrashyTransport.crashOnMethods = Set.of("tools/list");

        ManagedMcpClient client = new ManagedMcpClient(
                McpServerDescriptor.stdio("crashy", "crashy"),
                CrashyTransport::new, noCooldown(3));
        client.connect();

        // gen 1 crashes on tools/list -> restart (gen 2) -> retry succeeds
        var tools = client.listTools();

        assertEquals(1, tools.size());
        assertEquals("echo", tools.get(0).name());
        assertEquals(1, client.getRestartCount());
        client.disconnect();
    }

    // ============ Restart storm protection ============

    @Test
    void callTool_budgetExhausted_stopsRestarting() throws IOException {
        CrashyTransport.crashGenerations = Set.of(1, 2, 3, 4, 5);  // every generation crashes

        ManagedMcpClient client = new ManagedMcpClient(
                McpServerDescriptor.stdio("crashy", "crashy"),
                CrashyTransport::new, noCooldown(2));
        client.connect();

        ObjectNode args = MAPPER.createObjectNode().put("text", "hello");

        // call 1: crash -> restart #1 -> retry crashes again -> throw
        assertThrows(IOException.class, () -> client.callTool("echo", args));
        assertEquals(1, client.getRestartCount());

        // call 2: dead transport -> restart #2 -> retry crashes again -> throw
        assertThrows(IOException.class, () -> client.callTool("echo", args));
        assertEquals(2, client.getRestartCount());

        // call 3: budget exhausted (2/2) -> NO restart, original error surfaces
        assertThrows(IOException.class, () -> client.callTool("echo", args));
        assertEquals(2, client.getRestartCount());
        client.disconnect();
    }

    @Test
    void callTool_cooldownActive_noImmediateRestart() throws IOException {
        CrashyTransport.crashGenerations = Set.of(1, 2);  // gen 1 and gen 2 crash

        ManagedMcpClient client = new ManagedMcpClient(
                McpServerDescriptor.stdio("crashy", "crashy"),
                CrashyTransport::new, new McpRestartPolicy(5, 60_000, 60_000));
        client.connect();

        ObjectNode args = MAPPER.createObjectNode().put("text", "hello");

        // call 1: crash -> restart #1 (gen 2) -> retry crashes too -> throw
        assertThrows(IOException.class, () -> client.callTool("echo", args));
        assertEquals(1, client.getRestartCount());

        // call 2 immediately: cooldown (60s) still active -> NO restart, error surfaces
        assertThrows(IOException.class, () -> client.callTool("echo", args));
        assertEquals(1, client.getRestartCount());
        client.disconnect();
    }

    // ============ No restart when server is alive ============

    @Test
    void callTool_protocolError_doesNotRestart() throws IOException {
        MockMcpTransport mock = new MockMcpTransport();
        mock.registerResponse("initialize",
                "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"serverInfo\":{\"name\":\"mock\",\"version\":\"1\"}}}");
        mock.registerResponse("tools/call",
                "{\"jsonrpc\":\"2.0\",\"id\":0,\"error\":{\"code\":-32602,\"message\":\"Invalid params\"}}");

        ManagedMcpClient client = new ManagedMcpClient(
                McpServerDescriptor.stdio("mock", "mock"),
                () -> mock, noCooldown(3));
        client.connect();

        // Server is ALIVE (transport open) -- protocol-level error.
        // Reconnecting cannot help, so no restart must happen.
        assertThrows(IOException.class, () -> client.callTool("echo", null));
        assertEquals(0, client.getRestartCount());
        client.disconnect();
    }

    // ============ Health & reconnect ============

    @Test
    void isHealthy_trueWhenAlive_falseAfterCrash() throws IOException {
        ManagedMcpClient client = new ManagedMcpClient(
                McpServerDescriptor.stdio("crashy", "crashy"),
                CrashyTransport::new, noCooldown(3));
        client.connect();

        assertTrue(client.isHealthy());  // process alive + ping answered

        // simulate an external kill (e.g. OOM killer)
        ((CrashyTransport) client.getTransport()).simulateCrash();
        assertFalse(client.isHealthy());
        client.disconnect();
    }

    @Test
    void reconnect_reEstablishesConnection() throws IOException {
        ManagedMcpClient client = new ManagedMcpClient(
                McpServerDescriptor.stdio("crashy", "crashy"),
                CrashyTransport::new, noCooldown(3));
        client.connect();

        client.reconnect();  // manual: fresh transport + full handshake

        assertTrue(client.isConnected());
        assertEquals("echoed: hello",
                client.callTool("echo", MAPPER.createObjectNode().put("text", "hello")));
        client.disconnect();
    }

    // ============ Scripted crashing transport ============

    /**
     * Fake transport with "generations": the factory builds a fresh one per
     * (re)connection. Generations listed in {@link #crashGenerations} die
     * (process-crash semantics: open=false, no response) when they receive
     * a method listed in {@link #crashOnMethods} (default: tools/call).
     */
    static class CrashyTransport implements McpTransport {

        static final AtomicInteger CREATED = new AtomicInteger();
        static volatile Set<Integer> crashGenerations = Set.of();
        static volatile Set<String> crashOnMethods = Set.of("tools/call");

        final int generation = CREATED.incrementAndGet();
        private volatile boolean open = false;
        private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();

        static void reset() {
            CREATED.set(0);
            crashGenerations = Set.of();
            crashOnMethods = Set.of("tools/call");
        }

        void simulateCrash() {
            open = false;
            queue.clear();
        }

        @Override
        public void open() {
            open = true;
        }

        @Override
        public void send(String json) throws IOException {
            if (!open) {
                // same contract as StdioTransport: dead process -> IOException
                throw new IOException("Transport is not open (process dead)");
            }
            var node = new ObjectMapper().readTree(json);
            String method = node.has("method") ? node.get("method").asText() : "";
            if (method.startsWith("notifications/")) {
                return;  // fire-and-forget
            }

            // scripted crash: dies right before answering
            if (crashGenerations.contains(generation) && crashOnMethods.contains(method)) {
                simulateCrash();
                return;
            }

            long id = node.has("id") ? node.get("id").asLong() : 0;
            String result = switch (method) {
                case "initialize" ->
                        "{\"serverInfo\":{\"name\":\"crashy-server\",\"version\":\"1.0\"}}";
                case "tools/list" ->
                        "{\"tools\":[{\"name\":\"echo\",\"description\":\"Echo\",\"inputSchema\":{}}]}";
                case "tools/call" ->
                        "{\"content\":[{\"type\":\"text\",\"text\":\"echoed: hello\"}]}";
                default -> "{}";  // ping, shutdown, ... -> empty result
            };
            queue.add("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}");
        }

        @Override
        public String receive() throws IOException {
            if (!open) {
                throw new IOException("MCP subprocess closed its stdout (simulated crash)");
            }
            String resp = queue.poll();
            if (resp == null) {
                throw new IOException("No response queued");
            }
            return resp;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
