package io.github.qwzhang01.agent.observability.online;

import io.github.qwzhang01.agent.observability.metrics.RunMetrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Shadow run and version comparison (Stage 7.3 "shadow run 和版本对照"):
 * replay-shaped comparison of two traffic slices - the live slice (what
 * production serves now) and a shadow slice (a candidate combination, or
 * another prompt/model/tool version) - over the same metrics definitions.
 * <p>
 * "Shadow" here is honest about what this module can see: the in-process
 * harness does not duplicate production traffic to a second agent (that is
 * an assembly/infra concern - a real shadow needs a router that mirrors
 * requests). What evaluation NEEDS from a shadow is the comparison of two
 * windows under identical definitions; this class is that comparison, and
 * an assembly running a duplicate pipeline feeds it the two slices.
 * <p>
 * Versions ride in via {@link io.github.qwzhang01.agent.observability.version.RunRecord}
 * keys: callers group their rows by the combination that served them and
 * hand the groups here (the registry already records the triple).
 */
public final class VersionComparator {

    private VersionComparator() {
    }

    /**
     * Compare two metric rows field by field. The deltas are directional
     * (candidate minus live): a positive cost delta means the candidate is
     * MORE expensive; a positive completion delta means the candidate
     * completes MORE. No verdict is attached here - the release gate's
     * verdict discipline (EvalReport) stays with the golden set; this is
     * the operations comparison.
     */
    public record Comparison(
            OnlineMetrics live,
            OnlineMetrics shadow,
            double completionRateDelta,
            long costPerTaskDeltaMicros,
            long latencyP50DeltaMs,
            long latencyP95DeltaMs,
            double safetyViolationRateDelta,
            double fallbackRateDelta) {

        /** Human one-liner for the ops dashboard. */
        public String summary() {
            return "shadow vs live: completion " + String.format("%+.1f%%", completionRateDelta * 100)
                    + ", cost/task " + (costPerTaskDeltaMicros >= 0 ? "+" : "") + costPerTaskDeltaMicros
                    + "µ$, p50 " + (latencyP50DeltaMs >= 0 ? "+" : "") + latencyP50DeltaMs + "ms"
                    + ", safety " + String.format("%+.2f%%", safetyViolationRateDelta * 100)
                    + ", fallback " + String.format("%+.2f%%", fallbackRateDelta * 100);
        }
    }

    /** Compare live vs shadow windows (both must be non-empty). */
    public static Comparison compare(List<RunMetrics> live, List<RunMetrics> shadow) {
        OnlineMetrics liveRow = OnlineMetrics.from(live);
        OnlineMetrics shadowRow = OnlineMetrics.from(shadow);
        return compareRows(liveRow, shadowRow);
    }

    /** Compare pre-aggregated rows (for windows loaded from persistence). */
    public static Comparison compareRows(OnlineMetrics live, OnlineMetrics shadow) {
        Objects.requireNonNull(live, "live");
        Objects.requireNonNull(shadow, "shadow");
        return new Comparison(
                live,
                shadow,
                shadow.taskCompletionRate() - live.taskCompletionRate(),
                shadow.costPerTaskMicros() - live.costPerTaskMicros(),
                shadow.latencyP50Ms() - live.latencyP50Ms(),
                shadow.latencyP95Ms() - live.latencyP95Ms(),
                shadow.safetyViolationRate() - live.safetyViolationRate(),
                shadow.fallbackRate() - live.fallbackRate());
    }

    /**
     * Group run rows by the version combination that served them - the
     * "which combination did this batch" half of version comparison. Keyed
     * by the human-readable combination string (RunRecord.combination()).
     */
    public static Map<String, OnlineMetrics> byCombination(
            Map<String, List<RunMetrics>> combinationGroups) {
        Objects.requireNonNull(combinationGroups, "combinationGroups");
        Map<String, OnlineMetrics> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<RunMetrics>> e : combinationGroups.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) {
                continue;  // an empty group is no group: skip honestly
            }
            out.put(e.getKey(), OnlineMetrics.from(e.getValue()));
        }
        return out;
    }

    /** Convenience: build the live/shadow pair for one key each. */
    public static Optional<Comparison> compareByKey(
            Map<String, List<RunMetrics>> groups, String liveKey, String shadowKey) {
        List<RunMetrics> live = groups.get(liveKey);
        List<RunMetrics> shadow = groups.get(shadowKey);
        if (live == null || live.isEmpty() || shadow == null || shadow.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(compare(live, shadow));
    }

    /** Keep only rows matching one agent (dashboard filter helper). */
    public static List<RunMetrics> forAgent(List<RunMetrics> rows, String agentName) {
        List<RunMetrics> out = new ArrayList<>();
        for (RunMetrics r : rows) {
            if (r.agentName().equals(agentName)) {
                out.add(r);
            }
        }
        return out;
    }
}
