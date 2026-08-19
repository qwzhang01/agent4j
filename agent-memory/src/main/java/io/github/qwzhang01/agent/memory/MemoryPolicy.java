package io.github.qwzhang01.agent.memory;

/**
 * Write-gate policy for memory entries (Stage 8 D6 - pollution defense, gate 1 & 2).
 * <p>
 * Three checks (gate 3 - supersede - is handled by the write flow, not here):
 * <ol>
 *   <li>Importance threshold: low-importance candidates are rejected</li>
 *   <li>Frequency control: identical content (same scope+subject+content) is not re-written</li>
 * </ol>
 * Explicit save_memory tool calls (importance >= 1.0) bypass the importance threshold
 * (Stage 8 D8 - model self-decided storage is high-confidence).
 */
public class MemoryPolicy {

    private final double importanceThreshold;

    /**
     * @param importanceThreshold minimum importance to store (0.0 ~ 1.0).
     *                            Recommended default: 0.5.
     */
    public MemoryPolicy(double importanceThreshold) {
        this.importanceThreshold = importanceThreshold;
    }

    /**
     * Whether a candidate entry should be stored.
     *
     * @param candidate the entry proposed for storage
     * @param store     the store (to check for duplicates)
     * @return true if the entry passes the gate
     */
    public boolean shouldStore(MemoryEntry candidate, MemoryStore store) {
        // Gate 1: importance threshold (explicit save_memory bypasses via importance=1.0)
        if (candidate.importance() < importanceThreshold) {
            return false;
        }

        // Gate 2: frequency control - skip if identical content already exists
        var existing = store.findActiveBySubject(candidate.scope(), candidate.subject());
        if (existing.isPresent() && existing.get().content().equals(candidate.content())) {
            return false;
        }

        return true;
    }

    /**
     * Whether this candidate should supersede an existing entry with the same subject.
     * True when there is an existing ACTIVE entry with different content.
     */
    public boolean shouldSupersede(MemoryEntry candidate, MemoryStore store) {
        var existing = store.findActiveBySubject(candidate.scope(), candidate.subject());
        return existing.isPresent() && !existing.get().content().equals(candidate.content());
    }

    public double getImportanceThreshold() {
        return importanceThreshold;
    }

    /**
     * Default status for a new entry based on its scope (Stage 8 D6, gate 2).
     * <p>
     * Channel-shared memories default to PENDING_REVIEW (awaiting admin approval)
     * so unconfirmed entries never enter the context. Other scopes default to ACTIVE.
     */
    public MemoryStatus defaultStatusForScope(String scope) {
        if (scope != null && scope.startsWith("channel:")) {
            return MemoryStatus.PENDING_REVIEW;
        }
        return MemoryStatus.ACTIVE;
    }
}
