package io.github.qwzhang01.agent.memory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable record of ONE memory-write decision: what was decided (operation +
 * lifecycle), on what evidence (which old entry, which new entry, which subject),
 * by whom, and when (decision time vs application time).
 * <p>
 * Produced by every supersede-capable write path (extract pipeline, save_memory
 * tool, admin approve) so audits can answer "who changed my memory, and why" —
 * the evidence chain the reconciliation design calls . It is a value
 * object, not a store entity: hosts that need persistence subscribe to the
 * {@code MemoryDecisionListener} hook or poll their store's audit view.
 * <p>
 * Field semantics follow the decision-event schema agreed in the reconciliation
 * design (2026-09-10): {@code evidence} = the recalled old entries the decision
 * was made against (what the reconciler saw), {@code operation} = what the write
 * path did, {@code oldEntryId}/{@code newEntryId} = the ledger lines involved,
 * {@code approvedBy} = human or model identity that owns the decision, and the
 * decidedAt/appliedAt pair separates "when the judgment was made" from "when it
 * landed in the store" (they differ under async extraction and admin review).
 *
 * @param operation what the write path did with the candidate
 * @param subject the subject key the decision was made under — the reconciled
 *                    key when the pipeline corrected a drifted key, so audits can
 *                    see key corrections, not just content changes
 * @param lifecycle EVOLVE / CONFLICT / null (null = not judged -> CONFLICT)
 * @param oldEntryId the ACTIVE entry this decision replaced (null = ADD)
 * @param newEntryId the entry this decision wrote (null = REJECT)
 * @param evidence the recalled old entries visible to the decision maker
 *                    (empty for ADD decisions with no recalled evidence)
 * @param approvedBy identity owning the decision: model id, user id, or admin id
 * @param decidedAt when the judgment was made (decision time)
 * @param appliedAt when the write landed in the store (application time)
 */
public record MemoryDecision(
        Operation operation,
        String subject,
        MemoryLifecycle lifecycle,
        String oldEntryId,
        String newEntryId,
        Evidence evidence,
        String approvedBy,
        Instant decidedAt,
        Instant appliedAt
) {

    /** What the write path did with a candidate entry. */
    public enum Operation {
        /** New subject, no prior ACTIVE entry. */
        ADD,
        /** Replaced an old ACTIVE entry (EVOLVE or CONFLICT judged). */
        UPDATE,
        /** Candidate rejected by policy (importance gate / duplicate). */
        REJECT
    }

    /**
     * What the decision maker saw when judging: the recalled old entries
     * (the reconciliation context), ordered as recalled.
     *
     * @param recalledSubjectContents "subject: content" lines as recalled
     */
    public record Evidence(List<String> recalledSubjectContents) {

        public Evidence {
            Objects.requireNonNull(recalledSubjectContents, "recalledSubjectContents");
            recalledSubjectContents = List.copyOf(recalledSubjectContents);
        }

        public static Evidence none() {
            return new Evidence(List.of());
        }

        public boolean isEmpty() {
            return recalledSubjectContents.isEmpty();
        }
    }

    /**
     * Convenience factory: ADD decision, no old entry, no recalled evidence.
     */
    public static MemoryDecision add(String subject, String newEntryId, String approvedBy,
                                     Instant decidedAt, Instant appliedAt) {
        return new MemoryDecision(Operation.ADD, subject, null, null, newEntryId,
                Evidence.none(), approvedBy, decidedAt, appliedAt);
    }

    /**
     * Convenience factory: UPDATE decision replacing {@code oldEntryId}.
     * Evidence defaults to none; use the full constructor when the decision
     * was made against recalled entries.
     */
    public static MemoryDecision update(String subject, MemoryLifecycle lifecycle,
                                         String oldEntryId, String newEntryId, String approvedBy,
                                         Instant decidedAt, Instant appliedAt) {
        return new MemoryDecision(Operation.UPDATE, subject, lifecycle, oldEntryId, newEntryId,
                Evidence.none(), approvedBy, decidedAt, appliedAt);
    }

    /**
     * Convenience factory: REJECT decision (policy-gated candidate).
     */
    public static MemoryDecision reject(String subject, String approvedBy,
                                        Instant decidedAt, Instant appliedAt) {
        return new MemoryDecision(Operation.REJECT, subject, null, null, null,
                Evidence.none(), approvedBy, decidedAt, appliedAt);
    }

    /**
     * Render evidence as prompt-friendly lines "- subject: content", or an
     * "empty" marker line. Used when building the reconciliation prompt.
     */
    public static String renderEvidence(Evidence evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "(no recalled memories)";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : evidence.recalledSubjectContents()) {
            sb.append("- ").append(line).append('\n');
        }
        return sb.toString();
    }
}
