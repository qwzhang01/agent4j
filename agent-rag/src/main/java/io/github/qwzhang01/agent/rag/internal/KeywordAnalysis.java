package io.github.qwzhang01.agent.rag.internal;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The text analysis behind keyword search, shared by every {@code ChunkIndex} in agent4j so index
 * and query tokens agree across backends: {@link SmartChineseAnalyzer} (Chinese word segmentation,
 * lower-casing, English Porter stemming, punctuation removed).
 * <p>
 * Not part of the public API; public only so other agent4j modules (e.g. {@code agent-rag-pgvector})
 * can use it. May change without notice.
 */
public final class KeywordAnalysis {

    /** Field name passed to the analyzer; SmartChineseAnalyzer ignores it. */
    public static final String FIELD = "content";

    private KeywordAnalysis() {
    }

    /** New analyzer; thread-safe, close it when done. */
    public static Analyzer newAnalyzer() {
        return new SmartChineseAnalyzer();
    }

    /** Tokens of {@code text} in order, repeats kept, at most {@code maxTokens}. */
    public static List<String> tokens(Analyzer analyzer, String text, int maxTokens) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty() || maxTokens <= 0) {
            return out;
        }
        try (TokenStream tokens = analyzer.tokenStream(FIELD, text)) {
            CharTermAttribute term = tokens.addAttribute(CharTermAttribute.class);
            tokens.reset();
            while (out.size() < maxTokens && tokens.incrementToken()) {
                out.add(term.toString());
            }
            tokens.end();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
