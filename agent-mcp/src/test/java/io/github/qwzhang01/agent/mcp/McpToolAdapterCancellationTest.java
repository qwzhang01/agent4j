package io.github.qwzhang01.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.run.CancellationSource;
import io.github.qwzhang01.agent.core.run.RunCancelledException;
import io.github.qwzhang01.agent.core.run.RunContext;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Harness batch 3: cancellation flows from {@link RunContext} through
 * {@link DefaultToolExecutor} (which hands ctx down to every tool) into
 * {@link McpToolAdapter}'s ctx-aware execute, and reaches the wire at
 * {@link McpClient#callTool(String, com.fasterxml.jackson.databind.JsonNode,
 * io.github.qwzhang01.agent.core.run.CancellationToken)}.
 * <p>
 * The exception-type contract: a cancellation hit must surface as
 * {@link RunCancelledException} — NOT wrapped into ToolException — so the
 * loop's event contract (TOOL_FAILURE vs cancelled) keeps working.
 */
class McpToolAdapterCancellationTest {

    private MockMcpTransport transport;
    private McpClient client;

    @BeforeEach
    void setUp() throws IOException {
        transport = new MockMcpTransport();
        transport.registerResponse("initialize",
                "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{"
                        + "\"serverInfo\":{\"name\":\"echo-server\"}}} ");
        transport.registerResponse("tools/list",
                "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"tools\":["
                        + "{\"name\":\"echo\",\"description\":\"Echoes text\",\"inputSchema\":{"
                        + "\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}}"
                        + "]}}");
        transport.registerResponse("tools/call",
                "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"content\":"
                        + "[{\"type\":\"text\",\"text\":\"echoed: hello\"}]}}");

        client = new McpClient(McpServerDescriptor.stdio("echo-server", "mock"), transport);
        client.connect();
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.disconnect();
    }

    @Test
    void ctxTokenFlowsThroughDefaultToolExecutorToTheWire() throws IOException {
        McpToolSchema schema = client.listTools().get(0);
        McpToolAdapter adapter = new McpToolAdapter(client, schema);

        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(adapter);
        DefaultToolExecutor executor = new DefaultToolExecutor(registry);

        // A live (uncancelled) token: the call proceeds normally.
        CancellationSource source = new CancellationSource();
        RunContext ctx = RunContext.builder()
                .cancellationToken(source.token())
                .build();

        ObjectMapper mapper = new ObjectMapper();
        String result = executor.execute(
                ToolCall.of("id-1", "echo",
                        mapper.createObjectNode().put("text", "hello").toString()),
                ctx);

        assertEquals("echoed: hello", result);
    }

    @Test
    void cancelledCtxThrowsStructuredSignalNotToolException() throws IOException {
        McpToolSchema schema = client.listTools().get(0);
        McpToolAdapter adapter = new McpToolAdapter(client, schema);

        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(adapter);
        DefaultToolExecutor executor = new DefaultToolExecutor(registry);

        CancellationSource source = new CancellationSource();
        source.cancel();
        RunContext ctx = RunContext.builder()
                .cancellationToken(source.token())
                .build();

        ObjectMapper mapper = new ObjectMapper();
        RunCancelledException ex = assertThrows(RunCancelledException.class,
                () -> executor.execute(
                        ToolCall.of("id-1", "echo",
                                mapper.createObjectNode().put("text", "hello").toString()),
                        ctx));

        // The structured signal survives the whole chain: never wrapped
        // into ToolException / IOException (a caller might retry those).
        assertNotNull(ex);
    }

    @Test
    void legacyNoCtxPathStaysUncancellableBitForBit() throws IOException {
        McpToolSchema schema = client.listTools().get(0);
        McpToolAdapter adapter = new McpToolAdapter(client, schema);

        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(adapter);
        DefaultToolExecutor executor = new DefaultToolExecutor(registry);

        // Legacy executor path (no ctx): executes normally.
        ObjectMapper mapper = new ObjectMapper();
        String result = executor.execute(
                ToolCall.of("id-1", "echo",
                        mapper.createObjectNode().put("text", "hello").toString()));

        assertEquals("echoed: hello", result);
    }

    @Test
    void directCtxExecuteHonorsTheToken() throws IOException {
        McpToolSchema schema = client.listTools().get(0);
        McpToolAdapter adapter = new McpToolAdapter(client, schema);

        CancellationSource source = new CancellationSource();
        source.cancel();
        RunContext ctx = RunContext.builder()
                .cancellationToken(source.token())
                .build();

        ObjectMapper mapper = new ObjectMapper();
        assertThrows(RunCancelledException.class,
                () -> adapter.execute(
                        mapper.createObjectNode().put("text", "hello"), ctx));

        // And the ctx-free legacy overload is untouched by the token.
        assertEquals("echoed: hello",
                adapter.execute(mapper.createObjectNode().put("text", "hello")));
    }

    @Test
    void ctxWithoutTokenKeepsLegacyBehavior() throws IOException {
        McpToolSchema schema = client.listTools().get(0);
        McpToolAdapter adapter = new McpToolAdapter(client, schema);

        // ctx present but no token: legacy uncancellable path.
        RunContext ctx = RunContext.create();
        ObjectMapper mapper = new ObjectMapper();
        assertEquals("echoed: hello",
                adapter.execute(mapper.createObjectNode().put("text", "hello"), ctx));
    }
}
