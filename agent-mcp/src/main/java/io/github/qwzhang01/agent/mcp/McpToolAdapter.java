package io.github.qwzhang01.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.core.event.BoundaryEvent;
import io.github.qwzhang01.agent.core.run.RunContext;
import io.github.qwzhang01.agent.core.run.RunCancelledException;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.core.tool.ToolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

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
    private final Consumer<BoundaryEvent> eventSink;

    /**
     * @param client the connected MCP client (used for remote tool calls)
     * @param schema the tool definition received from the server via tools/list
     */
    public McpToolAdapter(McpClient client, McpToolSchema schema) {
        this(client, schema, null);
    }

    /**
     * Full wiring with the boundary telemetry sink (harness batch 7): one
     * {@link BoundaryEvent.McpBoundaryEvent} per tool call — success or
     * failure — emitted at the protocol boundary. The sink is a side
     * channel: a throwing sink is swallowed with a counted warn and never
     * breaks the call (the same discipline as every other boundary emitter).
     *
     * @param eventSink consumer of boundary telemetry (null = no events,
     *                  legacy behavior byte-for-byte)
     */
    public McpToolAdapter(McpClient client, McpToolSchema schema,
                          Consumer<BoundaryEvent> eventSink) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.schema = Objects.requireNonNull(schema, "schema must not be null");
        this.eventSink = eventSink != null ? eventSink : e -> { };
    }

    /** Side-channel emission: telemetry must never break the tool call. */
    private void emit(BoundaryEvent event) {
        try {
            eventSink.accept(event);
        } catch (RuntimeException e) {
            log.warn("[McpToolAdapter] event sink failed (side channel, swallowed): {}", e);
        }
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
        long start = System.currentTimeMillis();
        String serverName = client.getDescriptor().name();
        // Stage 6.2: validate arguments against the server-declared schema
        // BEFORE the wire — malformed args must fail here, not at the remote
        // server (correctness + injection surface).
        try {
            McpSchemaValidator.validateOrThrow(schema.inputSchema(), arguments);
        } catch (IllegalArgumentException e) {
            emit(new BoundaryEvent.McpToolCallFailed(
                    serverName, schema.name(), "SCHEMA_INVALID", Instant.now()));
            throw e;
        }
        io.github.qwzhang01.agent.core.run.CancellationToken token =
                ctx != null ? ctx.cancellationToken() : null;
        try {
            String result = client.callTool(schema.name(), arguments, token);
            emit(new BoundaryEvent.McpToolCallFinished(
                    serverName, schema.name(),
                    System.currentTimeMillis() - start, Instant.now()));
            return result;
        } catch (RunCancelledException e) {
            // Cancellation is control flow, not a boundary failure — but it
            // IS an observable outcome of the protocol call: the wire was
            // abandoned mid-flight. Structure only, never a rethrow here.
            emit(new BoundaryEvent.McpToolCallFailed(
                    serverName, schema.name(), "CANCELLED", Instant.now()));
            throw e;
        } catch (IOException e) {
            emit(new BoundaryEvent.McpToolCallFailed(
                    serverName, schema.name(), "TRANSPORT", Instant.now()));
            throw new ToolException(
                    "MCP tool call failed for '" + schema.name() + "': " + e.getMessage(), e);
        }
    }

    // ============ Accessors ============

    public McpClient getClient() { return client; }
    public McpToolSchema getSchema() { return schema; }
}
