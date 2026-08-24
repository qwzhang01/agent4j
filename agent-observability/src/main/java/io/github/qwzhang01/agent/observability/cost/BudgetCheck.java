package io.github.qwzhang01.agent.observability.cost;

/**
 * Result of a pre-flight budget check - warning and blocking are SEPARATE
 * mechanisms at the type level (Stage 18 D3).
 * <p>
 * The warning's job is to be SEEN (a WARN result never blocks the call); the
 * denial's job is to HOLD THE LINE (fail-closed). Mixing them ruins both: a
 * warning that blocks gets its threshold tuned to 99% to stop the noise, and a
 * block that only warns is not a budget at all.
 */
public sealed interface BudgetCheck {

    /** Within budget and below the warning line - proceed silently. */
    record Ok() implements BudgetCheck {
    }

    /**
     * Usage crossed the warning percentage but the call fits - proceed, but the
     * signal is out (an alarm event was emitted to the sink if one is wired).
     *
     * @param percentUsed percent of the limit ALREADY USED (not projected)
     * @param usedTokens  tokens already recorded against this budget
     * @param limitTokens the configured limit
     */
    record Warn(int percentUsed, long usedTokens, long limitTokens) implements BudgetCheck {
    }

    /**
     * Projected usage (already used + this call's estimate) would exceed the
     * limit - fail-closed, the caller must refuse. Never silently degrades.
     *
     * @param usedTokens  tokens already recorded against this budget
     * @param limitTokens the configured limit
     */
    record Denied(long usedTokens, long limitTokens) implements BudgetCheck {
    }
}
