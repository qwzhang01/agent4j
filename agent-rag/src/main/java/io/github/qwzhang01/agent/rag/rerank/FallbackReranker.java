package io.github.qwzhang01.agent.rag.rerank;

import io.github.qwzhang01.agent.rag.Reranker;
import io.github.qwzhang01.agent.rag.model.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Decorator that never throws: when the delegate fails, the first {@code topN} candidates
 * are returned in first-stage order and the outcome is marked degraded.
 * <p>
 * Optional {@code minScore} drops successful rerank results scoring below it. The filtered list
 * may be empty; callers are expected to treat that as "no relevant evidence" and refuse.
 * The threshold is not applied to degraded results, whose scores come from another stage.
 */
public final class FallbackReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(FallbackReranker.class);

    private final Reranker delegate;
    private final Double minScore;

    /** Without score threshold. */
    public FallbackReranker(Reranker delegate) {
        this(delegate, null);
    }

    /**
     * @param delegate reranker to protect
     * @param minScore drop results with rerank score below this; null disables the threshold
     */
    public FallbackReranker(Reranker delegate, Double minScore) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.minScore = minScore;
    }

    /**
     * Result of a protected rerank.
     *
     * @param results  reranked (or fallback) results, best first
     * @param degraded true when the delegate failed and first-stage order was kept
     * @param error    failure description when degraded, null otherwise
     */
    public record Outcome(List<ScoredChunk> results, boolean degraded, String error) {
        public Outcome {
            results = results == null ? List.of() : List.copyOf(results);
        }
    }

    @Override
    public List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topN) {
        return rerankWithOutcome(query, candidates, topN).results();
    }

    /** Like {@link #rerank} but also reports whether the call degraded. */
    public Outcome rerankWithOutcome(String query, List<ScoredChunk> candidates, int topN) {
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty() || topN <= 0) {
            return new Outcome(List.of(), false, null);
        }
        List<ScoredChunk> reranked;
        try {
            reranked = delegate.rerank(query, candidates, topN);
        } catch (RuntimeException e) {
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("rerank degraded to first-stage order ({} candidates): {}", candidates.size(), error);
            return new Outcome(NoOpReranker.head(candidates, topN), true, error);
        }
        if (reranked == null) {
            log.warn("rerank degraded: delegate returned null");
            return new Outcome(NoOpReranker.head(candidates, topN), true, "delegate returned null");
        }
        List<ScoredChunk> kept = reranked.stream()
                .filter(c -> minScore == null || c.score() >= minScore)
                .limit(topN)
                .toList();
        return new Outcome(kept, false, null);
    }

    public Double minScore() {
        return minScore;
    }
}
