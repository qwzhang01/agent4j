package io.github.qwzhang01.agent.rag.model;

import java.util.List;
import java.util.Objects;

/**
 * Final pipeline result.
 *
 * @param answer    answer text with citation markers, as shown to the user
 * @param sentences verified sentences
 * @param contexts  chunks given to the generator, in order; the targets of citation click-through
 * @param refused   true when the material does not cover the question
 * @param trace     trace of this call
 */
public record RagAnswer(
        String answer,
        List<AnswerSentence> sentences,
        List<ScoredChunk> contexts,
        boolean refused,
        RagTrace trace
) {
    public RagAnswer {
        Objects.requireNonNull(answer, "answer");
        sentences = sentences == null ? List.of() : List.copyOf(sentences);
        contexts = contexts == null ? List.of() : List.copyOf(contexts);
        Objects.requireNonNull(trace, "trace");
    }
}
