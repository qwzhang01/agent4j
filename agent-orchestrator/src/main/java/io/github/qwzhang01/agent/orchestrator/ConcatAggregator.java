package io.github.qwzhang01.agent.orchestrator;

import java.util.List;

/**
 * Default aggregation: concatenate every worker's output, failures marked inline
 * (Stage 11 M11.2). Report-style -- every voice is heard.
 * <p>
 * Output shape:
 * <pre>
 * [researcher] Found 3 candidate libraries...
 *
 * [executor] Wrote demo code for option A...
 *
 * [reviewer] FAILED: RuntimeException: connection refused
 * </pre>
 */
public class ConcatAggregator implements ResultAggregator {

    @Override
    public String aggregate(List<WorkerResult> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (WorkerResult r : results) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append('[').append(r.workerName()).append("] ");
            if (r.success()) {
                sb.append(r.output() != null ? r.output() : "");
            } else {
                sb.append("FAILED: ").append(r.error());
            }
        }
        return sb.toString();
    }
}
