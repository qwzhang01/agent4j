package io.github.qwzhang01.agent.examples;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.mcp.ManagedMcpClient;
import io.github.qwzhang01.agent.mcp.McpRestartPolicy;
import io.github.qwzhang01.agent.mcp.McpServerDescriptor;
import io.github.qwzhang01.agent.mcp.transport.StdioTransport;

/**
 * Stage 10 process management: self-healing MCP connection, demonstrated against
 * the REAL official filesystem server.
 * <p>
 * Scenario:
 * <ol>
 *   <li>Connect + healthy (MCP-standard ping answered by the real server)</li>
 *   <li>Call a tool -- works</li>
 *   <li>KILL the server subprocess (simulated crash, e.g. OOM killer)</li>
 *   <li>Health check reports dead</li>
 *   <li>Next call: failure detected -> auto-restart subprocess -> redo handshake
 *       -> retry the call -- all transparent to the caller</li>
 *   <li>Restart budget stats shown (storm protection)</li>
 * </ol>
 * <p>
 * Run:
 * <pre>
 *   mkdir -p /tmp/mcp-demo && echo "hello" > /tmp/mcp-demo/hello.txt
 *   mvn install -DskipTests -pl agent-mcp -am   # after changing agent-mcp
 *   mvn compile exec:java -pl examples \
 *     -Dexec.mainClass=io.github.qwzhang01.agent.examples.ManagedMcpExample
 * </pre>
 */
public class ManagedMcpExample {

    public static void main(String[] args) throws Exception {
        String allowedDir = args.length > 0 ? args[0] : "/tmp/mcp-demo";
        System.out.println("=== Stage 10: Process Management -- Self-Healing MCP Client ===\n");

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode listArgs = mapper.createObjectNode().put("path", allowedDir);

        // Restart budget: 3 restarts per minute, no cooldown (demo; production: 5s)
        ManagedMcpClient client = new ManagedMcpClient(
                McpServerDescriptor.stdio("filesystem",
                        "npx", "-y", "@modelcontextprotocol/server-filesystem", allowedDir),
                new McpRestartPolicy(3, 0, 60_000));
        try {
            // 1. Connect + health check (real MCP ping to the real server)
            client.connect();
            System.out.println("[1] Connected. isHealthy() = " + client.isHealthy()
                    + "  (process alive + MCP ping answered)\n");

            // 2. Normal call
            System.out.println("[2] list_directory -> " + brief(client.callTool("list_directory", listArgs)));

            // 3. Simulate crash: force-kill the subprocess (like an OOM killer)
            StdioTransport transport = (StdioTransport) client.getTransport();
            System.out.println("\n[3] >>> FORCE-KILLING server subprocess (simulated crash) <<<");
            transport.destroyForcibly();
            Thread.sleep(200);  // let the process actually die

            // 4. Health check detects the death
            System.out.println("[4] isHealthy() after crash = " + client.isHealthy() + "\n");

            // 5. Next call: transparent auto-recovery
            //    fail -> detect dead process -> restart budget ok ->
            //    respawn subprocess -> redo initialize handshake -> retry call
            System.out.println("[5] Calling again... (auto-recovery kicks in, respawns + re-handshakes)");
            long t0 = System.currentTimeMillis();
            String result = client.callTool("list_directory", listArgs);
            System.out.println("    list_directory -> " + brief(result));
            System.out.println("    (recovery took " + (System.currentTimeMillis() - t0) + " ms incl. npx restart)\n");

            // 6. Storm protection stats
            System.out.println("[6] Restart count (window) = " + client.getRestartCount()
                    + " / 3   |   healthy again = " + client.isHealthy());

            System.out.println("\n=== Acceptance: crashed MCP server is transparently revived ===");
        } finally {
            client.disconnect();
            System.out.println("\nDisconnected");
        }
    }

    private static String brief(String s) {
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 60 ? oneLine.substring(0, 60) + "..." : oneLine;
    }
}
