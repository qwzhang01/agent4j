package io.github.qwzhang01.agent.trace.trajectory;

import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.ToolCall;

import java.util.List;

/**
 * The Action half of one trajectory step: what the model decided on one call
 * (Stage 14 D3 - one step = one model call).
 * <p>
 * Faithfully mirrors the {@link ModelResponse} the loop received, including
 * token usage and wall time, so consumers can compute cost-per-action.
 * <p>
 * For a failed model call (provider threw), an action with
 * {@code finishReason = "error"} and null content/toolCalls is recorded -
 * the failure itself is training data (D4: failed runs are assets).
 *
 * @param content      model text output (null if only tool calls / error)
 * @param toolCalls    tool calls requested by the model (null if none)
 * @param finishReason why the model stopped: "stop", "tool_calls", "length", "error"
 * @param usage        token usage of this call (null if provider did not report)
 * @param durationMs   wall time of the model call
 */
public record StepAction(
        String content,
        List<ToolCall> toolCalls,
        String finishReason,
        ModelResponse.TokenUsage usage,
        long durationMs
) {
    public StepAction {
        toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
