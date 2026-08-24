package io.github.qwzhang01.agent.observability.eval;

import java.util.Objects;

/**
 * One regression case (Stage 18 D7): a prompt, the assertion its answer must
 * survive, and - when the case was mined from a failure - the lineage back to
 * the run that produced it.
 * <p>
 * {@code originRunId} is the "fix one bug = dataset +1 case" contract in data
 * form: the case knows which incident it came from, so "did the fix for
 * run-8842 hold?" is a query, not an archaeology project. Hand-written cases
 * carry null - absence of lineage is honest, fabricated lineage is not (the
 * Stage 14 metadata discipline).
 *
 * @param caseId      unique id within the dataset ("case-0007" for imported)
 * @param prompt      the user prompt to replay
 * @param expectation the deterministic assertion (D7: v1 no judge)
 * @param originRunId run id of the failure this case was mined from, null for
 *                    hand-written cases
 */
public record EvalCase(String caseId, String prompt, Expectation expectation, String originRunId) {

    public EvalCase {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId must not be null or blank");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be null or blank (case " + caseId + ")");
        }
        Objects.requireNonNull(expectation, "expectation (case " + caseId + ")");
    }

    /** Hand-written case without lineage. */
    public static EvalCase of(String caseId, String prompt, Expectation expectation) {
        return new EvalCase(caseId, prompt, expectation, null);
    }
}
