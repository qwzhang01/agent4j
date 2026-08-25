package io.github.qwzhang01.agent.observability.version;

import io.github.qwzhang01.agent.observability.metrics.RunMetrics;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * "What combination served this run" - the single source of truth for the
 * reproducibility question (Stage 18 D8): the version triple plus the run's
 * metrics summary row.
 * <p>
 * The {@link RunMetrics} summary already carries {@code costMicros} (the
 * M18.2 wiring prices it), so the record does not duplicate the field - two
 * copies of one number is how audits start lying. This is also the
 * operations-readable complement to Stage 14 {@code TrajectoryMetadata}
 * (which stores fingerprints for training consumers): trajectories store
 * hashes, RunRecords store human-readable versions.
 *
 * @param runId     run identifier
 * @param agentName agent name
 * @param versions  the PROMPT/MODEL/TOOL triple (any subset recorded)
 * @param metrics   the run's summary row (status/tokens/cost/duration)
 */
public record RunRecord(String runId, String agentName, List<ComponentVersion> versions,
                        RunMetrics metrics) {

    public RunRecord {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be null or blank");
        }
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalArgumentException("agentName must not be null or blank");
        }
        Objects.requireNonNull(versions, "versions");
        Objects.requireNonNull(metrics, "metrics");
        versions = List.copyOf(versions);
    }

    /** Derive runId/agentName from the metrics row itself (they travel together). */
    public static RunRecord of(List<ComponentVersion> versions, RunMetrics metrics) {
        return new RunRecord(metrics.runId(), metrics.agentName(), versions, metrics);
    }

    /** Human-readable combination, e.g. "PROMPT support-system@v3[canary], MODEL premium, TOOL core@f1". */
    public String combination() {
        return versions.stream()
                .map(v -> v.kind() + " " + v.name() + "@" + v.version()
                        + (v.channel() != null ? "[" + v.channel() + "]" : ""))
                .collect(Collectors.joining(", "));
    }
}
