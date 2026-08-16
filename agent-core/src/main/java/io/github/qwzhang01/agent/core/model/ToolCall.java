package io.github.qwzhang01.agent.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Represents a tool call requested by the model.
 *
 * @param id       unique call id assigned by the model
 * @param name     tool name to invoke
 * @param arguments JSON arguments as a parsed node (may be null if no args)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolCall(
        String id,
        String name,
        JsonNode arguments
) {
    public static ToolCall of(String id, String name, JsonNode arguments) {
        return new ToolCall(id, name, arguments);
    }

    public static ToolCall of(String id, String name, String rawJson) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return new ToolCall(id, name, mapper.readTree(rawJson));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON arguments: " + rawJson, e);
        }
    }
}
