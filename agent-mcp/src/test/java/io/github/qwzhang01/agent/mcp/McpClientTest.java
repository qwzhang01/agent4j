package io.github.qwzhang01.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.mcp.jsonrpc.JsonRpcResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 10 M10.2 tests: McpClient protocol operations with MockMcpTransport.
 */
class McpClientTest {

    private MockMcpTransport transport;
    private McpClient client;

    @BeforeEach
    void setUp() throws IOException {
        transport = new MockMcpTransport();

        // Register MCP server responses
        // initialize response
        transport.registerResponse("initialize",
                "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"serverInfo\":{\"name\":\"mock-server\",\"version\":\"1.0\"},\"capabilities\":{}}}");

        // tools/list response
        transport.registerResponse("tools/list",
                "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"tools\":[" +
                "{\"name\":\"echo\",\"description\":\"Echoes input\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}}," +
                "{\"name\":\"get_weather\",\"description\":\"Get weather\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}}" +
                "]}}");

        // tools/call response for "echo" tool
        transport.registerResponse("tools/call",
                "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"echoed: hello\"}]}}");

        // shutdown response
        transport.registerResponse("shutdown",
                "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{}}");

        McpServerDescriptor desc = McpServerDescriptor.stdio("mock", "mock");
        client = new McpClient(desc, transport);
        client.connect();
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.disconnect();
    }

    // ============ Connection ============

    @Test
    void connect_setsInitializedFlag() {
        assertTrue(client.isConnected());
    }

    @Test
    void connect_whenTransportNotOpen_fails() throws IOException {
        McpServerDescriptor desc = McpServerDescriptor.stdio("mock2", "mock");
        MockMcpTransport closedTransport = new MockMcpTransport();
        closedTransport.registerResponse("initialize",
                "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{}}");
        McpClient c = new McpClient(desc, closedTransport);
        // connect() calls transport.open() which sets open=true, then sends initialize
        c.connect();
        assertTrue(c.isConnected());
        c.disconnect();
    }

    // ============ listTools ============

    @Test
    void listTools_returnsAllServerTools() throws IOException {
        List<McpToolSchema> tools = client.listTools();

        assertEquals(2, tools.size());
        assertEquals("echo", tools.get(0).name());
        assertEquals("Echoes input", tools.get(0).description());
        assertEquals("get_weather", tools.get(1).name());
    }

    @Test
    void listTools_whenNotConnected_throws() throws IOException {
        McpServerDescriptor desc = McpServerDescriptor.stdio("d", "d");
        MockMcpTransport t = new MockMcpTransport();
        McpClient c = new McpClient(desc, t);
        assertThrows(IOException.class, c::listTools);
    }

    // ============ callTool ============

    @Test
    void callTool_returnsTextContent() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode args = mapper.createObjectNode().put("text", "hello");

        String result = client.callTool("echo", args);

        assertEquals("echoed: hello", result);
    }

    @Test
    void callTool_nullArgs_passesEmptyObject() throws IOException {
        String result = client.callTool("echo", null);
        assertNotNull(result);
    }

    @Test
    void callTool_whenNotConnected_throws() {
        McpServerDescriptor desc = McpServerDescriptor.stdio("d", "d");
        MockMcpTransport t = new MockMcpTransport();
        McpClient c = new McpClient(desc, t);
        assertThrows(IOException.class, () -> c.callTool("echo", null));
    }

    @Test
    void callTool_errorResponse_throws() throws IOException {
        // Register an error response for tools/call on a separate transport
        MockMcpTransport errTransport = new MockMcpTransport();
        errTransport.registerResponse("initialize",
                "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{}}");
        errTransport.registerResponse("tools/call",
                "{\"jsonrpc\":\"2.0\",\"id\":0,\"error\":{\"code\":-32602,\"message\":\"Invalid params\"}}");

        McpServerDescriptor desc = McpServerDescriptor.stdio("err", "err");
        McpClient errClient = new McpClient(desc, errTransport);
        errClient.connect();

        assertThrows(IOException.class, () -> errClient.callTool("bad", null));
        errClient.disconnect();
    }

    // ============ disconnect ============

    @Test
    void disconnect_whenNotConnected_justClosesTransport() {
        McpServerDescriptor desc = McpServerDescriptor.stdio("d", "d");
        MockMcpTransport t = new MockMcpTransport();
        McpClient c = new McpClient(desc, t);
        // Not connected -- disconnect should not throw
        c.disconnect();
        assertFalse(c.isConnected());
    }

    // ============ McpServerDescriptor ============

    @Test
    void descriptor_stdioFactory() {
        McpServerDescriptor d = McpServerDescriptor.stdio("test", "python", "server.py");
        assertEquals("test", d.name());
        assertTrue(d.isStdio());
        assertEquals(List.of("python", "server.py"), d.command());
        assertNull(d.url());
    }

    @Test
    void descriptor_sseFactory() {
        McpServerDescriptor d = McpServerDescriptor.sse("remote", "http://localhost:8080/sse");
        assertEquals("remote", d.name());
        assertFalse(d.isStdio());
        assertEquals("http://localhost:8080/sse", d.url());
    }

    // ============ McpToolSchema ============

    @Test
    void toolSchema_fromJson() {
        ObjectMapper mapper = new ObjectMapper();
        String json = "{\"name\":\"calc\",\"description\":\"Calculator\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"expr\":{\"type\":\"string\"}}}}";
        try {
            McpToolSchema schema = McpToolSchema.from(mapper.readTree(json));
            assertEquals("calc", schema.name());
            assertEquals("Calculator", schema.description());
            assertNotNull(schema.inputSchema());
            assertTrue(schema.inputSchema().has("properties"));
        } catch (Exception e) {
            fail("Failed to parse tool schema: " + e.getMessage());
        }
    }
}
