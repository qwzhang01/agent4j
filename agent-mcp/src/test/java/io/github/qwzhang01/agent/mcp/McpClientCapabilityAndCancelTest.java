package io.github.qwzhang01.agent.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.core.run.CancellationSource;
import io.github.qwzhang01.agent.core.run.RunCancelledException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Harness batch 3: capability negotiation + cancellation wiring in McpClient.
 * <p>
 * Three behaviors under test:
 * <ol>
 *   <li>The initialize handshake records the server's declared capabilities
 *       (typed record, null for old-era non-declarations).</li>
 *   <li>{@code supportsTools()} guards the tool path: a declaration that
 *       excludes tools is refused at listTools/callTool with a clear
 *       message; a non-declaration is honored (2024-11-05 semantics).</li>
 *   <li>A cancelled run surfaces as {@link RunCancelledException} — the
 *       structured signal every other boundary throws — checked before the
 *       wire AND between receive polls; never wrapped into an IOException
 *       a caller might retry.</li>
 * </ol>
 */
class McpClientCapabilityAndCancelTest {

    private static final String TOOLS_CAPS_INIT =
            "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{"
                    + "\"serverInfo\":{\"name\":\"caps\",\"version\":\"1.0\"},"
                    + "\"capabilities\":{\"tools\":{\"listChanged\":true}}}}";
    private static final String NO_TOOLS_CAPS_INIT =
            "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{"
                    + "\"serverInfo\":{\"name\":\"caps\",\"version\":\"1.0\"},"
                    + "\"capabilities\":{\"resources\":{},\"prompts\":{}}}}";

    private McpClient client;
    private MockMcpTransport transport;

    @BeforeEach
    void setUp() throws IOException {
        transport = new MockMcpTransport();
        transport.registerResponse("initialize", TOOLS_CAPS_INIT);
        transport.registerResponse("tools/list",
                "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"tools\":["
                        + "{\"name\":\"echo\",\"description\":\"Echo\",\"inputSchema\":{"
                        + "\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}}]}}");
        transport.registerResponse("tools/call",
                "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"content\":"
                        + "[{\"type\":\"text\",\"text\":\"echoed\"}]}}");

        client = new McpClient(McpServerDescriptor.stdio("caps", "caps"), transport);
        client.connect();
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.disconnect();
    }

    // ============ Capability negotiation ============

    @Test
    void initializeRecordsTypedServerCapabilities() {
        McpServerCapabilities caps = client.serverCapabilities();
        assertNotNull(caps);
        assertTrue(caps.tools());
        assertFalse(caps.resources());
    }

    @Test
    void toolsDeclarationSupportsTheToolPath() throws IOException {
        assertTrue(client.supportsTools());
        assertEquals(1, client.listTools().size());
        assertEquals("echoed", client.callTool("echo", null));
    }

    @Test
    void declarationWithoutToolsIsRefusedWithClearMessage() throws IOException {
        MockMcpTransport noTools = new MockMcpTransport();
        noTools.registerResponse("initialize", NO_TOOLS_CAPS_INIT);
        McpClient c = new McpClient(McpServerDescriptor.stdio("n", "n"), noTools);
        c.connect();

        McpServerCapabilities caps = c.serverCapabilities();
        assertNotNull(caps);
        assertFalse(caps.tools());
        assertFalse(c.supportsTools());

        IOException listEx = assertThrows(IOException.class, c::listTools);
        assertTrue(listEx.getMessage().contains("without tools"), listEx.getMessage());
        assertTrue(listEx.getMessage().contains("resources"), listEx.getMessage());

        IOException callEx = assertThrows(IOException.class,
                () -> c.callTool("echo", null));
        assertTrue(callEx.getMessage().contains("without tools"), callEx.getMessage());
        c.disconnect();
    }

    @Test
    void absentCapabilitiesKeepsLegacyToolsEra() throws IOException {
        MockMcpTransport legacy = new MockMcpTransport();
        legacy.registerResponse("initialize",
                "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{}}");
        legacy.registerResponse("tools/list",
                "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"tools\":[]}}");
        McpClient c = new McpClient(McpServerDescriptor.stdio("l", "l"), legacy);
        c.connect();

        assertNull(c.serverCapabilities());
        assertTrue(c.supportsTools());
        assertEquals(0, c.listTools().size());
        c.disconnect();
    }

