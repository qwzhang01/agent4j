package io.github.qwzhang01.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * Tool definition received from an MCP server via {@code tools/list} (Stage 10).
 * <p>
 * This is what the server tells us about a tool it exposes. We then wrap it
 * in {@link McpToolAdapter} to implement our local {@code Tool} interface.
 *
 * @param name        tool name (e.g. "get_weather")
 * @param description human-readable description
 * @param inputSchema JSON Schema for the tool's input parameters
 */
public record McpToolSchema(
        String name,
        String description,
        JsonNode inputSchema
) {
    public McpToolSchema {
        Objects.requireNonNull(name, "name must not be null");
    }

    /**
     * Parse a tool definition from a JSON node (as received from tools/list).
     */
    public static McpToolSchema from(JsonNode node) {
        return new McpToolSchema(
                node.get("name").asText(),
                node.has("description") ? node.get("description").asText() : "",
                node.get("inputSchema")
        );
    }
}
