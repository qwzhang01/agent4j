package io.github.qwzhang01.agent.examples;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.mcp.McpClient;
import io.github.qwzhang01.agent.mcp.McpServerDescriptor;
import io.github.qwzhang01.agent.mcp.McpToolSchema;

import java.util.List;

/**
 * Stage 10: connect to a REAL external MCP server -- the official filesystem server.
 * <p>
 * Unlike {@link McpExample} (inline mock), this launches an actual subprocess:
 * <pre>
 *   npx -y @modelcontextprotocol/server-filesystem /tmp/mcp-demo
 * </pre>
 * and proves protocol compatibility with a third-party server: initialize
 * handshake, tools/list discovery, tools/call execution -- all against the
 * real JSON-RPC implementation, not our mock.
 * <p>
 * Requirements:
 * <ul>
 *   <li>Node.js + npx on PATH (first run downloads the npm package, be patient)</li>
 *   <li>Demo directory with a file to read (see Run below)</li>
 * </ul>
 * <p>
 * Run:
 * <pre>
 *   mkdir -p /tmp/mcp-demo && echo "hello from real mcp server" > /tmp/mcp-demo/hello.txt
 *   mvn compile exec:java -pl examples -am \
 *     -Dexec.mainClass=io.github.qwzhang01.agent.examples.McpRealServerExample
 * </pre>
 */
public class McpRealServerExample {

    public static void main(String[] args) throws Exception {
        String allowedDir = args.length > 0 ? args[0] : "/tmp/mcp-demo";
        System.out.println("=== Stage 10: Connect to REAL MCP Server (official filesystem) ===\n");

        // 1. Descriptor: command is tokenized -- npx + package + allowed directory.
        //    The last argument is a SERVER POLICY: the filesystem server refuses
        //    to touch anything outside it (server-side sandbox).
        McpServerDescriptor descriptor = McpServerDescriptor.stdio(
                "filesystem",
                "npx", "-y", "@modelcontextprotocol/server-filesystem", allowedDir);

        McpClient client = new McpClient(descriptor);
        try {
            // 2. Initialize handshake with a real third-party server.
            //    First run may take a while: npx downloads the package.
            System.out.println("Connecting (first run downloads the npm package, may take ~30s)...");
            long t0 = System.currentTimeMillis();
            client.connect();
            System.out.println("Connected in " + (System.currentTimeMillis() - t0) + " ms"
                    + " -- handshake OK with real server\n");

            // 3. Discover tools (real tools/list response)
            List<McpToolSchema> tools = client.listTools();
            System.out.println("Discovered " + tools.size() + " tools from REAL server:");
            for (McpToolSchema t : tools) {
                System.out.println("  - " + t.name() + ": " + brief(t.description()));
            }

            ObjectMapper mapper = new ObjectMapper();

            // 4. Call tools -- plain JSON-RPC against the real implementation.
            //    Tool names are discovered at runtime (versions differ), not hardcoded blindly.
            System.out.println("\n--- tools/call: list_allowed_directories ---");
            System.out.println("Result: " + client.callTool("list_allowed_directories", null));

            System.out.println("\n--- tools/call: list_directory ---");
            ObjectNode listArgs = mapper.createObjectNode().put("path", allowedDir);
            System.out.println("Result: " + client.callTool("list_directory", listArgs));

            String readTool = findTool(tools, "read_text_file", "read_file");
            if (readTool != null) {
                System.out.println("\n--- tools/call: " + readTool + " ---");
                ObjectNode readArgs = mapper.createObjectNode().put("path", allowedDir + "/hello.txt");
                System.out.println("Result: " + client.callTool(readTool, readArgs));
            } else {
                System.out.println("\n(no read tool found in this server version, skipping read demo)");
            }

            System.out.println("\n=== Acceptance: McpClient interoperates with the REAL official MCP server ===");
        } finally {
            // 5. Disconnect: best-effort shutdown + destroy subprocess
            client.disconnect();
            System.out.println("\nDisconnected");
        }
    }

    /** Return the first tool name that exists in the discovered list. */
    private static String findTool(List<McpToolSchema> tools, String... candidates) {
        for (String candidate : candidates) {
            for (McpToolSchema t : tools) {
                if (t.name().equals(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static String brief(String s) {
        if (s == null) return "";
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 70 ? oneLine.substring(0, 70) + "..." : oneLine;
    }
}
