package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.model.ToolCall;

import java.util.List;

/**
 * Event emitted while an {@link Agent} streams a run.
 * <p>
 * Content deltas are for live UI. {@link Done} is the persistence boundary
 * and must not be preceded by a delta that merely repeats the full answer.
 * <p>
 * Stage 9 (typed-event completeness): the model boundary and the governance
 * boundary each get explicit facts. The loop already logged model calls and
 * tool validation silently; these events make them first-class without
 * changing any existing behavior:
 * <ul>
 *   <li>{@link ModelCallStarted} / {@link ModelCallFinished}: one pair per
 *       model call, emitted right around the invoker. Carry config name,
 *       model id, message count, latency, and (when the loop knows it)
 *       token usage — the model-boundary facts a replay host needs.</li>
 *   <li>{@link ToolValidationRejected}: a tool call was rejected by the
 *       governance chain (permission/approval/validation) before execution.
 *       Rejection is a fact, not a silent error string; hosts audit the
 *       tool boundary without parsing error text.</li>
 *   <li>{@link ReflectionStarted} / {@link ReflectionFinished}: a
 *       {@link ReflectiveAgent} critique cycle began/ended. Reflection
 *       output is internal by design — the events carry the cycle count and
 *       verdict, never the critique text (user-visible output and
 *       reflection output are separated by contract, not by convention).</li>
 * </ul>
 */
public sealed interface AgentEvent {

    /**
     * A chunk of assistant text (may be a token or a larger fragment).
     */
    record ContentDelta(String delta) implements AgentEvent {
    }

    /**
     * The model is about to be called. Emitted after input guardrails and
     * request assembly, before the invoker runs. One event per model call,
     * paired with {@link ModelCallFinished}.
     *
     * @param agentName    name of the config owning the loop at this call
     *                     (differs from the entry name after a handoff)
     * @param model        model id from the request (may be null when the
     *                     client fills it in — record what the loop knows)
     * @param messageCount messages in the assembled request
     * @param step         1-based loop step this call belongs to
     */
    record ModelCallStarted(String agentName, String model, int messageCount, int step)
            implements AgentEvent {
    }

    /**
     * The model call returned (success or failure). Emitted right after the
     * invoker, before tool handling. Latency is wall-clock milliseconds.
     *
     * @param agentName   name of the config owning the loop
     * @param model       model id from the request (null when unknown)
     * @param latencyMs   wall-clock duration of the model call
     * @param toolCallCount number of tool calls in the response (0 = text)
     * @param failed      true when the call threw or the stream errored
     */
    record ModelCallFinished(String agentName, String model, long latencyMs,
                             int toolCallCount, boolean failed) implements AgentEvent {
    }

    /**
     * A tool is about to execute. Emitted only after a complete model response
     * that contains this call — not on incremental {@code ToolCallEvent}s.
     */
    record ToolStarted(ToolCall toolCall) implements AgentEvent {
    }

    /**
     * A tool call was rejected by the governance chain (validation,
     * permission, or approval boundary) BEFORE execution started.
     * <p>
     * Stage 9: rejection is an explicit fact. Previously a rejection
     * surfaced only as an {@code [ERROR] ...} tool result string; hosts had
     * to parse text to audit the tool boundary. The legacy error-string
     * result is kept (models need it to self-correct); this event is the
     * observability twin.
     *
     * @param toolCallId the rejected call's id
     * @param toolName   the rejected call's tool name
     * @param stage      which governance stage rejected: "validation",
     *                   "permission", or "approval"
     * @param reason     rejection reason (governance message, never null)
     */
    record ToolValidationRejected(String toolCallId, String toolName,
                                  String stage, String reason) implements AgentEvent {
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
     * <p>
     * Observability signal. Persistence of the active identity is
     * {@link AgentState#getLastActiveAgentName()}; hosts no longer need to
     * remember {@code toAgent} themselves to resume correctly.
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

    /**
     * A reflection cycle started (Stage 9 {@link ReflectiveAgent}). The
     * critique pass re-reads the candidate answer and returns a verdict.
     * The critique text itself is NOT in this event — reflection output is
     * internal to the reflective loop and never shown to the user.
     *
     * @param cycle  1-based reflection cycle index
     * @param maxCycles the configured maximum (rejection beyond this is
     *                  passed through as-is)
     */
    record ReflectionStarted(int cycle, int maxCycles) implements AgentEvent {
    }

    /**
     * A reflection cycle finished with a verdict.
     *
     * @param cycle   1-based reflection cycle index
     * @param verdict {@code PASS} when the critique accepted the answer,
     *                {@code REVISE} when a revised candidate will be
     *                generated, {@code GIVE_UP} when max cycles were hit
     *                and the last candidate is returned as-is
     */
    record ReflectionFinished(int cycle, String verdict) implements AgentEvent {
    }
}
