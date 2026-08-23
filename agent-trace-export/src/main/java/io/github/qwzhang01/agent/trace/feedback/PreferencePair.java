package io.github.qwzhang01.agent.trace.feedback;

import java.time.Instant;
import java.util.UUID;

/**
 * A preference between two trajectories (Stage 14 D6): which of two rollouts
 * of the SAME prompt a human preferred.
 * <p>
 * Stores REFERENCES (ids), never embedded conversations: one trajectory can
 * appear in many pairs (A&gt;B, A&gt;C, C&gt;B) - storage once, projections many
 * (same shape as DagSpec's "one semantics, two representations"). The
 * {@link DpoExporter} materializes {prompt, chosen, rejected} at export time.
 * <p>
 * Rejection-sampling pairing (same prompt, two rollouts, keep the better) is
 * the v1 source of pairs - comparing trajectories of different prompts has
 * no preference semantics, and {@link TrajectoryPairBuilder} enforces that.
 *
 * @param pairId      unique pair id
 * @param trajectoryA first rollout's trajectory id
 * @param trajectoryB second rollout's trajectory id
 * @param preferred   "A" or "B" (which one the human preferred)
 * @param annotator   who decided
 * @param createdAt   when
 */
public record PreferencePair(String pairId, String trajectoryA, String trajectoryB,
                             String preferred, String annotator, Instant createdAt) {

    public PreferencePair {
        if (pairId == null || pairId.isBlank()) {
            pairId = "pair-" + UUID.randomUUID();
        }
        if (trajectoryA == null || trajectoryB == null || trajectoryA.equals(trajectoryB)) {
            throw new IllegalArgumentException("pair needs two DISTINCT trajectory ids");
        }
        if (!"A".equals(preferred) && !"B".equals(preferred)) {
            throw new IllegalArgumentException("preferred must be 'A' or 'B', got " + preferred);
        }
        if (annotator == null || annotator.isBlank()) {
            throw new IllegalArgumentException("annotator must not be blank");
        }
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
