package io.github.qwzhang01.agent.orchestrator;

import java.util.List;

/**
 * Strategy for merging multiple {@link WorkerResult}s into one final output
 * (Stage 11 M11.2).
 * <p>
 * Aggregation is the ONLY place where parallel worker outputs meet (D3:
 * message passing -- merge happens at a single point, single-threaded).
 * What "merge" means is a policy decision, hence an interface:
 * <ul>
 *   <li>{@link ConcatAggregator} -- concatenate all outputs (report-style)</li>
 *   <li>{@link FirstSuccessAggregator} -- first successful output (race-style)</li>
 * </ul>
 * Custom policies (voting, LLM summarization, majority vote) plug in the same way.
 */
@FunctionalInterface
public interface ResultAggregator {

    /**
     * Merge worker results into one string. Receives results in task order.
     * Implementations decide how to treat failures (skip / mark / abort).
     */
    String aggregate(List<WorkerResult> results);
}
