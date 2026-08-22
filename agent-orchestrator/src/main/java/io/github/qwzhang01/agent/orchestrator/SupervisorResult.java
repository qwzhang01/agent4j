package io.github.qwzhang01.agent.orchestrator;

import java.util.List;

/**
 * The supervisor's receipt for one {@code dispatchAll} call (Stage 11 M11.2).
 * <p>
 * Carries BOTH the per-worker detail (auditable, per D4 failure isolation the
 * caller must be able to see who failed and why) and the merged final output.
 *
 * @param allSucceeded whether every task succeeded (failed == 0)
 * @param totalTasks    how many tasks were dispatched
 * @param succeeded     how many succeeded
 * @param failed        how many failed (unknown workers included)
 * @param aggregated    the {@link ResultAggregator}'s merged output
 * @param results       per-task results, in dispatch order (immutable copy)
 * @param durationMs    wall-clock time of the whole dispatch
 */
public record SupervisorResult(
        boolean allSucceeded,
        int totalTasks,
        int succeeded,
        int failed,
        String aggregated,
        List<WorkerResult> results,
        long durationMs
) {

    public SupervisorResult {
        results = List.copyOf(results);
    }

    public static SupervisorResult of(List<WorkerResult> results,
                                      String aggregated, long durationMs) {
        int succeeded = 0;
        for (WorkerResult r : results) {
            if (r.success()) {
                succeeded++;
            }
        }
        return new SupervisorResult(
                succeeded == results.size(),
                results.size(),
                succeeded,
                results.size() - succeeded,
                aggregated,
                results,
                durationMs);
    }
}
