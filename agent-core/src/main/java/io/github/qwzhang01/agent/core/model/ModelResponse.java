package io.github.qwzhang01.agent.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Response from a model provider.
 *
 * @param content      text output from the model (may be empty if only tool calls)
 * @param toolCalls    tool calls requested by the model (null if none)
 * @param finishReason why the model stopped: "stop", "tool_calls", "length", "error"
 * @param usage        token usage stats (null if not reported)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelResponse(
        String content,
        List<ToolCall> toolCalls,
        String finishReason,
        TokenUsage usage
) {
    public static ModelResponse text(String content) {
        return new ModelResponse(content, null, "stop", null);
    }

    public static ModelResponse toolCalls(List<ToolCall> calls) {
        return new ModelResponse(null, calls, "tool_calls", null);
    }

    // ============ Factory ============

    public static ModelResponse error(String message) {
        return new ModelResponse(null, null, "error", null);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public boolean isFinished() {
        return !"tool_calls".equals(finishReason);
    }

    // ============ Nested ============

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
    }
}
