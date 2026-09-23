package io.github.qwzhang01.agent.memory;

/**
 * How a new memory entry relates to an older entry with the same subject.
 * <p>
 * Judged at extraction time (from the conversation text) and carried on
 * {@link MemoryEntry#lifecycle}. It controls which status the replaced old
 * entry transitions to:
 * <ul>
 *   <li>{@code EVOLVE} - the old content was once true but has changed
 *       (e.g. "I moved to Shanghai" replaces "lives in Shenzhen".
 *       The old entry becomes {@link MemoryStatus#HISTORICAL}: excluded from
 *       the default context, still visible to explicit history queries.</li>
 *   <li>{@code CONFLICT} - the old content was wrong from the start
 *       (e.g. "you remembered it wrong, I never had a credit card".
 *       The old entry becomes {@link MemoryStatus#SUPERSEDED}: audit-only,
 *       never retrievable.</li>
 * </ul>
 * {@code null} (not judged) is treated as {@code CONFLICT}: absent a judgment,
 * the safe default is to archive the old content, not to keep it retrievable.
 */
public enum MemoryLifecycle {
    EVOLVE,
    CONFLICT;

    /**
     * The status an old ACTIVE entry transitions to when replaced by a
     * candidate carrying the given lifecycle. {@code null} gets CONFLICT
     * semantics (SUPERSEDED), preserving the pre-lifecycle behaviour.
     */
    public static MemoryStatus supersedeTarget(MemoryLifecycle lifecycle) {
        return lifecycle == EVOLVE ? MemoryStatus.HISTORICAL : MemoryStatus.SUPERSEDED;
    }
}
