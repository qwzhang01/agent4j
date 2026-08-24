package io.github.qwzhang01.agent.coding.patch;

/**
 * Outcome of {@link PatchStore#apply()} (Stage 17 M17.2).
 * <p>
 * The blueprint sketched a two-value enum; drift rejection needs details (which path,
 * why), so this is a sealed interface with two records instead (same precedent as
 * {@code TurnResult} in Stage 16 M16.2).
 * <p>
 * Drift = the disk no longer matches the staging-time snapshot: someone (or something)
 * modified the workspace while the patch was awaiting approval. A drifted apply is
 * <b>rejected as a whole</b> - and crucially, the disk keeps the human's modification:
 * a rejected apply must not have a single byte of side effect.
 */
public sealed interface ApplyResult {

    /** Patch applied: all changes written to disk, patch is now APPLIED. */
    record Applied(Patch patch, int filesWritten) implements ApplyResult {
        public Applied {
            if (patch == null || patch.status() != Patch.PatchStatus.APPLIED) {
                throw new IllegalArgumentException("Applied result must carry an APPLIED patch");
            }
        }
    }

    /** Drift detected: nothing written, the disk keeps whatever it currently holds. */
    record DriftRejected(String path, String reason) implements ApplyResult {
        public DriftRejected {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("path must not be null or blank");
            }
        }
    }
}
