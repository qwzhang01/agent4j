package io.github.qwzhang01.agent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;

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
     * Unique tool name (used by the model to call this tool).
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
     * Execute the tool with the given arguments.
     *
     * @param arguments parsed JSON arguments from the model (may be null)
     * @return result as text (will be sent back to the model)
     * @throws ToolException if execution fails
     */
    String execute(JsonNode arguments) throws ToolException;
}
