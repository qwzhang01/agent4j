package io.github.qwzhang01.agent.rag.index;

import io.github.qwzhang01.agent.core.client.EmbeddingClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic embedder for tests: hashes ASCII words and individual CJK characters into a
 * small bag-of-tokens vector, so texts sharing tokens have high cosine similarity.
 */
public final class FakeEmbeddingClient implements EmbeddingClient {

    private final int dimensions;
    private volatile boolean failing;
    private final AtomicInteger batchCalls = new AtomicInteger();
    private final AtomicInteger singleCalls = new AtomicInteger();

    public FakeEmbeddingClient(int dimensions) {
        this.dimensions = dimensions;
    }

    public FakeEmbeddingClient failing(boolean failing) {
        this.failing = failing;
        return this;
    }

    public int batchCalls() {
        return batchCalls.get();
    }

    public int singleCalls() {
        return singleCalls.get();
    }

    @Override
    public float[] embed(String text) {
        singleCalls.incrementAndGet();
        return vector(text);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        batchCalls.incrementAndGet();
        List<float[]> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            out.add(vector(text));
        }
        return out;
    }

    private float[] vector(String text) {
        if (failing) {
            throw new IllegalStateException("embedding service down");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("blank text");
        }
        float[] v = new float[dimensions];
        for (String token : tokens(text)) {
            v[Math.floorMod(token.hashCode(), dimensions)] += 1f;
        }
        return v;
    }

    static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                flush(word, tokens);
                tokens.add(String.valueOf(c));
            } else if (Character.isLetterOrDigit(c)) {
                word.append(Character.toLowerCase(c));
            } else {
                flush(word, tokens);
            }
        }
        flush(word, tokens);
        return tokens;
    }

    private static void flush(StringBuilder word, List<String> tokens) {
        if (!word.isEmpty()) {
            tokens.add(word.toString().toLowerCase(Locale.ROOT));
            word.setLength(0);
        }
    }
}
