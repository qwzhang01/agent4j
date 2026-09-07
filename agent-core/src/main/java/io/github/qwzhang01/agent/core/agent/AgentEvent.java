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
     * @param promptTokens     estimated prompt token count (character-count approximation)
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
}
