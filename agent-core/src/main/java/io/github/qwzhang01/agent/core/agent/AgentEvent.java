package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.model.ToolCall;

/**
 * Event emitted while an {@link Agent} streams a run.
 * <p>
 * Content deltas are for live UI. {@link Done} is the persistence boundary
 * and must not be preceded by a delta that merely repeats the full answer.
 */
public sealed interface AgentEvent {

    /**
     * A chunk of assistant text (may be a token or a larger fragment).
     */
    record ContentDelta(String delta) implements AgentEvent {
    }

    /**
     * A tool is about to execute. Emitted only after a complete model response
     * that contains this call — not on incremental {@code ToolCallEvent}s.
     */
    record ToolStarted(ToolCall toolCall) implements AgentEvent {
    }

    /**
     * A tool finished and its result was written into conversation history.
     */
    record ToolFinished(String toolCallId, String toolName, String result) implements AgentEvent {
    }

    /**
     * The loop reached a terminal success or max-steps state.
     *
     * @param finalAnswer assistant text, or the max-steps placeholder
     * @param state       mutated run state
     */
    record Done(String finalAnswer, AgentState state) implements AgentEvent {
    }

    /**
     * The loop failed. {@code cause} may be null when only a message is known.
     */
    record Error(String message, Throwable cause) implements AgentEvent {
    }
}
