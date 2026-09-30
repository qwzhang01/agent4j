package io.github.qwzhang01.agent.rag.retrieve;

import io.github.qwzhang01.agent.rag.FusionStrategy;
import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.ScoredChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reciprocal Rank Fusion: {@code score(c) = Σ w_i / (k + rank_i(c))} over the input lists that
 * contain {@code c}, with 1-based ranks. Only ranks matter, so lists with incomparable score
 * scales (BM25, cosine) fuse without normalization.
 * <p>
 * Ties are broken by the best individual rank, then by chunk id. A chunk repeated within one
 * list counts once, at its best rank. Output chunks carry every input signal plus {@code rrf}.
 */
public final class ReciprocalRankFusion implements FusionStrategy {

    public static final int DEFAULT_K = 60;

    private final int k;
    private final double[] weights;

    /** RRF with {@code k = 60} and equal weights. */
    public ReciprocalRankFusion() {
        this(DEFAULT_K);
    }

    /** RRF with equal weights. */
    public ReciprocalRankFusion(int k) {
        this(k, (double[]) null);
    }

    /**
     * @param k       rank damping constant, {@code >= 1}; larger values flatten the head of each list
     * @param weights one non-negative weight per input list, in order; {@link #fuse} then requires
     *                exactly that many lists. Null or empty for equal weights.
     */
    public ReciprocalRankFusion(int k, double... weights) {
        if (k < 1) {
            throw new IllegalArgumentException("k must be >= 1: " + k);
        }
        if (weights != null) {
            for (double w : weights) {
                if (w < 0 || Double.isNaN(w)) {
                    throw new IllegalArgumentException("weights must be non-negative: " + w);
                }
            }
        }
        this.k = k;
        this.weights = weights == null || weights.length == 0 ? null : weights.clone();
    }

    public int k() {
        return k;
    }

    @Override
    public List<ScoredChunk> fuse(List<List<ScoredChunk>> rankings, int topK) {
        if (rankings == null || rankings.isEmpty() || topK <= 0) {
            return List.of();
        }
        if (weights != null && weights.length != rankings.size()) {
            throw new IllegalArgumentException("expected " + weights.length + " rankings, got " + rankings.size());
        }
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (int list = 0; list < rankings.size(); list++) {
            List<ScoredChunk> ranking = rankings.get(list);
            if (ranking == null) {
                continue;
            }
            double weight = weights == null ? 1.0 : weights[list];
            Set<String> seen = new HashSet<>();
            int rank = 0;
            for (ScoredChunk hit : ranking) {
                String id = hit.chunk().chunkId();
                if (!seen.add(id)) {
                    continue;
                }
                rank++;
                Entry entry = entries.computeIfAbsent(id, key -> new Entry(hit.chunk()));
                entry.score += weight / (k + rank);
                entry.bestRank = Math.min(entry.bestRank, rank);
                hit.signals().forEach((stage, value) -> entry.signals.merge(stage, value, Math::max));
            }
        }
        List<Entry> sorted = new ArrayList<>(entries.values());
        sorted.sort(Comparator.comparingDouble((Entry e) -> e.score).reversed()
                .thenComparingInt(e -> e.bestRank)
                .thenComparing(e -> e.chunk.chunkId()));
        List<ScoredChunk> fused = new ArrayList<>(Math.min(topK, sorted.size()));
        for (Entry e : sorted.subList(0, Math.min(topK, sorted.size()))) {
            e.signals.put(ScoredChunk.RRF, e.score);
            fused.add(new ScoredChunk(e.chunk, e.score, e.signals));
        }
        return List.copyOf(fused);
    }

    private static final class Entry {
        final Chunk chunk;
        final Map<String, Double> signals = new LinkedHashMap<>();
        double score;
        int bestRank = Integer.MAX_VALUE;

        Entry(Chunk chunk) {
            this.chunk = chunk;
        }
    }
}
