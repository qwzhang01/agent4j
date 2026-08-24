package io.github.qwzhang01.agent.tavern.relation;

/**
 * The game's numeric balance, as one value (Stage 16, blueprint D4).
 * <p>
 * {@code maxChangePerTurn} bounds the <b>net</b> relationship change per
 * character per turn - the accumulated sum of all adjustments in one turn,
 * not any single call. This closes the "salami-slicing" loophole: a model
 * calling +3 four times reaches +12, and the second call (+6 accumulated)
 * is already rejected. The guard against "an AI barkeep falling in love in
 * one turn" is exactly this policy.
 *
 * @param maxChangePerTurn max absolute net change allowed per character per turn
 */
public record RelationshipPolicy(int maxChangePerTurn) {

    public static final int DEFAULT_MAX_CHANGE_PER_TURN = 5;

    public static final RelationshipPolicy DEFAULT = new RelationshipPolicy(DEFAULT_MAX_CHANGE_PER_TURN);

    public RelationshipPolicy {
        if (maxChangePerTurn < 1) {
            throw new IllegalArgumentException(
                    "maxChangePerTurn must be >= 1, got: " + maxChangePerTurn);
        }
    }
}
