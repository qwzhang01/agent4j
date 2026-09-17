package io.github.qwzhang01.agent.mcp;

import io.github.qwzhang01.agent.core.event.BoundaryEvent;
import io.github.qwzhang01.agent.mcp.transport.McpTransport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Harness batch 7: the MCP boundary emits one {@link BoundaryEvent} per
 * tool call — success, schema refusal, transport failure, cancellation —
 * as a side channel that never breaks the call, with structure only
 * (server/tool ids, never args or results).
 */
class McpToolAdapterBoundaryEventTest {

    /** Straightforward scripted transport: one canned tools/call result. */
    static class ScriptedTransport implements McpTransport {
        volatile String callResult =
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}";
        volatile boolean dead;
        boolean open;

        @Override
        public void open() { open = true; }

        @Override
        public void send(String json) { }

        @Override
        public String receive(java.time.Duration timeout) throws IOException {
            if (dead) {
                throw new IOException("connection reset by peer");
            }
            String resp = callResult;
            if (resp == null) {
                throw new IOException("no response queued");
            }
            return resp;
        }

        @Override
        public String receive() throws IOException { return receive(null); }

        @Override
        public boolean isOpen() { return open; }

        @Override
        public void close() { open = false; }
    }

    private static McpClient connectedClient(ScriptedTransport t) throws IOException {
        McpServerDescriptor descriptor = McpServerDescriptor.stdio("srv", "echo");
        McpClient client = new McpClient(descriptor, t);
        // Hand-fed initialize handshake: the scripted transport answers the
        // initialize request with a tools-capable response.
        t.callResult = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"capabilities\":{\"tools\":{}}}}";
        client.connect();
        t.callResult =
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}";
        return client;
    }

    private static McpToolSchema anySchema() {
        String schemaJson = "{\"name\":\"echo\",\"description\":\"d\","
                + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}";
        try {
            return McpToolSchema.from(new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(schemaJson));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void successEmitsFinishedWithLatency() throws Exception {
        List<BoundaryEvent> events = new ArrayList<>();
        ScriptedTransport t = new ScriptedTransport();
        McpClient client = connectedClient(t);
        McpToolAdapter adapter = new McpToolAdapter(client, anySchema(), events::add);

        String out = adapter.execute(new com.fasterxml.jackson.databind.ObjectMapper()
                .createObjectNode());

        assertEquals("ok", out);
        assertEquals(1, events.size());
        BoundaryEvent.McpToolCallFinished e = assertInstanceOf(
                BoundaryEvent.McpToolCallFinished.class, events.get(0));
        assertEquals("srv", e.serverName());
        assertEquals("echo", e.toolName());
        assertTrue(e.latencyMs() >= 0);
        assertEquals(1, e.schemaVersion());
    }

    @Test
    void schemaRefusalEmitsFailedBeforeTheWire() throws Exception {
        List<BoundaryEvent> events = new ArrayList<>();
        ScriptedTransport t = new ScriptedTransport();
        McpClient client = connectedClient(t);
        McpToolAdapter adapter = new McpToolAdapter(client,
                schemaRequiringText(), events::add);

        assertThrows(IllegalArgumentException.class, () -> adapter.execute(
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()));

        assertEquals(1, events.size());
        BoundaryEvent.McpToolCallFailed e = assertInstanceOf(
                BoundaryEvent.McpToolCallFailed.class, events.get(0));
        assertEquals("SCHEMA_INVALID", e.failureKind());
        assertEquals("srv", e.serverName());
        assertEquals("echo", e.toolName());
    }

    @Test
    void transportFailureEmitsFailed() throws Exception {
        List<BoundaryEvent> events = new ArrayList<>();
        ScriptedTransport t = new ScriptedTransport();
        McpClient client = connectedClient(t);
        t.dead = true;
        McpToolAdapter adapter = new McpToolAdapter(client, anySchema(), events::add);

        assertThrows(io.github.qwzhang01.agent.core.tool.ToolException.class,
                () -> adapter.execute(new com.fasterxml.jackson.databind.ObjectMapper()
                        .createObjectNode()));

        assertEquals(1, events.size());
        BoundaryEvent.McpToolCallFailed e = assertInstanceOf(
                BoundaryEvent.McpToolCallFailed.class, events.get(0));
        assertEquals("TRANSPORT", e.failureKind());
    }

    @Test
    void throwingSinkIsSwallowedAndCountedNowhere() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        McpClient client = connectedClient(t);
        McpToolAdapter adapter = new McpToolAdapter(client, anySchema(),
                e -> { throw new IllegalStateException("sink boom"); });

        String out = adapter.execute(new com.fasterxml.jackson.databind.ObjectMapper()
                .createObjectNode());

        assertEquals("ok", out, "a throwing sink must never break the call");
    }

    @Test
    void legacyConstructorEmitsNothing() throws Exception {
        ScriptedTransport t = new ScriptedTransport();
        McpClient client = connectedClient(t);
        McpToolAdapter adapter = new McpToolAdapter(client, anySchema());

        String out = adapter.execute(new com.fasterxml.jackson.databind.ObjectMapper()
                .createObjectNode());

        assertEquals("ok", out);
    }

    /** A schema demanding a required text field, so a no-arg call is refused. */
    private static McpToolSchema schemaRequiringText() {
        String schemaJson = "{\"name\":\"echo\",\"description\":\"d\","
                + "\"inputSchema\":{\"type\":\"object\",\"required\":[\"text\"],"
                + "\"properties\":{\"text\":{\"type\":\"string\"}}}}";
        try {
            return McpToolSchema.from(new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(schemaJson));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
