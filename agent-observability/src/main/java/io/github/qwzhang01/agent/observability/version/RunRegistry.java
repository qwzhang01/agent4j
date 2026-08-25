package io.github.qwzhang01.agent.observability.version;

import io.github.qwzhang01.agent.observability.metrics.RunMetrics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Append-only registry of {@link RunRecord}s (Stage 18 D8) - the time-travel
 * query target: "last night's wrong answer ran on WHAT combination" stops
 * being archaeology and becomes {@link #byRunId}.
 * <p>
 * Append-only means exactly that: no updates, no deletes, duplicate runIds
 * rejected (a rewritten history is a fabricated history - the same
 * discipline as Stage 13 prompt versions and Stage 14 trajectories).
 * Persistence (JSONL) is a v2 concern; v1 is the in-memory registry the
 * assembly keeps for the process lifetime.
 */
public final class RunRegistry {

    private final Map<String, RunRecord> byRunId = new HashMap<>();
    private final List<RunRecord> order = new ArrayList<>();

    /** Append one record; duplicate runIds are rejected fail-fast. */
    public synchronized RunRegistry add(RunRecord record) {
        java.util.Objects.requireNonNull(record, "record");
        if (byRunId.containsKey(record.runId())) {
            throw new IllegalArgumentException("runId '" + record.runId() + "' already recorded (append-only)");
        }
        byRunId.put(record.runId(), record);
        order.add(record);
        return this;
    }

    /** Convenience: derive the record from its metrics row + version triple. */
    public synchronized RunRegistry record(List<ComponentVersion> versions, RunMetrics metrics) {
        return add(RunRecord.of(versions, metrics));
    }

    /** The time-travel query: what combination served this run. */
    public synchronized Optional<RunRecord> byRunId(String runId) {
        return Optional.ofNullable(byRunId.get(runId));
    }

    /** All records of one agent, oldest first. */
    public synchronized List<RunRecord> byAgent(String agentName) {
        List<RunRecord> out = new ArrayList<>();
        for (RunRecord record : order) {
            if (record.agentName().equals(agentName)) {
                out.add(record);
            }
        }
        return out;
    }

    /** Everything recorded, oldest first (the honest, ordered history). */
    public synchronized List<RunRecord> all() {
        return List.copyOf(order);
    }

    public synchronized int size() {
        return order.size();
    }
}
