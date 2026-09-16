package io.github.qwzhang01.agent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.core.run.RunContext;
import io.github.qwzhang01.agent.core.tool.contract.ToolDefinition;

/**
 * Interface for a tool that an Agent can call.
 * <p>
 * Design principle: a Tool is not just a Java function. It has:
 * - A name and description (for the model to understand when to use it)
 * - A JSON schema for parameters (for the model to construct correct arguments)
 * - An execute method (the actual behavior)
 * <p>
 * Tool execution results are always returned as String (text).
 * The model will consume this text in the next conversation turn.
 */
public interface Tool {

    /**
     * Unique tool name (used by the model to call it).
     * Convention: snake_case, e.g. "get_weather", "search_web".
     */
    String getName();

    /**
     * Human-readable description of what this tool does.
     * This is sent to the model — clarity matters more than brevity.
     */
    String getDescription();

    /**
     * JSON Schema for the tool's parameters.
     * Return null if the tool takes no parameters.
     * <p>
     * Example:
     * <pre>{@code
     * {
     *   "type": "object",
     *   "properties": {
     *     "location": { "type": "string", "description": "City name" }
     *   },
     *   "required": ["location"]
     * }
     * }</pre>
     */
    String getParametersSchema();

    /**
     * Structured contract (Stage 2.1, harness roadmap).
     * <p>
     * Default: a legacy-adapted definition (side-effect UNKNOWN, version
     * "legacy", schema from {@link #getParametersSchema()}). Override to
     * declare the real contract — schemas, version, side-effect level,
     * required capabilities and size/timeout budgets. The validation
     * chain and secure assemblies read this, never the loose accessors.
     */
    default ToolDefinition definition() {
        return ToolDefinition.fromLegacyTool(this);
    }

    /**
     * Execute the tool with the given arguments.
     *
     * @param arguments parsed JSON arguments from the model (may be null)
     * @return result as text (will be sent back to the model)
     * @throws ToolException if execution fails
     */
    String execute(JsonNode arguments) throws ToolException;

    /**
     * Execute with the run context (Stage 1.2 of the harness roadmap).
     * <p>
     * Default: legacy path. Context-aware tools read identity (tenant/user),
     * deadline and cancellation from {@code ctx} instead of free strings;
     * the context is read-only and must never be re-created downstream.
     * A tool that needs neither keeps the single-arg method only.
     *
     * @param arguments parsed JSON arguments from the model (may be null)
     * @param ctx       the run context (null on the legacy path)
     * @return result as text
     * @throws ToolException if execution fails
     */
    default String execute(JsonNode arguments, RunContext ctx) throws ToolException {
        return execute(arguments);
    }
}
