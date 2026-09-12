package io.github.qwzhang01.agent.observability.health;

import java.util.Objects;

/**
 * Online health snapshot over one evaluation window (KP7 E7) - the five
 * production health indicators, each with its HONEST coverage label.
 * <p>
 * Five indicators, three coverage levels (the KP7 two-layer doctrine made
 * structural instead of documentary):
 * <ul>
 *   <li>process completion rate - PROCESS layer observable: a DONE loop says
 *       the machine stopped cleanly, NOT that the task was actually solved.
 *       The outcome layer lives in {@code Expectation} assertions and human
 *       sampling (the offline path, {@code EvaluationRunner}) - conflating
 *       the two is how dashboards start lying</li>
 *   <li>cost per task - fully observable from {@code RunMetrics.costMicros};
 *       a 0 means no pricing table was wired (honest placeholder, the same
 *       convention as {@code RunMetrics} itself - check {@link #costKnown})
 *       and must not be read as "free"</li>
 *   <li>latency P50/P95 - nearest-rank percentiles over run durations</li>
 *   <li>safety signals - denied tool calls (governance chain) plus model
 *       call errors; process-layer proxies, NOT a safety verdict</li>
 *   <li>output drift - the ONLY indicator that needs a CONTENT carrier (the
 *       final answer text), fed from {@code AgentEvent.Done}; v1 compares
 *       average answer length against the previous snapshot's window within
 *       a heuristic band - a tripwire, not a statistic</li>
 * </ul>
 * Reproducibility (the discipline inherited from {@code EvalReport}): no
 * timestamps, no ambient state - the same window and the same snapshot
 * sequence yield equal reports.
 *
 * @param windowRuns              runs in the numeric window (&gt; 0)
 * @param completedRuns           runs that reached DONE
 * @param processCompletionRate   DONE fraction in [0.0, 1.0] - process layer only
 * @param totalCostMicros         summed microUSD over the window
 * @param avgCostPerTaskMicros    totalCostMicros / windowRuns
 * @param latencyP50Ms            nearest-rank P50 of run durations
 * @param latencyP95Ms            nearest-rank P95 of run durations
 * @param deniedToolCalls         tool calls blocked by the governance chain
 * @param modelCallErrors         model calls that threw
 * @param drift                   output-distribution signal (never null)
 */
public record HealthReport(
        int windowRuns,
        long completedRuns,
        double processCompletionRate,
        long totalCostMicros,
        double avgCostPerTaskMicros,
        long latencyP50Ms,
        long latencyP95Ms,
        long deniedToolCalls,
        long modelCallErrors,
        Drift drift) {

    public HealthReport {
        if (windowRuns <= 0) {
            throw new IllegalArgumentException(
                    "windowRuns must be positive - an empty window is not an eval");
        }
        if (completedRuns < 0 || completedRuns > windowRuns) {
            throw new IllegalArgumentException("completedRuns out of range: " + completedRuns);
        }
        if (processCompletionRate < 0.0 || processCompletionRate > 1.0) {
            throw new IllegalArgumentException(
                    "processCompletionRate out of [0.0, 1.0]: " + processCompletionRate);
        }
        if (totalCostMicros < 0 || avgCostPerTaskMicros < 0 || latencyP50Ms < 0 || latencyP95Ms < 0
                || deniedToolCalls < 0 || modelCallErrors < 0) {
            throw new IllegalArgumentException("metric counters must not be negative");
        }
        Objects.requireNonNull(drift, "drift");
        if (latencyP50Ms > latencyP95Ms) {
            throw new IllegalArgumentException("P50 must not exceed P95");
        }
    }

    /**
     * Is the cost figure trustworthy? {@code false} means no pricing was
     * wired - the zeros are placeholders, not bargains.
     */
    public boolean costKnown() {
        return totalCostMicros > 0;
    }

    /**
     * Output-length drift signal - the content-carrier indicator.
     * <p>
     * v1 shape (deliberately honest): the status comes from comparing the
     * current window's average answer length against the PREVIOUS snapshot's
     * window. {@link Status#SUSPECTED} means "investigate", never
     * "page someone" - the band is a heuristic, not a distribution test.
     *
     * @param status                 tripwire verdict for this window
     * @param sampleCount            final answers in the content window
     * @param currentAvgAnswerChars  average answer length this window
     * @param baselineAvgAnswerChars average answer length the previous
     *                               snapshot reconciled to
     * @param ratio                  current / baseline (positive infinity when
     *                               the baseline was 0 and output appeared;
     *                               1.0 while building)
     */
    public record Drift(Status status, int sampleCount,
                        double currentAvgAnswerChars, double baselineAvgAnswerChars, double ratio) {

        public Drift {
            Objects.requireNonNull(status, "status");
            if (sampleCount < 0 || currentAvgAnswerChars < 0
                    || baselineAvgAnswerChars < 0 || ratio < 0) {
                throw new IllegalArgumentException("drift fields must not be negative");
            }
        }

        public enum Status {
            /** Not enough content yet - the first snapshots BUILD the baseline. */
            BASELINE_BUILDING,
            /** Within the heuristic band around the previous snapshot's window. */
            STABLE,
            /** Outside the band - investigate; a tripwire, not a verdict. */
            SUSPECTED
        }
    }
}
