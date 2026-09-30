package io.github.qwzhang01.agent.rag;

import io.github.qwzhang01.agent.rag.model.AnswerSentence;
import io.github.qwzhang01.agent.rag.model.Chunk;

import java.util.List;
import java.util.Map;

/**
 * Checks each answer sentence against the chunks it cites, and only those.
 * A sentence citing nothing is {@code NOT_FOUND} without a model call.
 */
public interface CitationVerifier {

    /**
     * @param sentences   answer sentences with citations
     * @param chunksById  every chunk that may be cited
     * @return same sentences, same order, with verdicts filled; on verifier failure
     *         the affected sentences are {@code UNVERIFIED}
     */
    Verification verify(List<AnswerSentence> sentences, Map<String, Chunk> chunksById);

    record Verification(List<AnswerSentence> sentences, int promptTokens, int completionTokens, boolean degraded) {
        public Verification {
            sentences = List.copyOf(sentences);
        }
    }
}
