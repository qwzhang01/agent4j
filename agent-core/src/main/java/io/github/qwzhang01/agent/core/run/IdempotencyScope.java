package io.github.qwzhang01.agent.core.run;

/**
 * Idempotency scope carried in {@link RunContext} .
 * <p>
 * The contract-level key formula is {@code runId:nodeId:visitOrdinal}
 * (harness contract 1.5, proven by E8). This record scopes which idempotency
 * domain a run belongs to: entries sharing a scope key are deduplicated
 * together by the SideEffectLedger. v1: carried, not yet consumed
 * by a ledger.
 */
public record IdempotencyScope(String scopeKey) {

    public static IdempotencyScope of(String scopeKey) {
        return new IdempotencyScope(scopeKey);
    }
}
