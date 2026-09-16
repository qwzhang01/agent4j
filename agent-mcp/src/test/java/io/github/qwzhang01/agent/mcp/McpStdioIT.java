package io.github.qwzhang01.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.mcp.transport.StdioTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 8.3 MCP Integration Profile: the FULL lifecycle against a REAL
 * subprocess MCP server (a Python script speaking the actual wire protocol),
 * not mocks.
 * <p>
 * Covered path: connect (initialize handshake) → listTools → callTool →
 * server crash (crash-on-demand) → {@link ManagedMcpClient} auto-restart →
 * retry succeeds. This is the production topology: agent4j's self-healing
 * client talking stdio JSON-RPC to an external process.
 * <p>
 * Runs in the default suite (needs only python3 + the script; both are
 * versioned in the repo). Tagged {@code mcp-it} so CI can select or exclude
 * the profile explicitly.
 */
@Tag(McpStdioIT.TAG)
class McpStdioIT {

    static final String TAG = "mcp-it";

    /** Static probe cache: python3 existence does not change mid-run. */
    private static boolean availabilityChecked;
    private static boolean pythonAvailable;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void requirePython() {
        if (!availabilityChecked) {
            pythonAvailable = probePython();
            availabilityChecked = true;
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(pythonAvailable,
                "python3 not on PATH — MCP stdio integration profile skipped");
    }

    private static boolean probePython() {
        try {
            Process p = new ProcessBuilder("python3", "--version")
                    .redirectErrorStream(true)
                    .start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static Path serverScript() {
        return Path.of("src", "test", "resources", "mcp-it", "py_mcp_server.py");
    }

    private ManagedMcpClient newClient() {
        McpServerDescriptor descriptor = McpServerDescriptor.stdio(
                "py-it-server",
                "python3", serverScript().toAbsolutePath().toString());
        return new ManagedMcpClient(
                descriptor,
                () -> new StdioTransport(descriptor.command()),
                McpRestartPolicy.defaults());
    }

    @Test
    @DisplayName("full lifecycle: handshake, listTools, callTool over a real subprocess")
    void fullLifecycleAgainstRealServer() throws Exception {
        ManagedMcpClient client = newClient();
        try {
            client.connect();

            List<McpToolSchema> tools = client.listTools();
            assertTrue(tools.size() >= 2, "server must expose echo + add, got " + tools.size());
            assertTrue(tools.stream().anyMatch(t -> t.name().equals("echo")));
            assertTrue(tools.stream().anyMatch(t -> t.name().equals("add")));

            ObjectNode args = mapper.createObjectNode();
            args.put("a", 20);
            args.put("b", 22);
            String result = client.callTool("add", args);
            assertEquals("42", result, "add(20,22) must return 42 via the real server");

            assertTrue(client.isHealthy(), "ping must answer after the lifecycle");
        } finally {
            client.disconnect();
        }
    }

    @Test
    @DisplayName("self-healing: crashed subprocess is restarted and the call retried")
    void crashedServerIsRestartedAndRetried() throws Exception {
        ManagedMcpClient client = newClient();
        try {
            client.connect();
            assertEquals(0, client.getRestartCount(), "no restarts before the crash");

            // Sanity: a normal call succeeds first.
            ObjectNode hello = mapper.createObjectNode();
            hello.put("text", "hello");
            assertEquals("echo:hello", client.callTool("echo", hello));

            // Simulate the crash PHYSICALLY: kill the subprocess out-of-band.
            // (A crash injected via call arguments would be replayed verbatim
            // by the retry and kill the restarted server again.)
            ((StdioTransport) client.getTransport()).destroyForcibly();

            // The next call hits a dead transport, triggers restart + retry.
            ObjectNode world = mapper.createObjectNode();
            world.put("text", "world");
            String result = client.callTool("echo", world);

            assertEquals("echo:world", result,
                    "the retried call must succeed against the restarted server");
            assertEquals(1, client.getRestartCount(),
                    "exactly one restart must have been consumed");
            assertTrue(client.isHealthy(), "the fresh subprocess must answer ping");
        } finally {
            client.disconnect();
        }
    }

    @Test
    @DisplayName("unknown tool surfaces the server's protocol error, no restart")
    void unknownToolIsAProtocolErrorNotARestart() throws Exception {
        ManagedMcpClient client = newClient();
        try {
            client.connect();

            ObjectNode args = mapper.createObjectNode();
            String thrown = org.junit.jupiter.api.Assertions.assertThrows(
                    IOException.class,
                    () -> client.callTool("no-such-tool", args))
                    .getMessage();
            assertTrue(thrown.contains("no-such-tool") || thrown.contains("-32602")
                            || thrown.toLowerCase().contains("unknown"),
                    "protocol error must surface the tool name, got: " + thrown);
            assertEquals(0, client.getRestartCount(),
                    "server is alive: a protocol error must NOT trigger a restart");
        } finally {
            client.disconnect();
        }
    }
}
