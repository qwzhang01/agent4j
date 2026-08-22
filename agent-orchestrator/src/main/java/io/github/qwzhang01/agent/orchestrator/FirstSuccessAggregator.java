package io.github.qwzhang01.agent.orchestrator;

import java.util.List;

/**
 * Race-style aggregation: return the FIRST successful output, ignore the rest
 * (Stage 11 M11.2). Use when several workers can produce an equivalent answer
 * and speed matters more than completeness (e.g. three mirrors of the same
 * retrieval, first one back wins).
 * <p>
 * Order matters: results are scanned in task order, so "first" means the first
 * successful task in the dispatch list, not the first to finish.
 */
public class FirstSuccessAggregator implements ResultAggregator {

    static final String NO_SUCCESS = "[no successful results]";

    @Override
    public String aggregate(List<WorkerResult> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        for (WorkerResult r : results) {
            if (r.success()) {
                return r.output();
            }
        }
        return NO_SUCCESS;
    }
}
