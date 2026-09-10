package io.github.qwzhang01.agent.core.client;

import java.util.List;

/**
 * Unified interface for embedding providers (read-side semantic retrieval).
 * <p>
 * Same design principle as {@link ModelClient}: agent and memory code depend on
 * this port, never on a specific provider SDK. The memory module uses it to
 * vectorize entries on write (via {@code EmbeddingMemoryStore}) and to embed the
 * recall query on read (via {@code HybridRankingStrategy}).
 * <p>
 * Vectors are {@code float[]} because every mainstream provider returns
 * float32 JSON numbers; keeping the raw primitive array avoids a per-entry
 * allocation and matches pgvector's storage type.
 */
public interface EmbeddingClient {

    /**
     * Embed a single text.
     *
     * @param text non-null, non-blank text to vectorize
     * @return embedding vector; length is provider/model specific and stable
     *         across calls with the same model
     * @throws IllegalArgumentException if {@code text} is null or blank
     * @throws io.github.qwzhang01.agent.core.client.ModelException if the call fails
     */
    float[] embed(String text);

    /**
     * Embed multiple texts in one call. Implementations that support batching
     * should override this to issue a single request; the default loops over
     * {@link #embed}.
     * <p>
     * The returned list is index-aligned with the input: result {@code i} is
     * the embedding of {@code texts.get(i)}.
     *
     * @param texts non-null, non-empty list of non-blank texts
     * @return one vector per input text, same order
     * @throws IllegalArgumentException if {@code texts} is null, empty, or
     *                                  contains null/blank entries
     * @throws io.github.qwzhang01.agent.core.client.ModelException if the call fails
     */
    default List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("texts must not be null or empty");
        }
        return texts.stream().map(this::embed).toList();
    }

    /**
     * Cosine similarity of two vectors, in {@code [-1.0, 1.0]}.
     * <p>
     * Lives on the port because both sides of the pipeline need it: the
     * model adapter tests verify provider vectors round-trip, and the memory
     * ranking strategy scores query-vs-entry similarity. Returns {@code 0.0}
     * when either vector is null, empty, or zero-length — treat missing
     * vectors as "no semantic signal" rather than crashing, so unvectorized
     * legacy entries degrade gracefully in hybrid ranking.
     */
    static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
