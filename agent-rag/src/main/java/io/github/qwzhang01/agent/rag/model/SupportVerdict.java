package io.github.qwzhang01.agent.rag.model;

/** Whether the cited chunks back an answer sentence. */
public enum SupportVerdict {
    /** Cited chunks state the claim. */
    SUPPORTED,
    /** Cited chunks state something incompatible with the claim. */
    CONTRADICTED,
    /** Cited chunks do not mention the claim, or the sentence cites nothing. */
    NOT_FOUND,
    /** Verification was skipped or failed (verifier down, parse error). */
    UNVERIFIED
}
