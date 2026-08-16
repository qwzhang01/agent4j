package io.github.qwzhang01.agent.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A single message in a conversation.
 * <p>
 * Uses factory methods to support flexible construction:
 * - SYSTEM/USER messages: role + content
 * - ASSISTANT message: role + content + optional toolCalls
 * - TOOL message: role + content + toolCallId (result of a tool execution)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(
        ChatRole role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId,
        String name
) {
    // ============ Factory Methods ============

    public static ChatMessage system(String content) {
        return new ChatMessage(ChatRole.SYSTEM, content, null, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(ChatRole.USER, content, null, null, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ChatRole.ASSISTANT, content, null, null, null);
    }

    public static ChatMessage assistantWithTools(String content, List<ToolCall> toolCalls) {
        return new ChatMessage(ChatRole.ASSISTANT, content, toolCalls, null, null);
    }

    public static ChatMessage tool(String toolCallId, String result) {
        return new ChatMessage(ChatRole.TOOL, result, null, toolCallId, null);
    }

    public static ChatMessage tool(String toolCallId, String name, String result) {
        return new ChatMessage(ChatRole.TOOL, result, null, toolCallId, name);
    }
}
