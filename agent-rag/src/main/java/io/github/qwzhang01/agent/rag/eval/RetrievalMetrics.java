package io.github.qwzhang01.agent.rag.eval;

import io.github.qwzhang01.agent.rag.model.Chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Per-case retrieval metrics over a ranked chunk list (best first) and gold references. */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    /** Fraction of gold refs matched by at least one of the top {@code k} chunks; 0 when {@code gold} is empty. */
    public static double recallAtK(List<Chunk> ranked, List<GoldRef> gold, int k) {
        if (gold.isEmpty()) {
            return 0.0;
        }
        return (double) found(ranked, gold, k).size() / gold.size();
    }

    /** 1 when any gold ref is matched within the top {@code k}, else 0. */
    public static double hitAtK(List<Chunk> ranked, List<GoldRef> gold, int k) {
        return found(ranked, gold, k).isEmpty() ? 0.0 : 1.0;
    }

    /** 1 / rank of the first chunk matching any gold ref, 0 when none matches. */
    public static double reciprocalRank(List<Chunk> ranked, List<GoldRef> gold) {
        for (int i = 0; i < ranked.size(); i++) {
            if (matchesAny(ranked.get(i), gold)) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /** Gold refs matched by at least one of the top {@code k} chunks, in gold order. */
    public static List<GoldRef> found(List<Chunk> ranked, List<GoldRef> gold, int k) {
        Objects.requireNonNull(ranked, "ranked");
        Objects.requireNonNull(gold, "gold");
        if (k <= 0) {
            throw new IllegalArgumentException("k must be > 0, got: " + k);
        }
        List<Chunk> top = ranked.subList(0, Math.min(k, ranked.size()));
        List<GoldRef> out = new ArrayList<>();
        for (GoldRef ref : gold) {
            if (top.stream().anyMatch(ref::matches)) {
                out.add(ref);
            }
        }
        return out;
    }

    private static boolean matchesAny(Chunk chunk, List<GoldRef> gold) {
        for (GoldRef ref : gold) {
            if (ref.matches(chunk)) {
                return true;
            }
        }
        return false;
    }
}
