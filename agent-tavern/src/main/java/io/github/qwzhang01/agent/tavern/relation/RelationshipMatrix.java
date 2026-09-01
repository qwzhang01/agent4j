package io.github.qwzhang01.agent.tavern.relation;

import java.util.HashMap;
import java.util.Map;

/**
 * All player-to-character relationships, with the turn-accumulated limiter
 * as the only write path (Stage 16, blueprint D4).
 * <p>
 * Fail-closed: an adjustment that would exceed the per-turn net-change budget
 * is REJECTED with a reason - rejection is a normal game flow (the model reads
 * the failure observation and self-corrects), never an exception. The 0-100
 * clamp applies after acceptance; the budget is charged by the REQUESTED delta
 * (stricter and safer than charging the clamped result).
 */
public final class RelationshipMatrix {

    private final RelationshipPolicy policy;
    private final Map<String, Relationship> values = new HashMap<>();
    private final Map<String, Integer> appliedThisTurn = new HashMap<>();
    private int lastTurnSeen = -1;

    public RelationshipMatrix() {
        this(RelationshipPolicy.DEFAULT);
    }

    public RelationshipMatrix(RelationshipPolicy policy) {
        this.policy = policy;
    }

    /** Current relationship with a character; unseen characters are neutral 50. */
    public Relationship view(String characterId) {
        if (characterId == null || characterId.isBlank()) {
            throw new IllegalArgumentException("characterId must not be null or blank");
        }
        return values.getOrDefault(characterId, Relationship.initial());
    }

    /**
     * Apply an adjustment for a character at a given turn, honoring the
     * per-turn accumulated limit.
     *
     * @return {@link ApplyResult.Applied} on success (value clamped to 0-100),
     *         {@link ApplyResult.Rejected} when the net change would exceed the policy
     */
    public ApplyResult apply(String characterId, int delta, int turnNo) {
        if (characterId == null || characterId.isBlank()) {
            throw new IllegalArgumentException("characterId must not be null or blank");
        }
        if (turnNo < 0) {
            throw new IllegalArgumentException("turnNo must be >= 0, got: " + turnNo);
        }
        rollTurnIfNeeded(turnNo);

        int alreadyApplied = appliedThisTurn.getOrDefault(characterId, 0);
        int projected = alreadyApplied + delta;
        if (Math.abs(projected) > policy.maxChangePerTurn()) {
            return new ApplyResult.Rejected(characterId, delta, alreadyApplied,
                    "net change |" + projected + "| would exceed this turn's limit of ±"
                            + policy.maxChangePerTurn()
                            + " (already applied " + alreadyApplied + " this turn)");
        }

        Relationship before = view(characterId);
        int clamped = Math.max(Relationship.MIN, Math.min(Relationship.MAX, before.value() + delta));
        Relationship after = new Relationship(clamped, turnNo);
        values.put(characterId, after);
        appliedThisTurn.put(characterId, projected);
        return new ApplyResult.Applied(characterId, before, after, delta);
    }

    /** Immutable snapshot of all tracked relationships (save/replay view). */
    public Map<String, Relationship> snapshot() {
        return Map.copyOf(values);
    }

    /** The policy in force (for inspection and the M16.4 save file). */
    public RelationshipPolicy policy() {
        return policy;
    }

    /**
     * Restore values from a save (M16.4 load path). Bypasses the limiter on
     * purpose: restoring is a system operation, not a model's turn action.
     * The per-turn budget is cleared - a restored game starts a fresh turn.
     */
    public void restore(java.util.Map<String, Relationship> values) {
        this.values.clear();
        this.appliedThisTurn.clear();
        if (values != null) {
            this.values.putAll(values);
        }
    }

    private void rollTurnIfNeeded(int turnNo) {
        if (turnNo != lastTurnSeen) {
            appliedThisTurn.clear();
            lastTurnSeen = turnNo;
        }
    }

    // ============ Result ============

    /** Sealed two-state outcome: rejection is game flow, not an error. */
    public sealed interface ApplyResult {

        /** The adjustment was applied (value clamped to 0-100). */
        record Applied(String characterId, Relationship before, Relationship after, int requestedDelta)
                implements ApplyResult {
        }

        /** The adjustment was rejected by the per-turn accumulated limit. */
        record Rejected(String characterId, int requestedDelta, int alreadyAppliedThisTurn, String reason)
                implements ApplyResult {
        }
    }
}
