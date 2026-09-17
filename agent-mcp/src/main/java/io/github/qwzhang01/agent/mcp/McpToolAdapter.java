package io.github.qwzhang01.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.core.run.RunContext;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.core.tool.ToolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;

/**
 * Adapts an MCP tool (remote, served by an MCP server) to our local {@link Tool} interface (Stage 10 D1).
 * <p>
 * This is the <b>key glue</b> that makes MCP tools transparently usable in the existing framework:
 * <ul>
 *   <li>Registered into {@code ToolRegistry} like any local tool
 *   <li>Executed by {@code DefaultToolExecutor} like any local tool
 *   <li>Wrapped by {@code GovernedToolExecutor} (Stage 9) like any local tool
 *       -- permissions, approval, audit, sanitization all <b>automatically apply</b>
 *   <li>Attached to {@code ModelRequest.tools} (Stage 1) like any local tool
 * </ul>
 * <p>
 * The governance layer (Stage 9) doesn't know -- and doesn't need to know --
 * that this tool is remote. The decorator pattern's reward: new connection
 * method = new Tool implementation, governance layer unchanged.
 */
public class McpToolAdapter implements Tool {

    private static final Logger log = LoggerFactory.getLogger(McpToolAdapter.class);

    private final McpClient client;
    private final McpToolSchema schema;

    /**
     * @param client the connected MCP client (used for remote tool calls)
     * @param schema the tool definition received from the server via tools/list
     */
    public McpToolAdapter(McpClient client, McpToolSchema schema) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.schema = Objects.requireNonNull(schema, "schema must not be null");
    }

    @Override
    public String getName() {
        return schema.name();
    }

    @Override
    public String getDescription() {
        return schema.description() != null ? schema.description() : "";
    }

    @Override
    public String getParametersSchema() {
        if (schema.inputSchema() != null) {
            return schema.inputSchema().toString();
        }
        return "{}";
    }

    /**
     * Execute the tool by calling the remote MCP server.
     * <p>
     * This is where the "remote call" happens: instead of running local Java code,
     * we delegate to {@link McpClient#callTool}, which sends a JSON-RPC tools/call
     * request to the MCP server subprocess and returns its text response.
     *
     * @throws ToolException if the remote call fails (connection lost, server error, etc.)
     */
    @Override
    public String execute(JsonNode arguments) throws ToolException {
        return execute(arguments, null);
    }

    /**
     * Context-aware execution (harness batch 3: cancellation wiring).
     * <p>
     * The run's {@link RunContext} carries the {@code CancellationToken};
     * {@code DefaultToolExecutor} hands the ctx down to every tool, and here
     * it reaches the wire: {@link McpClient#callTool(String, JsonNode,
     * io.github.qwzhang01.agent.core.run.CancellationToken)} checks the token
     * before sending and between receive polls. A cancellation hit surfaces
     * as {@code RunCancelledException} — the same structured signal every
     * other boundary throws — and must NOT be wrapped into ToolException:
     * cancellation is not a business failure, and the loop's event contract
     * (TOOL_FAILURE vs cancelled) depends on the exception type surviving.
     * <p>
     * A null ctx (legacy path) or a ctx without token keeps the uncancellable
     * legacy behavior bit-for-bit.
     *
     * @param ctx the run's execution context; null = legacy uncancellable path
     */
    @Override
    public String execute(JsonNode arguments, RunContext ctx) throws ToolException {
        log.debug("Calling MCP tool '{}' on server '{}'",
                schema.name(), client.getDescriptor().name());
        // Stage 6.2: validate arguments against the server-declared schema
        // BEFORE the wire — malformed args must fail here, not at the remote
        // server (correctness + injection surface).
        McpSchemaValidator.validateOrThrow(schema.inputSchema(), arguments);
        io.github.qwzhang01.agent.core.run.CancellationToken token =
                ctx != null ? ctx.cancellationToken() : null;
        try {
            return client.callTool(schema.name(), arguments, token);
        } catch (IOException e) {
            throw new ToolException(
                    "MCP tool call failed for '" + schema.name() + "': " + e.getMessage(), e);
        }
    }

    // ============ Accessors ============

    public McpClient getClient() { return client; }
    public McpToolSchema getSchema() { return schema; }
}
