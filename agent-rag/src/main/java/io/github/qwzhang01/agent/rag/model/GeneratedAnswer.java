package io.github.qwzhang01.agent.rag.model;

import java.util.List;
import java.util.Objects;

/**
 * Raw generator output before verification.
 *
 * @param rawText          model output including citation markers such as {@code [doc.md#3]}
 * @param sentences        parsed sentences with citations
 * @param refused          true when the model answered that the material does not cover the question
 * @param promptTokens     prompt tokens reported by the provider, 0 if unknown
 * @param completionTokens completion tokens reported by the provider, 0 if unknown
 */
public record GeneratedAnswer(
        String rawText,
        List<AnswerSentence> sentences,
        boolean refused,
        int promptTokens,
        int completionTokens
) {
    public GeneratedAnswer {
        Objects.requireNonNull(rawText, "rawText");
        sentences = sentences == null ? List.of() : List.copyOf(sentences);
    }
}
