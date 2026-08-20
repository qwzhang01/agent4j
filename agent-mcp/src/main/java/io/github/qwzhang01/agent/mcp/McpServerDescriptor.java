package io.github.qwzhang01.agent.mcp;

import java.util.List;
import java.util.Objects;

/**
 * MCP server connection configuration (Stage 10).
 * <p>
 * Describes how to reach a server and what transport to use.
 *
 * @param name     human-readable server name
 * @param command  subprocess command for stdio transport (null for SSE)
 * @param url      server URL for SSE transport (null for stdio)
 * @param version  expected MCP version (default "2024-11-05")
 */
public record McpServerDescriptor(
        String name,
        List<String> command,
        String url,
        String version
) {
    public McpServerDescriptor {
        Objects.requireNonNull(name, "name must not be null");
    }

    /**
     * Stdio descriptor: launch a local subprocess.
     */
    public static McpServerDescriptor stdio(String name, String... command) {
        return new McpServerDescriptor(name, List.of(command), null, "2024-11-05");
    }

    /**
     * Stdio descriptor with command list.
     */
    public static McpServerDescriptor stdio(String name, List<String> command) {
        return new McpServerDescriptor(name, command, null, "2024-11-05");
    }

    /**
     * SSE descriptor: connect to a remote HTTP/SSE server (v2, not yet implemented).
     */
    public static McpServerDescriptor sse(String name, String url) {
        return new McpServerDescriptor(name, null, url, "2024-11-05");
    }

    /**
     * Whether this descriptor uses stdio transport.
     */
    public boolean isStdio() {
        return command != null && !command.isEmpty();
    }
}