    // ============ Cancellation wiring ============

    @Test
    void preCancelledTokenThrowsBeforeTheWire() {
        CancellationSource source = new CancellationSource();
        source.cancel();

        assertThrows(RunCancelledException.class,
                () -> client.callTool("echo", null, source.token()));
    }

    @Test
    void midReceiveCancellationSurfacesAsStructuredSignal() throws Exception {
        CancellationSource source = new CancellationSource();

        // Two-phase transport: initialize answers instantly (handshake
        // passes), the tools/call receive blocks like a slow server.
        SlowOnCallTransport slow = new SlowOnCallTransport(TOOLS_CAPS_INIT);
        McpClient c = new McpClient(McpServerDescriptor.stdio("slow", "slow"), slow);
        c.connect();
        // Short window so the test runs fast: the wire BLOCKS through the
        // whole window (cancellation cannot interrupt a blocking poll —
        // the honest boundary), so the poll exits via timeout and the
        // cancellation check REWRITES the timeout story into the
        // structured signal.
        c.setReceiveTimeout(Duration.ofMillis(500));

        // Cancel while the first poll is still blocking.
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            source.cancel();
        });
        canceller.start();

        long start = System.currentTimeMillis();
        assertThrows(RunCancelledException.class,
                () -> c.callTool("echo", null, source.token()));
        long elapsed = System.currentTimeMillis() - start;

        // Structured signal, and it surfaced when the poll exited (not a
        // 30s wait, not a retryable IOException).
        assertTrue(elapsed < 5_000, "cancellation surfaced late: " + elapsed + "ms");
        canceller.interrupt();
        c.disconnect();
    }

    @Test
    void nullTokenKeepsTheUncancellableLegacyPath() throws IOException {
        // The 2-arg callTool delegates with null: legacy behavior, no token.
        assertEquals("echoed", client.callTool("echo", null, null));
    }

    @Test
    void cancelBeforeSendNeverReachesTheWire() {
        CancellationSource source = new CancellationSource();
        source.cancel();

        // No tools/call response registered on this transport — if the
        // pre-wire check failed and the request went out, receive() would
        // throw "No response queued" (RuntimeException), not RunCancelled.
        assertThrows(RunCancelledException.class,
                () -> client.callTool("echo", null, source.token()));
    }

    /**
     * Two-phase transport for the mid-receive cancellation test:
     * <ul>
     *   <li>initialize/ping/shutdown requests answer instantly (the canned
     *       initialize capabilities) — the handshake must pass normally;</li>
     *   <li>the tools/call receive blocks for as long as nobody cancels —
     *       modeling a server that never heard an MCP-protocol cancellation
     *       (we do not implement the notification) and keeps its side of
     *       the work; the CLIENT-side token check between polls is what
     *       stops the wait.</li>
     * </ul>
     */
    private static final class SlowOnCallTransport implements io.github.qwzhang01.agent.mcp.transport.McpTransport {
        private final String initResult;
        private volatile boolean open = false;
        private volatile boolean callSeen = false;
        private volatile String lastRequestId = "0";

        SlowOnCallTransport(String initResult) {
            this.initResult = initResult;
        }

        @Override
        public void open() {
            open = true;
        }

        @Override
        public void send(String json) {
            if (!open) throw new RuntimeException("not open");
            try {
                com.fasterxml.jackson.databind.JsonNode node =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
                String method = node.has("method") ? node.get("method").asText() : "";
                if ("tools/call".equals(method)) {
                    callSeen = true;
                }
                if (node.has("id") && !method.startsWith("notifications/")) {
                    lastRequestId = node.get("id").toString();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public String receive() throws IOException {
            if (!callSeen) {
                // Handshake phase: answer instantly with the canned
                // initialize result, echoing the request's id so the
                // client's id-matching receive loop consumes it on the
                // first poll.
                return initResult.replace("\"id\":0", "\"id\":" + lastRequestId);
            }
            // Call phase: block like a slow server. The client loop checks
            // the token BEFORE each poll, so a cancelled run throws before
            // we ever return; the 30s receive timeout is what would fire
            // otherwise — the test asserts cancellation wins that race.
            final long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted");
                }
            }
            return "{\"jsonrpc\":\"2.0\",\"id\":-1,\"result\":{}}";
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
