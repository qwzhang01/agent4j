package io.github.qwzhang01.agent.coding.patch;

import java.time.Instant;
import java.util.List;

/**
 * The set of staged changes for one task - the <b>transaction unit</b> of a coding
 * change (Stage 17 M17.2, blueprint D1): applied as a whole or discarded as a whole,
 * never half-applied.
 * <p>
 * Status machine: {@code DRAFT -> VALIDATED -> APPLIED} with terminal exits
 * {@code REJECTED} (human gate) and {@code DISCARDED} (fix-loop budget exhausted or
 * explicit throwaway). {@code DRAFT -> APPLIED} directly is also allowed: the human gate
 * may approve an untested patch, the state machine does not legislate process
 * (that is {@code CodingSession}'s job in M17.4).
 * <p>
 * Immutable record with wither methods ({@link #withStatus}/{@link #withChanges}) -
 * the same discipline as {@code RewardResult.applyTo} (Stage 14): transitions produce a
 * new instance, the old one stays as an audit fact.
 * <p>
 * The blueprint lists a {@code summary} field; it is deliberately NOT a field - the
 * summary is derived on demand by {@link PatchSummarizer} (single source of truth, no
 * stale copy to keep in sync).
 *
 * @param patchId   session-scoped id, e.g. "P-1" (deterministic, test-friendly)
 * @param changes   staged changes, insertion-ordered by first staging (defensive copy)
 * @param status    current state in the machine above
 * @param createdAt when this patch was opened
 */
public record Patch(String patchId, List<FileChange> changes, PatchStatus status, Instant createdAt) {

    public enum PatchStatus { DRAFT, VALIDATED, APPLIED, REJECTED, DISCARDED }

    public Patch {
        if (patchId == null || patchId.isBlank()) {
            throw new IllegalArgumentException("patchId must not be null or blank");
        }
        if (changes == null) {
            throw new IllegalArgumentException("changes must not be null (use List.of() for an empty patch)");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt must not be null");
        }
        changes = List.copyOf(changes);
    }

    public Patch withStatus(PatchStatus newStatus) {
        return new Patch(patchId, changes, newStatus, createdAt);
    }

    public Patch withChanges(List<FileChange> newChanges) {
        return new Patch(patchId, newChanges, status, createdAt);
    }

    /** Number of staged changes. */
    public int size() {
        return changes.size();
    }
}
