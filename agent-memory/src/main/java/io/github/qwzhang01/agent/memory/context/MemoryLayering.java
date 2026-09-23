package io.github.qwzhang01.agent.memory.context;

import io.github.qwzhang01.agent.memory.MemoryEntry;

import java.util.Objects;

/**
 * Layered-injection policy (memory roadmap step 3; Letta core/archival
 * alignment).
 * <p>
 * One recall, two tiers with different physics:
 * <ul>
 *   <li><b>Core tier</b> — {@code importance >= coreImportanceThreshold}: the
 *       always-on working set "who the user is". Injected at the head of
 *       context every turn, never ranked by the query, never dropped by the
 *       token budget. Identity facts must survive query drift.</li>
 *   <li><b>Archival tier</b> — everything else: paged in next to the current
 *       turn, ranked by query relevance, trimmed by the token budget.</li>
 * </ul>
 * Tier proxy: v1 classifies the core tier by importance. An explicit tier
 * field on {@code MemoryEntry} can replace this proxy later without changing
 * this contract or any call site.
 * <p>
 * Token budget: {@code memoryTokenBudget} uses the chars/4 heuristic per
 * rendered line (the {@link ContextBudget} spirit — approximate, good enough
 * to bound the block, not token-exact). Core entries count against the budget
 * first but are never dropped by it; a warn fires when core alone exceeds the
 * budget (hosts gate what enters the core tier at write time).
 *
 * @param coreImportanceThreshold importance floor for the core tier, in [0.0, 1.0]
 * @param memoryTokenBudget max estimated tokens for the whole memory block;
 *                                {@code 0} = no budget
 */
public record MemoryLayering(double coreImportanceThreshold, int memoryTokenBudget) {

    /**
     * Default core-tier floor. Above the common archival seeding values
     * (0.6–0.8) so existing stores stay archival until a host explicitly
     * promotes entries.
     */
    public static final double DEFAULT_CORE_IMPORTANCE_THRESHOLD = 0.9;

    /** Sentinel for "no token budget". */
    public static final int NO_TOKEN_BUDGET = 0;

    /** Default policy: core floor 0.9, no token budget. */
    public static final MemoryLayering DEFAULTS =
            new MemoryLayering(DEFAULT_CORE_IMPORTANCE_THRESHOLD, NO_TOKEN_BUDGET);

    public MemoryLayering {
        if (Double.isNaN(coreImportanceThreshold)
                || coreImportanceThreshold < 0.0 || coreImportanceThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "coreImportanceThreshold must be in [0.0, 1.0], got: " + coreImportanceThreshold);
        }
        if (memoryTokenBudget < 0) {
            throw new IllegalArgumentException(
                    "memoryTokenBudget must be >= 0, got: " + memoryTokenBudget);
        }
    }

    /** The default policy: core floor 0.9, no token budget. */
    public static MemoryLayering defaults() {
        return DEFAULTS;
    }

    /**
     * @param coreImportanceThreshold importance floor for the core tier, in [0.0, 1.0]
     * @param memoryTokenBudget max estimated tokens for the whole block; 0 = none
     */
    public static MemoryLayering of(double coreImportanceThreshold, int memoryTokenBudget) {
        return new MemoryLayering(coreImportanceThreshold, memoryTokenBudget);
    }

    /**
     * Whether an entry belongs to the always-on core tier.
     */
    public boolean isCore(MemoryEntry entry) {
        Objects.requireNonNull(entry, "entry");
        return entry.importance() >= coreImportanceThreshold;
    }

    /**
     * Whether an entry belongs to the query-ranked archival tier.
     * Negation of {@link #isCore}; kept as a method reference target for
     * stream filters.
     */
    public boolean isNotCore(MemoryEntry entry) {
        return !isCore(entry);
    }
}
