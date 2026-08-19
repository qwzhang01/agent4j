package io.github.qwzhang01.agent.memory;

import java.time.Instant;

/**
 * Provenance of a memory entry - answers "who said it / where did it come from".
 * <p>
 * This is the backbone of memory governance (Stage 8 D2):
 * - Admin can trace any entry back to its origin
 * - Conflict resolution knows which source to trust
 * - Audit log can reconstruct what happened
 *
 * @param sourceType how this memory was produced
 * @param actor      who/what produced it (userId, tool name, or model id)
 * @param runId      the run that extracted this memory (null for admin edits)
 * @param at         when it was recorded
 */
public record MemoryProvenance(
        SourceType sourceType,
        String actor,
        String runId,
        Instant at
) {

    /**
     * How a memory entry came into existence.
     */
    public enum SourceType {
        /** A user stated something in conversation. */
        USER_SAID,
        /** Extracted from a tool execution result. */
        TOOL_RESULT,
        /** The model derived/summarized it (e.g. compaction or explicit save_memory). */
        MODEL_DERIVED,
        /** An administrator manually wrote or edited it. */
        ADMIN_EDIT
    }

    // ============ Factory Methods ============

    public static MemoryProvenance userSaid(String userId, String runId, Instant at) {
        return new MemoryProvenance(SourceType.USER_SAID, userId, runId, at);
    }

    public static MemoryProvenance toolResult(String toolName, String runId, Instant at) {
        return new MemoryProvenance(SourceType.TOOL_RESULT, toolName, runId, at);
    }

    public static MemoryProvenance modelDerived(String modelId, String runId, Instant at) {
        return new MemoryProvenance(SourceType.MODEL_DERIVED, modelId, runId, at);
    }

    public static MemoryProvenance adminEdit(String adminId, Instant at) {
        return new MemoryProvenance(SourceType.ADMIN_EDIT, adminId, null, at);
    }
}
