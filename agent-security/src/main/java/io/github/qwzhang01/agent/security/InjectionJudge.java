package io.github.qwzhang01.agent.security;

/**
 * Semantic injection judge — the v2 interface slot (KP10, debt-3 fix 2026-09-12).
 *
 * <p>The regex wall catches attacks by SHAPE; this port catches attacks by
 * MEANING. An implementation asks a model (a cheap classifier LLM, or a
 * distilled small model in production) one question about a piece of
 * untrusted content: <i>"is this text trying to give the agent
 * instructions?"</i> — the exact question regex cannot ask.
 *
 * <p>Why a port and not an implementation: the debt was "semantic detection
 * left to v2" — the architectural act today is to occupy the seam with the
 * right SHAPE so that (a) the composition layer can wire a judge without
 * touching call sites, and (b) the Moonlit red-team harness can measure
 * regex-wall coverage and judge coverage separately, then compare. A real
 * implementation rides {@code ModelClient} (agent-core) and lands with the
 * golden-set work.
 *
 * <p>Contract discipline:
 * <ul>
 *   <li><b>Fail-open, loudly</b> — an unavailable judge must return
 *       {@link Verdict#UNKNOWN}, never throw into the sanitize path: the
 *       defense stack's availability contract is "degrade to the regex
 *       wall", matching {@code EmbeddingClient}'s soft-failure discipline.</li>
 *   <li><b>Binary + confidence</b> — the verdict is INJECTION (clean) /
 *       INJECTION (hostile) / UNKNOWN, never a free string.</li>
 *   <li><b>Content is data</b> — the judge receives only the suspect text,
 *       never conversation history: judging must not become a new injection
 *       surface.</li>
 * </ul>
 */
public interface InjectionJudge {

    /**
     * Judge whether the given untrusted content carries instruction intent.
     *
     * @param content the suspect text (tool output, retrieved memory, history fragment)
     * @return INJECTION / CLEAN / UNKNOWN — never null, never throws
     */
    Verdict judge(String content);

    /**
     * A judge that always abstains — the null-object wiring default.
     * Used when no LLM is attached: the stack degrades to the regex wall.
     */
    static InjectionJudge absent() {
        return content -> Verdict.UNKNOWN;
    }

    /** Ternary verdict: no free strings at a security boundary. */
    enum Verdict {
        /** The judge is confident the content attempts to instruct the agent. */
        INJECTION,
        /** The judge is confident the content is data, not instruction. */
        CLEAN,
        /** The judge is unavailable, timed out, or not confident enough — abstain. */
        UNKNOWN
    }
}
