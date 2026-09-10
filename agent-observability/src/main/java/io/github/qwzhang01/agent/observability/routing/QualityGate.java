package io.github.qwzhang01.agent.observability.routing;

import io.github.qwzhang01.agent.core.model.ModelResponse;

/**
 * Post-call quality verdict for cascade routing (E2): a response-side signal
 * that {@link CascadeModelClient} reads to decide whether the cheap attempt is
 * good enough, or the call must be re-issued on the premium tier.
 * <p>
 * WHERE this sits (E2's core question - decision 25): pre-call signals (budget,
 * task markers) belong to {@link ModelRouter}; post-call signals (did the
 * answer actually parse / finish / say anything) belong here, one decorator
 * ABOVE the tiers, wrapping them both. The two layers never merge: the router
 * chooses before the call, the gate judges after it.
 * <p>
 * WHAT counts as bad (v1, three signals only - deliberately dumb, no scoring
 * model): structured output that fails to parse, abnormal finish reason, empty
 * content. Anything subtler (correctness, tone, depth) is out of scope: a
 * scorer gate is a v2 concern and needs labeled data first.
 * <p>
 * Verdicts are auditable like {@link RouteDecision}: a gate that fails a
 * response must say why - "escalated because X" is part of the cost audit
 * trail, same discipline as routing reasons.
 */
@FunctionalInterface
public interface QualityGate {

    /**
     * Judge a model response.
     *
     * @param response the response the cheap tier just produced
     * @return verdict with pass/fail and a non-blank reason (enforced by
     *         {@link Verdict})
     */
    Verdict judge(ModelResponse response);

    /**
     * One quality verdict: passed, or failed with a reason.
     *
     * @param passed whether the response is good enough to return as-is
     * @param reason non-blank; when passed it SHOULD describe why confidence
     *               is high enough, when failed it MUST name the concrete
     *               defect (parse failure, finish reason, emptiness)
     */
    record Verdict(boolean passed, String reason) {

        public Verdict {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException(
                        "reason must not be null or blank (quality verdicts are auditable, like routing decisions)");
            }
        }

        /** Convenience factory for a pass. */
        public static Verdict pass(String reason) {
            return new Verdict(true, reason);
        }

        /** Convenience factory for a fail - the reason names the defect. */
        public static Verdict fail(String reason) {
            return new Verdict(false, reason);
        }
    }
}
