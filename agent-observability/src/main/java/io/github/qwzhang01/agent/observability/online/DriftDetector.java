package io.github.qwzhang01.agent.observability.online;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Drift detection with threshold alarms : compare the current
 * online window against a baseline row and fire when a metric crosses its
 * configured line. Detection is threshold-based, not statistical - the
 * honest v1: no fancy distribution tests without the traffic volume to
 * justify them, the same discipline as the roadmap's own gap notes.
 * <p>
 * Every alarm carries a RECOMMENDED ACTION, not just a number (the 7.4
 * discipline applied here early): an alarm that says "completion 82%" is a
 * dashboard, an alarm that says "completion 82%, below the 90% floor -
 * check the latest prompt version and the golden set" is an operator.
 * <p>
 * Baseline semantics: the previous window's row (or a hand-configured SLO
 * row). Deltas are current minus baseline.
 */
public final class DriftDetector {

    private DriftDetector() {
    }

    /** One fired drift alarm with its recommended action. */
    public record DriftAlarm(
            String metric,
            double currentValue,
            double baselineValue,
            double delta,
            String threshold,
            String recommendedAction) {
    }

    /** Thresholds for one watch; unset lines are simply not watched. */
    public static final class Thresholds {
        private double minCompletionRate = -1;
        private double maxSafetyViolationRate = -1;
        private double maxFallbackRate = -1;
        private double maxCostPerTaskMicros = -1;
        private long maxLatencyP95Ms = -1;

        /** Completion floor (e.g. 0.90); below fires. */
        public Thresholds minCompletionRate(double rate) {
            this.minCompletionRate = rate;
            return this;
        }

        /** Safety ceiling (e.g. 0.02); above fires. */
        public Thresholds maxSafetyViolationRate(double rate) {
            this.maxSafetyViolationRate = rate;
            return this;
        }

        /** Fallback ceiling (e.g. 0.05); above fires. */
        public Thresholds maxFallbackRate(double rate) {
            this.maxFallbackRate = rate;
            return this;
        }

        /** Cost-per-task ceiling in microUSD. */
        public Thresholds maxCostPerTaskMicros(long micros) {
            this.maxCostPerTaskMicros = micros;
            return this;
        }

        /** P95 latency ceiling in ms. */
        public Thresholds maxLatencyP95Ms(long ms) {
            this.maxLatencyP95Ms = ms;
            return this;
        }
    }

    /**
     * Check the current window against the baseline under the thresholds.
     *
     * @return fired alarms (empty = healthy); ordered completion, cost,
     *         latency, safety, fallback
     */
    public static List<DriftAlarm> check(OnlineMetrics current, OnlineMetrics baseline,
                                         Thresholds thresholds) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(thresholds, "thresholds");
        List<DriftAlarm> alarms = new ArrayList<>();

        if (thresholds.minCompletionRate >= 0
                && current.taskCompletionRate() < thresholds.minCompletionRate) {
            alarms.add(new DriftAlarm(
                    "taskCompletionRate",
                    current.taskCompletionRate(), baseline.taskCompletionRate(),
                    current.taskCompletionRate() - baseline.taskCompletionRate(),
                    "min " + thresholds.minCompletionRate,
                    "check the latest prompt/model version against the golden set; "
                            + "compare with the shadow window if one is running"));
        }
        if (thresholds.maxCostPerTaskMicros >= 0
                && current.costPerTaskMicros() > thresholds.maxCostPerTaskMicros) {
            alarms.add(new DriftAlarm(
                    "costPerTaskMicros",
                    current.costPerTaskMicros(), baseline.costPerTaskMicros(),
                    current.costPerTaskMicros() - baseline.costPerTaskMicros(),
                    "max " + thresholds.maxCostPerTaskMicros,
                    "inspect the cost breakdown by model and tool (RunRegistry "
                            + "combination query); consider budget caps on the hot axis"));
        }
        if (thresholds.maxLatencyP95Ms >= 0
                && current.latencyP95Ms() > thresholds.maxLatencyP95Ms) {
            alarms.add(new DriftAlarm(
                    "latencyP95Ms",
                    current.latencyP95Ms(), baseline.latencyP95Ms(),
                    current.latencyP95Ms() - baseline.latencyP95Ms(),
                    "max " + thresholds.maxLatencyP95Ms,
                    "check slow tools in the tool latency quantiles and provider "
                            + "latency series; consider routing heavy calls to a faster tier"));
        }
        if (thresholds.maxSafetyViolationRate >= 0
                && current.safetyViolationRate() > thresholds.maxSafetyViolationRate) {
            alarms.add(new DriftAlarm(
                    "safetyViolationRate",
                    current.safetyViolationRate(), baseline.safetyViolationRate(),
                    current.safetyViolationRate() - baseline.safetyViolationRate(),
                    "max " + thresholds.maxSafetyViolationRate,
                    "review denied tool-call details for injection patterns; "
                            + "re-run the red team suite against the current prompt version"));
        }
        if (thresholds.maxFallbackRate >= 0
                && current.fallbackRate() > thresholds.maxFallbackRate) {
            alarms.add(new DriftAlarm(
                    "fallbackRate",
                    current.fallbackRate(), baseline.fallbackRate(),
                    current.fallbackRate() - baseline.fallbackRate(),
                    "max " + thresholds.maxFallbackRate,
                    "inspect provider error series (by model) and recent provider "
                            + "incidents; verify the cascade tiers are reachable"));
        }
        return alarms;
    }
}
