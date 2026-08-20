package io.github.qwzhang01.agent.examples;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.mcp.*;
import io.github.qwzhang01.agent.mcp.transport.McpTransport;
import io.github.qwzhang01.agent.security.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Stage 10 acceptance example: connect MCP server + discover tools + governed execution.
 * <p>
 * Demonstrates:
 * - McpClient connecting to a mock MCP server (inline, no external subprocess needed)
 * - listTools discovering the "echo" tool
 * - McpToolAdapter wrapping it as a local Tool
 * - Registration into ToolRegistry alongside local tools
 * - GovernedToolExecutor wrapping: MCP tool gets REQUIRES_APPROVAL (D4),
 *   audit records APPROVED + EXECUTED, result passes through ResultSanitizer (D6)
 * <p>
 * Run: mvn compile exec:java -pl examples -Dexec.mainClass=io.github.qwzhang01.agent.examples.McpExample
 */
public class McpExample {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Stage 10: MCP Client + Governed Tool Execution ===\n");

        // 1. Set up a mock MCP server (inline, no external process)
        InlineMockTransport transport = new InlineMockTransport();
        transport.registerResponse("initialize",
                "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"serverInfo\":{\"name\":\"echo-mcp\",\"version\":\"1.0\"}}}");
        transport.registerResponse("tools/list",
                "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"tools\":[" +
                "{\"name\":\"echo\",\"description\":\"Echoes your text back\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}}" +
                "]}}");
        transport.registerResponse("tools/call",
                "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"echoed: hello MCP!\"}]}}");
        transport.registerResponse("shutdown",
                "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{}}");

        // 2. Connect MCP client
        McpServerDescriptor descriptor = McpServerDescriptor.stdio("echo-mcp", "mock");
        McpClient client = new McpClient(descriptor, transport);
        client.connect();
        System.out.println("Connected to MCP server: " + descriptor.name());

        // 3. Discover tools
        List<McpToolSchema> tools = client.listTools();
        System.out.println("Discovered " + tools.size() + " tool(s):");
        for (McpToolSchema t : tools) {
            System.out.println("  - " + t.name() + ": " + t.description());
        }

        // 4. Wrap MCP tool as local Tool (D1: transparent adaptation)
        McpToolAdapter mcpTool = new McpToolAdapter(client, tools.get(0));

        // Also register a local tool to show mixing
        Tool localTool = new Tool() {
            @Override public String getName() { return "get_time"; }
            @Override public String getDescription() { return "Returns current time"; }
            @Override public String getParametersSchema() { return "{}"; }
            @Override public String execute(com.fasterxml.jackson.databind.JsonNode args) {
                return java.time.Instant.now().toString();
            }
        };

        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(localTool);
        registry.register(mcpTool);
        System.out.println("\nToolRegistry now has " + registry.listTools().size()
                + " tools (1 local + 1 MCP)");

        // 5. Set up governance (Stage 9 components, zero changes for MCP tools!)
        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO)
                .setPermission("echo", ToolPermission.REQUIRES_APPROVAL); // MCP tool = conservative (D4)
        InMemoryAuditLogger audit = new InMemoryAuditLogger();

        GovernedToolExecutor executor = GovernedToolExecutor.builder(
                new DefaultToolExecutor(registry))
                .permissionChecker(new PermissionChecker(policy))
                .approvalService(ConsoleApprovalService.autoApprove()) // auto-approve for demo
                .resultSanitizer(new DefaultResultSanitizer())         // sanitize MCP results (D6)
                .auditLogger(audit)
                .build();

        // 6. Call the MCP tool through the governance layer
        System.out.println("\n--- Calling MCP tool 'echo' through GovernedToolExecutor ---");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode echoArgs = mapper.createObjectNode().put("text", "hello MCP!");

        String result = executor.execute(
                ToolCall.of("id-1", "echo", echoArgs.toString()));
        System.out.println("Result: " + result);

        // 7. Call local tool (AUTO, no approval needed)
        System.out.println("\n--- Calling local tool 'get_time' (AUTO) ---");
        String timeResult = executor.execute(
                ToolCall.of("id-2", "get_time", "{}"));
        System.out.println("Result: " + timeResult);

        // 8. Show audit trail
        System.out.println("\n--- Audit Trail ---");
        for (AuditEvent event : audit.getAll()) {
            System.out.printf("  [%s] %s | %dms | reason=%s%n",
                    event.status(),
                    event.toolName(),
                    event.durationMs(),
                    event.reason());
        }

        // 9. Disconnect
        client.disconnect();
        System.out.println("\nMCP server disconnected");

        System.out.println("\n=== Acceptance: MCP tools transparently governed by Stage 9 (D1+D6) ===");
    }

    // ============ Inline Mock Transport (for self-contained demo) ============

    /**
     * A minimal mock MCP transport for the example. Not for production use.
     * Same pattern as MockMcpTransport in tests, but self-contained in the example.
     */
    static class InlineMockTransport implements McpTransport {
        private final Map<String, String> responses = new ConcurrentHashMap<>();
        private final Queue<String> queue = new ConcurrentLinkedQueue<>();
        private volatile boolean open = false;

        void registerResponse(String method, String json) { responses.put(method, json); }

        @Override public void open() { open = true; }

        @Override public void send(String json) {
            if (!open) throw new RuntimeException("not open");
            try {
                var node = new ObjectMapper().readTree(json);
                String method = node.has("method") ? node.get("method").asText() : "";
                if (method.startsWith("notifications/")) return;
                String resp = responses.get(method);
                if (resp != null) {
                    var respNode = new ObjectMapper().readTree(resp);
                    ((ObjectNode) respNode).set("id", node.get("id"));
                    queue.add(respNode.toString());
                } else {
                    queue.add("{\"jsonrpc\":\"2.0\",\"id\":" + node.get("id") + ",\"result\":{}}");
                }
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Override public String receive() {
            String r = queue.poll();
            if (r == null) throw new RuntimeException("no response");
            return r;
        }

        @Override public boolean isOpen() { return open; }
        @Override public void close() { open = false; }
    }
}
