package io.github.qwzhang01.agent.rag.model;

import java.util.List;
import java.util.Objects;

/**
 * Generator output before verification.
 *
 * @param rawText          answer text with citation markers such as {@code [doc.md#3]}; every marker
 *                         names a chunk in {@code usedChunkIds} (or in the contexts when that is null)
 * @param sentences        parsed sentences with citations
 * @param refused          true when the model answered that the material does not cover the question
 * @param promptTokens     prompt tokens reported by the provider, 0 if unknown
 * @param completionTokens completion tokens reported by the provider, 0 if unknown
 * @param usedChunkIds     ids of the contexts actually shown to the model, in order (a generator may drop
 *                         some to fit its budget); null when the generator does not report it, meaning all
 */
public record GeneratedAnswer(
        String rawText,
        List<AnswerSentence> sentences,
        boolean refused,
        int promptTokens,
        int completionTokens,
        List<String> usedChunkIds
) {
    public GeneratedAnswer {
        Objects.requireNonNull(rawText, "rawText");
        sentences = sentences == null ? List.of() : List.copyOf(sentences);
        usedChunkIds = usedChunkIds == null ? null : List.copyOf(usedChunkIds);
    }

    /** Without {@code usedChunkIds}: every given context counts as shown. */
    public GeneratedAnswer(String rawText, List<AnswerSentence> sentences, boolean refused,
                           int promptTokens, int completionTokens) {
        this(rawText, sentences, refused, promptTokens, completionTokens, null);
    }
}
