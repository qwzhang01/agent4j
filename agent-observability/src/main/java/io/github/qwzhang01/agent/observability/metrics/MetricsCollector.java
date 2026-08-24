package io.github.qwzhang01.agent.observability.metrics;

import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.observability.cost.CostMeter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory aggregator turning boundary events into run-level summaries
 * (Stage 18 M18.1).
 * <p>
 * Implements {@link MetricsSink} so the observing decorators can feed it
 * directly; on top of the sink contract it owns the RUN concept:
 * {@link #beginRun} opens a thread-local run context, every subsequent
 * boundary event on the same thread is attributed to it, and
 * {@link #endRun} materializes the {@link RunMetrics} summary row.
 * <p>
 * Events arriving OUTSIDE any run context (bare decorator usage, async
 * callbacks) are not dropped - they count into the global totals
 * ({@link #totalModelCalls} / {@link #totalToolCalls}). Operations accounting
 * does not cherry-pick runs: a token burned outside a run is still a token.
 * <p>
 * Thread discipline (v1 honest boundary, same as Stage 14 recording sessions):
 * the run context is thread-bound. Inner decorators that hop threads (e.g.
 * timeout wrappers) will detach events from the run - keep the observing
 * decorators outermost, or accept orphan attribution.
 * <p>
 * {@link #onRun} is a no-op: this class is the PRODUCER of RunMetrics rows,
 * not a consumer; external sinks (console / JSONL) receive them via their own
 * onRun subscription if the assembly wires one.
 */
public final class MetricsCollector implements MetricsSink {

    private static final Logger log = LoggerFactory.getLogger(MetricsCollector.class);

    private static final ThreadLocal<String> CURRENT_RUN = new ThreadLocal<>();

    private final Map<String, RunAccumulator> runs = new LinkedHashMap<>();
    private final CostMeter costMeter;
    private long orphanModelCalls;
    private long orphanToolCalls;

    // ============ Construction ============

    /**
     * No cost accounting: {@code RunMetrics.costMicros} stays 0 (metrics-only
     * wiring; cost flows through {@code BudgetBook} instead).
     */
    public MetricsCollector() {
        this(null);
    }

    /**
     * With cost accounting (M18.2 wiring): model calls are priced as they arrive
     * and the run summary carries the summed microUSD.
     * <p>
     * Unpriced models are deliberately NOT fatal here: the meter's fail-loud
     * {@code IllegalArgumentException} is caught, logged at warn and the call
     * contributes 0 cost - metrics/cost are a side channel and must not blow up
     * the run they observe (CostMeter's own contract stays fail-loud for direct
     * callers; the aggregation path chooses the side-channel discipline).
     */
    public MetricsCollector(CostMeter costMeter) {
        this.costMeter = costMeter;
    }

    // ============ Run lifecycle ============

    /**
     * Open a run context on the current thread; boundary events until
     * {@link #endRun} are attributed to this run.
     *
     * @throws IllegalArgumentException blank ids, nested run on this thread
     *                                  (one active run per thread, same
     *                                  discipline as Stage 14), or reused runId
     */
    public synchronized void beginRun(String runId, String agentName) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be null or blank");
        }
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalArgumentException("agentName must not be null or blank");
        }
        String active = CURRENT_RUN.get();
        if (active != null) {
            throw new IllegalArgumentException(
                    "nested runs on one thread are not supported (already in run '" + active + "')");
        }
        if (runs.containsKey(runId)) {
            throw new IllegalArgumentException("runId '" + runId + "' already used (runIds are unique)");
        }
        runs.put(runId, new RunAccumulator(runId, agentName, costMeter));
        CURRENT_RUN.set(runId);
    }

    /**
     * Close the run context and materialize its {@link RunMetrics}.
     *
     * @param status    terminal status from {@link AgentState}
     * @param lastError last error text, null on success (carries doneReason
     *                  semantics for failures)
     * @return the materialized summary row (also queryable via {@link #runMetrics})
     * @throws IllegalArgumentException no active run on this thread
     */
    public synchronized RunMetrics endRun(AgentState.Status status, String lastError) {
        String runId = CURRENT_RUN.get();
        if (runId == null) {
            throw new IllegalArgumentException("no active run on this thread (beginRun first)");
        }
        CURRENT_RUN.remove();
        RunAccumulator acc = runs.get(runId);
        return acc.finish(status, lastError);
    }

    // ============ MetricsSink ============

    @Override
    public synchronized void onModelCall(ModelCallMetrics metrics) {
        String runId = CURRENT_RUN.get();
        if (runId == null) {
            orphanModelCalls++;
            return;
        }
        runs.get(runId).addModelCall(metrics);
    }

    @Override
    public synchronized void onToolCall(ToolCallMetrics metrics) {
        String runId = CURRENT_RUN.get();
        if (runId == null) {
            orphanToolCalls++;
            return;
        }
        runs.get(runId).addToolCall(metrics);
    }

    /** No-op: this collector produces RunMetrics rows, it does not consume them. */
    @Override
    public synchronized void onRun(RunMetrics metrics) {
        // producer, not consumer - see class javadoc
    }

    // ============ Queries ============

    /** Materialized summary of a FINISHED run; empty while the run is active or unknown. */
    public synchronized Optional<RunMetrics> runMetrics(String runId) {
        RunAccumulator acc = runs.get(runId);
        return acc == null || acc.finished == null ? Optional.empty() : Optional.of(acc.finished);
    }

    /** Finished runs of one agent, oldest first. */
    public synchronized List<RunMetrics> byAgent(String agentName) {
        List<RunMetrics> out = new ArrayList<>();
        for (RunAccumulator acc : runs.values()) {
            if (acc.finished != null && acc.agentName.equals(agentName)) {
                out.add(acc.finished);
            }
        }
        return out;
    }

    /** Success-rate statistics over one agent's finished runs. */
    public synchronized AgentStats agentStats(String agentName) {
        int total = 0;
        int succeeded = 0;
        for (RunAccumulator acc : runs.values()) {
            if (acc.finished != null && acc.agentName.equals(agentName)) {
                total++;
                if (acc.finished.succeeded()) {
                    succeeded++;
                }
            }
        }
        return new AgentStats(agentName, total, succeeded, total - succeeded,
                total == 0 ? 0.0 : (double) succeeded / total);
    }

    /** All model-call events counted so far, run-attributed AND orphaned. */
    public synchronized long totalModelCalls() {
        long inRuns = runs.values().stream().mapToLong(a -> a.modelCalls).sum();
        return inRuns + orphanModelCalls;
    }

    /** All tool-call events counted so far, run-attributed AND orphaned. */
    public synchronized long totalToolCalls() {
        long inRuns = runs.values().stream().mapToLong(a -> a.toolCalls).sum();
        return inRuns + orphanToolCalls;
    }

    // ============ Nested ============

    /** Per-agent success-rate statistics (the "task success rate" acceptance metric). */
    public record AgentStats(String agentName, int totalRuns, int succeededRuns,
                             int failedRuns, double successRate) {
    }

    /** Mutable per-run accumulator; {@link #finish} freezes it into a RunMetrics. */
    private static final class RunAccumulator {
        private final String runId;
        private final String agentName;
        private final CostMeter costMeter;
        private final long startNanos = System.nanoTime();

        private int modelCalls;
        private int modelErrors;
        private int toolCalls;
        private int deniedTools;
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;
        private long costMicros;
        private RunMetrics finished;

        RunAccumulator(String runId, String agentName, CostMeter costMeter) {
            this.runId = runId;
            this.agentName = agentName;
            this.costMeter = costMeter;
        }

        void addModelCall(ModelCallMetrics m) {
            modelCalls++;
            promptTokens += m.promptTokens();
            completionTokens += m.completionTokens();
            totalTokens += m.totalTokens();
            if (!m.success()) {
                modelErrors++;
            }
            if (costMeter != null) {
                try {
                    costMicros += costMeter.costMicros(m);
                } catch (IllegalArgumentException e) {
                    log.warn("no pricing for model '{}', cost recorded as 0 ({})",
                            m.model(), e.getMessage());
                }
            }
        }

        void addToolCall(ToolCallMetrics t) {
            toolCalls++;
            if (t.denied()) {
                deniedTools++;
            }
        }

        RunMetrics finish(AgentState.Status status, String lastError) {
            if (finished != null) {
                throw new IllegalStateException("run '" + runId + "' already finished");
            }
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            finished = new RunMetrics(runId, agentName, status, lastError, durationMs,
                    modelCalls, modelErrors, toolCalls, deniedTools,
                    new ModelResponse.TokenUsage(promptTokens, completionTokens, totalTokens),
                    costMicros);
            return finished;
        }
    }
}
