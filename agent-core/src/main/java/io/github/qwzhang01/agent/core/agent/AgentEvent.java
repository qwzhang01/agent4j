package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.model.ToolCall;

import java.util.List;

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

    /**
     * Observability snapshot emitted by {@link io.github.qwzhang01.agent.chat.ChatRoom}
     * (or its engine) once per turn, immediately <em>before</em> {@link Done}.
     * <p>
     * Hosts listen for this event to audit what the model actually saw, correlate
     * persona version with reply quality, and track per-turn cost signals.
     *
     * @param personaVersion   persona version string from {@code PersonaSpec.version}
     *                         (null until A6 wires it)
     * @param recalledSubjects subject keys of all memory entries injected via
     *                         {@link io.github.qwzhang01.agent.chat.context.MemorySource}
     *                         (empty list when no MemorySource is registered)
     * @param extraTextBytes   UTF-8 byte size of all
     *                         {@link io.github.qwzhang01.agent.chat.context.ExtraTextSource}
     *                         contributions after budget truncation
     * @param promptTokens     estimated prompt token count (character-count approximation),
     *                         measured on the exact prefix sent for the accepted attempt
     *                         (includes any retry-extra text appended for later attempts)
     * @param completionTokens estimated completion token count (character-count approximation)
     * @param latencyMs        wall-clock milliseconds from context assembly start to Done
     */
    record TurnTrace(
            String personaVersion,
            List<String> recalledSubjects,
            int extraTextBytes,
            int promptTokens,
            int completionTokens,
            long latencyMs
    ) implements AgentEvent {
    }

    /**
     * The active agent transferred the conversation to another agent
     * (Stage 19). Emitted right after the handoff tool result is written
     * into history and before the next model call, which already runs
     * under the new config.
     *
     * @param fromAgent config name that declared and invoked the handoff
     * @param toAgent   config name that now owns the loop
     * @param toolName  the handoff tool the model called
     */
    record Handoff(String fromAgent, String toAgent, String toolName) implements AgentEvent {
    }

    /**
     * Emitted by {@link io.github.qwzhang01.agent.chat.ChatEngine} when a completed
     * reply is discarded by a {@code RetryPolicy} and a new generation attempt
     * is about to start.
     * <p>
     * Signals UI listeners to discard/reset any {@link ContentDelta}s accumulated
     * for the current turn so far: the next {@code ContentDelta} belongs to a fresh
     * attempt, not a continuation of the discarded one. Never emitted when no
     * {@code RetryPolicy} is configured (default behavior is unchanged).
     *
     * @param discardedReply the full text of the attempt being thrown away
     * @param attemptNumber  1-based index of the attempt about to start (2 = first retry)
     * @param maxAttempts    the configured {@code RetryPolicy#maxAttempts()}
     */
    record RetryStarted(String discardedReply, int attemptNumber, int maxAttempts)
            implements AgentEvent {
    }
}
