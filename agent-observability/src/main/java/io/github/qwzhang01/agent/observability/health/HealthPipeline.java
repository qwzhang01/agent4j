package io.github.qwzhang01.agent.observability.health;

import io.github.qwzhang01.agent.core.agent.AgentEvent;
import io.github.qwzhang01.agent.observability.metrics.MetricsSink;
import io.github.qwzhang01.agent.observability.metrics.ModelCallMetrics;
import io.github.qwzhang01.agent.observability.metrics.RunMetrics;
import io.github.qwzhang01.agent.observability.metrics.ToolCallMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Online health pipeline (KP7 E7): the five production health indicators
 * computed as a PURE DOWNSTREAM consumer of the existing event fabric -
 * nothing in the production chain is touched, no field is added anywhere.
 * The KP4 homology made literal: a router is a decorator that can be pulled
 * without the framework noticing; this pipeline is a consumer that can be
 * unplugged the same way - it implements two existing interfaces, and no
 * production class imports this package.
 * <p>
 * DUAL subscription, because no single outlet carries all five indicators -
 * the experiment's first-hand finding:
 * <ul>
 *   <li>{@link MetricsSink#onRun} (the numeric projection): completion,
 *       cost, latency, safety - four indicators, but zero content; a
 *       {@code RunMetrics} row is numbers only</li>
 *   <li>the {@link AgentEvent} stream (the content projection):
 *       {@link AgentEvent.Done} carries {@code finalAnswer} - the only
 *       content carrier in the fabric, without which drift is structurally
 *       unmeasurable</li>
 * </ul>
 * The two projections are complementary, not redundant - the same lesson as
 * blueprint D1 (one run, three projections): readers differ, boundaries
 * shared. Evaluation turns out to be a FOURTH projection straddling the
 * numeric and content ones.
 * <p>
 * Drift discipline (v1 honest shape): {@link #snapshot()} is a
 * RECONCILIATION point, not a pure query - each snapshot compares the
 * current content window's average answer length against the previous
 * snapshot's window, then that window becomes the new baseline. A monitor
 * that snapshots every five minutes is diffing adjacent five-minute windows;
 * that sliding semantics is the point and is documented, not hidden. The
 * band itself is a heuristic tripwire ({@value #DRIFT_BAND} around the
 * baseline) - a real deployment swaps in a distribution test; v1 pins the
 * STRUCTURE (content carrier + rolling baseline), not the statistics.
 * <p>
 * Windows are bounded in-memory ring buffers (the last {@code windowSize}
 * rows per projection). Process restart clears them - persistent health
 * history is a v2 concern ({@code RunRegistry} is the natural join).
 */
public final class HealthPipeline implements MetricsSink, Consumer<AgentEvent> {

    private static final Logger log = LoggerFactory.getLogger(HealthPipeline.class);

    /** Default sliding window: the last 100 rows per projection. */
    public static final int DEFAULT_WINDOW = 100;

    /**
     * Heuristic drift band: the current window's average answer length within
     * +/-{@value #DRIFT_BAND} of the previous snapshot's window counts as
     * STABLE. A tripwire, NOT a statistical test.
     */
    static final double DRIFT_BAND = 0.30;

    /**
     * Below this many content samples drift stays BASELINE_BUILDING - a
     * two-sample "distribution" is noise wearing a ratio costume.
     */
    static final int MIN_DRIFT_SAMPLES = 5;

    private final int windowSize;

    // numeric projection: RunMetrics rows (completion/cost/latency/safety)
    private final ArrayDeque<RunMetrics> runWindow = new ArrayDeque<>();

    // content projection: final-answer lengths in chars (drift)
    private final ArrayDeque<Integer> answerCharWindow = new ArrayDeque<>();

    // previous snapshot's window average; null until the first reconciliation
    private Double baselineAvgAnswerChars;

    /** Default window ({@value #DEFAULT_WINDOW} rows per projection). */
    public HealthPipeline() {
        this(DEFAULT_WINDOW);
    }

    /**
     * @param windowSize rows kept per projection (&gt;= 1)
     */
    public HealthPipeline(int windowSize) {
        if (windowSize < 1) {
            throw new IllegalArgumentException("windowSize must be >= 1: " + windowSize);
        }
        this.windowSize = windowSize;
    }

    // MetricsSink: the numeric projection

    /**
     * No-op by design: this pipeline is a downstream consumer of RUNS. Call
     * events were already aggregated per-run upstream in
     * {@code MetricsCollector}; re-counting them here would double-book the
     * same boundary.
     */
    @Override
    public synchronized void onModelCall(ModelCallMetrics metrics) {
        Objects.requireNonNull(metrics, "metrics");
    }

    /** No-op by design - same discipline as {@link #onModelCall}. */
    @Override
    public synchronized void onToolCall(ToolCallMetrics metrics) {
        Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * One finished run entered the window - the numeric projection's feed.
     * The caller is the assembly that materialized the row (typically
     * fanning out what {@code MetricsCollector#endRun} returned).
     */
    @Override
    public synchronized void onRun(RunMetrics metrics) {
        Objects.requireNonNull(metrics, "metrics");
        runWindow.addLast(metrics);
        while (runWindow.size() > windowSize) {
            runWindow.pollFirst();
        }
    }

    // AgentEvent stream: the content projection

    /**
     * Only {@link AgentEvent.Done} is drift material: it carries the final
     * answer - the one content carrier in the event fabric. Deltas, tool
     * results, traces are observations, not answers; the distribution being
     * guarded is "what users actually read".
     */
    @Override
    public synchronized void accept(AgentEvent event) {
        Objects.requireNonNull(event, "event");
        if (event instanceof AgentEvent.Done done) {
            String answer = done.finalAnswer() == null ? "" : done.finalAnswer();
            answerCharWindow.addLast(answer.length());
            while (answerCharWindow.size() > windowSize) {
                answerCharWindow.pollFirst();
            }
        }
    }

    /**
     * Materialize the health report over the current windows.
     * <p>
     * Reconciliation side effect (documented, not hidden): the content
     * window's average becomes the drift BASELINE for the next snapshot.
     * Two snapshots with no data in between therefore differ in their drift
     * section (the second reports STABLE at ratio 1.0) - deterministic, but
     * not idempotent; that is the sliding-baseline semantics.
     *
     * @return the five-indicator report with coverage labels
     * @throws IllegalArgumentException no runs observed yet - an empty
     *                                  window is not an eval (the same
     *                                  convention as {@code EvaluationRunner})
     */
    public synchronized HealthReport snapshot() {
        if (runWindow.isEmpty()) {
            throw new IllegalArgumentException(
                    "no runs observed yet - an empty window is not an eval (feed onRun first)");
        }
        List<RunMetrics> runs = List.copyOf(runWindow);
        long completed = runs.stream().filter(RunMetrics::succeeded).count();
        long totalCostMicros = runs.stream().mapToLong(RunMetrics::costMicros).sum();
        List<Long> durations = runs.stream().mapToLong(RunMetrics::durationMs).sorted()
                .boxed().toList();
        long denied = runs.stream().mapToLong(RunMetrics::deniedToolCalls).sum();
        long modelErrors = runs.stream().mapToLong(RunMetrics::modelCallErrors).sum();
        return new HealthReport(
                runs.size(),
                completed,
                (double) completed / runs.size(),
                totalCostMicros,
                (double) totalCostMicros / runs.size(),
                nearestRank(durations, 50),
                nearestRank(durations, 95),
                denied,
                modelErrors,
                driftSnapshot());
    }

    /**
     * Drift section of the snapshot - and the reconciliation itself: this
     * window's average becomes the next comparison's baseline.
     */
    private HealthReport.Drift driftSnapshot() {
        int samples = answerCharWindow.size();
        if (samples == 0) {
            // no content at all: honest BASELINE_BUILDING, not a fake STABLE
            return new HealthReport.Drift(HealthReport.Drift.Status.BASELINE_BUILDING,
                    0, 0.0, 0.0, 0.0);
        }
        double current = answerCharWindow.stream().mapToInt(Integer::intValue)
                .average().orElse(0.0);
        Double baseline = baselineAvgAnswerChars;
        if (baseline == null || samples < MIN_DRIFT_SAMPLES) {
            baselineAvgAnswerChars = current;
            return new HealthReport.Drift(HealthReport.Drift.Status.BASELINE_BUILDING,
                    samples, current, current, 1.0);
        }
        double ratio = baseline == 0.0
                ? (current == 0.0 ? 1.0 : Double.POSITIVE_INFINITY)
                : current / baseline;
        boolean withinBand = Double.isFinite(ratio) && Math.abs(ratio - 1.0) <= DRIFT_BAND;
        baselineAvgAnswerChars = current;
        if (!withinBand) {
            log.info("drift tripwire: avg answer chars {} -> {} (ratio {}), investigate",
                    (long) baseline.doubleValue(), (long) current, String.format("%.3f", ratio));
        }
        return new HealthReport.Drift(
                withinBand ? HealthReport.Drift.Status.STABLE : HealthReport.Drift.Status.SUSPECTED,
                samples, current, baseline, ratio);
    }

    /**
     * Nearest-rank percentile over a SORTED ascending list:
     * rank = ceil(p% of n), index = rank - 1. Deterministic, interpolation
     * controversies excluded by definition.
     */
    private static long nearestRank(List<Long> sorted, int p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.min(rank, sorted.size()) - 1);
    }
}
