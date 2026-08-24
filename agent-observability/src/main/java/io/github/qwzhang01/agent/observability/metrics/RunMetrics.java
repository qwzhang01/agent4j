package io.github.qwzhang01.agent.observability.metrics;

import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ModelResponse;

import java.util.Objects;

/**
 * Run-level summary row - the on-call engineer's one-screen answer for
 * "what happened on this run" (Stage 18 D1: the operations projection of a run).
 * <p>
 * Materialized by {@link MetricsCollector#endRun} from the boundary events
 * captured during the run. The training projection of the same run is the
 * Stage 14 {@code Trajectory}; the governance projection is the Stage 9
 * {@code AuditEvent} stream.
 *
 * @param runId            run identifier
 * @param agentName        agent name given at {@code beginRun}
 * @param status           terminal status from {@link AgentState}
 * @param lastError        last error text (null unless failed) - carries the
 *                         doneReason semantics for failures
 * @param durationMs       wall-clock duration from beginRun to endRun
 * @param modelCallCount   total model calls (errors included)
 * @param modelCallErrors  model calls that threw
 * @param toolCallCount    total tool calls (denied included)
 * @param deniedToolCalls  tool calls blocked by the governance chain
 * @param tokenUsage       summed token usage across model calls
 * @param costMicros       summed cost in microUSD; 0 until M18.2 wires the
 *                         {@code CostMeter} (honest placeholder, not a lie:
 *                         no pricing table configured means no cost computed)
 */
public record RunMetrics(
        String runId,
        String agentName,
        AgentState.Status status,
        String lastError,
        long durationMs,
        int modelCallCount,
        int modelCallErrors,
        int toolCallCount,
        int deniedToolCalls,
        ModelResponse.TokenUsage tokenUsage,
        long costMicros) {

    public RunMetrics {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(agentName, "agentName");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(tokenUsage, "tokenUsage");
    }

    /** Only a {@code DONE} run counts as succeeded (MAX_STEPS_EXCEEDED does not). */
    public boolean succeeded() {
        return status == AgentState.Status.DONE;
    }
}
