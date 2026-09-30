package io.github.qwzhang01.agent.rag.model;

import java.util.List;
import java.util.Objects;

/**
 * One sentence of a generated answer with its citations and verification result.
 *
 * @param text          sentence text with citation markers removed
 * @param citedChunkIds chunk ids cited by this sentence; only ids present in the retrieved context are kept
 * @param verdict       verification result; {@link SupportVerdict#UNVERIFIED} before verification
 * @param reason        verifier's short justification, may be empty
 */
public record AnswerSentence(String text, List<String> citedChunkIds, SupportVerdict verdict, String reason) {

    public AnswerSentence {
        Objects.requireNonNull(text, "text");
        citedChunkIds = citedChunkIds == null ? List.of() : List.copyOf(citedChunkIds);
        verdict = verdict == null ? SupportVerdict.UNVERIFIED : verdict;
        reason = reason == null ? "" : reason;
    }

    public AnswerSentence withVerdict(SupportVerdict newVerdict, String newReason) {
        return new AnswerSentence(text, citedChunkIds, newVerdict, newReason);
    }
}
