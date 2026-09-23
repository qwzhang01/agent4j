package io.github.qwzhang01.agent.observability.online;

import io.github.qwzhang01.agent.observability.metrics.RunMetrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The online window's five-plus-one metrics : computed from real
 * {@link RunMetrics} rows - not synthetic probes, the production traffic
 * itself. This is the "deploy and measure" half the architecture-design
 * ladder was missing.
 * <p>
 * Definitions (fixed here, so dashboards and alarms agree):
 * <ul>
 *   <li>{@code taskCompletionRate} - DONE runs / all runs (MAX_STEPS_EXCEEDED
 *       is not completion, per RunMetrics.succeeded)</li>
 *   <li>{@code costPerTaskMicros} - total cost / ALL runs, attempted tasks:
 *       a failed run burns tokens too, and pretending failures are free is
 *       how cost dashboards start lying</li>
 *   <li>{@code latencyP50/P95/P99Ms} - sample quantiles over run durations</li>
 *   <li>{@code safetyViolationRate} - denied tool calls / tool calls (the
 *       governance chain's denial is the leading injection indicator, F7)</li>
 *   <li>{@code fallbackRate} - model errors / model calls; the closest proxy
 *       RunMetrics carries today. Honest note: which cascade tier actually
 *       served a recovered call is CascadeModelClient's own metric, not yet
 *       projected into the run row (gap)</li>
 *   <li>{@code memoryHitRate} - NULL = unreported. The memory boundary
 *       emits no metrics yet (honest gap); a zero here would be a
 *       fabricated "never hit", the null says "cannot know yet"</li>
 * </ul>
 * An empty window is rejected: an average over nothing is not an honest
 * zero, it is no measurement at all.
 */
public record OnlineMetrics(
        int runs,
        int completedRuns,
        double taskCompletionRate,
        long costPerTaskMicros,
        long latencyP50Ms,
        long latencyP95Ms,
        long latencyP99Ms,
        double safetyViolationRate,
        double fallbackRate,
        Double memoryHitRate) {

    public OnlineMetrics {
        if (runs < 0 || completedRuns < 0 || completedRuns > runs) {
            throw new IllegalArgumentException(
                    "completedRuns must be within [0, runs]: " + completedRuns + "/" + runs);
        }
        if (taskCompletionRate < 0.0 || taskCompletionRate > 1.0
                || safetyViolationRate < 0.0 || safetyViolationRate > 1.0
                || fallbackRate < 0.0 || fallbackRate > 1.0) {
            throw new IllegalArgumentException("rates must be within [0.0, 1.0]");
        }
        if (memoryHitRate != null && (memoryHitRate < 0.0 || memoryHitRate > 1.0)) {
            throw new IllegalArgumentException("memoryHitRate must be within [0.0, 1.0] or null");
        }
    }

    /** Aggregate one evaluation window from its run rows. */
    public static OnlineMetrics from(List<RunMetrics> rows) {
        Objects.requireNonNull(rows, "rows");
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("an empty window is not a window - feed at least one run");
        }
        int completed = 0;
        long totalCost = 0;
        long deniedTools = 0;
        long totalTools = 0;
        long modelErrors = 0;
        long modelCalls = 0;
        List<Long> durations = new ArrayList<>();
        for (RunMetrics m : rows) {
            if (m.succeeded()) {
                completed++;
            }
            totalCost += m.costMicros();
            deniedTools += m.deniedToolCalls();
            totalTools += m.toolCallCount();
            modelErrors += m.modelCallErrors();
            modelCalls += m.modelCallCount();
            if (m.durationMs() >= 0) {
                durations.add(m.durationMs());
            }
        }
        Collections.sort(durations);
        int runs = rows.size();
        return new OnlineMetrics(
                runs,
                completed,
                (double) completed / runs,
                totalCost / runs,
                quantile(durations, 0.50),
                quantile(durations, 0.95),
                quantile(durations, 0.99),
                totalTools == 0 ? 0.0 : (double) deniedTools / totalTools,
                modelCalls == 0 ? 0.0 : (double) modelErrors / modelCalls,
                null);  // memory boundary emits no metrics yet (honest gap)
    }

    /** Whether the memory hit rate is a measurement or an honest blank. */
    public boolean memoryHitRateReported() {
        return memoryHitRate != null;
    }

    /** Sample quantile: ceil(q*n)-th order statistic (the same convention as the Prometheus sink). */
    private static long quantile(List<Long> sorted, double q) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(q * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }
}
