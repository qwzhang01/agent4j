package io.github.qwzhang01.agent.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Defense-in-depth composition of two sanitizers (KP10 v1.5, debt-3 fix 2026-09-12).
 *
 * <p>Two layers, two detection physics:
 * <ol>
 *   <li><b>Fast wall</b> — the regex sanitizer over a NORMALIZED view
 *       ({@link InjectionNormalizer}): shape-based, microseconds, no
 *       external dependency, catches canonical and obfuscated-form attacks.</li>
 *   <li><b>Semantic judge</b> — {@link InjectionJudge}: meaning-based,
 *       LLM-backed, catches paraphrase attacks the wall cannot see. Optional;
 *       an absent judge (verdict UNKNOWN) degrades the layer to the wall
 *       alone — fail-open by contract, loudly logged.</li>
 * </ol>
 *
 * <p>Composition rule (first-hit-wins): if either layer flags the content,
 * the result is modified. The fast wall runs FIRST even when a judge is
 * attached — the cheap deterministic layer answers most cases and the
 * judge sees only what the wall could not rule on, the same
 * cheap-first-expensive-second discipline as cascade model routing
 * ({@code CascadeModelClient}).
 *
 * <p>What this decorator deliberately does NOT do:
 * <ul>
 *   <li>It does not rewrite the layers' strategies — each layer returns its
 *       own {@link SanitizeResult}; the composition returns the first
 *       modified one, preserving the caller's configured strategy.</li>
 *   <li>It does not persist anything — state discipline stays with the
 *       caller (decision 12).</li>
 * </ul>
 *
 * <p>Implements {@link ResultSanitizer} so it slots into every existing
 * consumer ({@code GovernedToolExecutor}, {@code SanitizingContextBuilder},
 * {@code SanitizerGuardrail}) with zero call-site changes — decorator over
 * interface, decision 5.
 */
public final class LayeredResultSanitizer implements ResultSanitizer {

    private static final Logger log = LoggerFactory.getLogger(LayeredResultSanitizer.class);

    private final ResultSanitizer fastWall;
    private final InjectionJudge judge;

    /**
     * Fast wall only (no judge attached): degradation to v1.5 shape.
     *
     * @param fastWall the regex-based sanitizer (typically {@link DefaultResultSanitizer})
     */
    public LayeredResultSanitizer(ResultSanitizer fastWall) {
        this(fastWall, InjectionJudge.absent());
    }

    /**
     * Full stack: regex wall + semantic judge.
     *
     * @param fastWall the regex-based sanitizer
     * @param judge semantic judge; {@link InjectionJudge#absent} disables the layer
     */
    public LayeredResultSanitizer(ResultSanitizer fastWall, InjectionJudge judge) {
        this.fastWall = Objects.requireNonNull(fastWall, "fastWall must not be null");
        this.judge = Objects.requireNonNull(judge, "judge must not be null (use absent())");
    }

    /**
     * Convenience factory: the default stack — default-strategy regex wall
     * over the normalized view, plus an optional judge.
     */
    public static LayeredResultSanitizer of(InjectionJudge judge) {
        return new LayeredResultSanitizer(new DefaultResultSanitizer(), judge);
    }

    @Override
    public SanitizeResult sanitize(String result) {
        if (result == null || result.isBlank()) {
            return SanitizeResult.clean(result);
        }

        // Layer 1: shape wall over the normalized view.
        String normalized = InjectionNormalizer.normalize(result);
        SanitizeResult wallVerdict = fastWall.sanitize(normalized);
        if (wallVerdict.modified()) {
            return wallVerdict;
        }

        // Layer 2: semantic judge, only on what the wall passed.
        InjectionJudge.Verdict verdict = judge.judge(result);
        if (verdict == InjectionJudge.Verdict.INJECTION) {
            log.warn("[Security] Semantic judge flagged content the regex wall passed "
                    + "(paraphrase-class attack); applying wall strategy to the whole content");
            // The wall had no match to act on, so apply the configured strategy
            // semantics here: the content as a whole is the injection surface.
            return SanitizeResult.modified(
                    "[BLOCKED: semantic injection detected]",
                    "semantic-judge");
        }
        if (verdict == InjectionJudge.Verdict.UNKNOWN && judge != InjectionJudge.absent()) {
            log.debug("[Security] Judge abstained; wall-only coverage for this content");
        }
        return SanitizeResult.clean(result);
    }

    /** The layers this sanitizer was composed from (observability / tests). */
    public List<String> describeLayers() {
        boolean judgeAttached = judge != InjectionJudge.absent();
        return List.of(
                "regex-wall(normalized-view)",
                judgeAttached ? "semantic-judge" : "semantic-judge(absent)");
    }
}
