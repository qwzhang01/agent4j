package io.github.qwzhang01.agent.core.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Contract tests for the {@link EmbeddingClient} port's static and default
 * members: cosine similarity edge cases and the batch default loop.
 */
class EmbeddingClientTest {

    @Test
    void cosine_identicalVectors_isOne() {
        float[] v = {1.0f, 2.0f, 3.0f};
        assertEquals(1.0, EmbeddingClient.cosineSimilarity(v, v), 1e-9);
    }

    @Test
    void cosine_oppositeVectors_isMinusOne() {
        float[] a = {1.0f, 0.0f};
        float[] b = {-1.0f, 0.0f};
        assertEquals(-1.0, EmbeddingClient.cosineSimilarity(a, b), 1e-9);
    }

    @Test
    void cosine_orthogonalVectors_isZero() {
        float[] a = {1.0f, 0.0f};
        float[] b = {0.0f, 1.0f};
        assertEquals(0.0, EmbeddingClient.cosineSimilarity(a, b), 1e-9);
    }

    @Test
    void cosine_nullOrEmptyOrMismatched_returnsZero() {
        float[] v = {1.0f, 2.0f};
        assertEquals(0.0, EmbeddingClient.cosineSimilarity(null, v), 1e-9);
        assertEquals(0.0, EmbeddingClient.cosineSimilarity(v, null), 1e-9);
        assertEquals(0.0, EmbeddingClient.cosineSimilarity(new float[0], v), 1e-9);
        assertEquals(0.0, EmbeddingClient.cosineSimilarity(v, new float[]{1.0f}), 1e-9);
    }

    @Test
    void cosine_zeroVector_returnsZero() {
        float[] zero = {0.0f, 0.0f};
        assertEquals(0.0, EmbeddingClient.cosineSimilarity(zero, zero), 1e-9);
    }

    @Test
    void cosine_knownValue_manualCheck() {
        // (1,1)·(1,0) / (|(1,1)| * |(1,0)|) = 1 / (sqrt(2) * 1) ≈ 0.7071
        float[] a = {1.0f, 1.0f};
        float[] b = {1.0f, 0.0f};
        assertEquals(1.0 / Math.sqrt(2.0), EmbeddingClient.cosineSimilarity(a, b), 1e-9);
    }

    @Test
    void embedAll_defaultLoopsIndexAligned() {
        List<float[]> result = new FixedVectorClient().embedAll(List.of("a", "b", "c"));
        assertEquals(3, result.size());
        assertArrayEquals(new float[]{1.0f, 0.0f}, result.get(0));
        assertArrayEquals(new float[]{0.0f, 1.0f}, result.get(1));
        assertArrayEquals(new float[]{1.0f, 1.0f}, result.get(2));
    }

    @Test
    void embedAll_nullOrEmpty_throws() {
        FixedVectorClient client = new FixedVectorClient();
        assertThrows(IllegalArgumentException.class, () -> client.embedAll(null));
        assertThrows(IllegalArgumentException.class, () -> client.embedAll(List.of()));
    }

    /** Stub client used by port-level tests: map keyed by text. */
    static final class FixedVectorClient implements EmbeddingClient {
        @Override
        public float[] embed(String text) {
            return switch (text) {
                case "a" -> new float[]{1.0f, 0.0f};
                case "b" -> new float[]{0.0f, 1.0f};
                default -> new float[]{1.0f, 1.0f};
            };
        }
    }
}
