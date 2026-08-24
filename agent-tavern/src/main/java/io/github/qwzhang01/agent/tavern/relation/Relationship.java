package io.github.qwzhang01.agent.tavern.relation;

/**
 * The player's standing with one character (Stage 16, blueprint D4).
 * <p>
 * A first-class domain value the Runtime does not have: {@code Relationship}
 * exists only in the Tavern Profile - that is what makes this a Profile
 * (domain semantics layered on mechanism).
 *
 * @param value           affection, 0-100 (50 = neutral first meeting)
 * @param lastChangedTurn the turn it last changed (0 = never changed)
 */
public record Relationship(int value, int lastChangedTurn) {

    public static final int MIN = 0;
    public static final int MAX = 100;
    public static final int DEFAULT = 50;

    /** Derived tiers - the vocabulary the model sees in [relationship] notes. */
    public enum Tier {
        STRANGER, COLD, NEUTRAL, WARM, FRIEND, DEVOTED
    }

    public Relationship {
        if (value < MIN || value > MAX) {
            throw new IllegalArgumentException(
                    "relationship value must be in [" + MIN + ", " + MAX + "], got: " + value);
        }
        if (lastChangedTurn < 0) {
            throw new IllegalArgumentException("lastChangedTurn must be >= 0, got: " + lastChangedTurn);
        }
    }

    /** A first meeting: neutral 50, never changed. */
    public static Relationship initial() {
        return new Relationship(DEFAULT, 0);
    }

    public Tier tier() {
        if (value < 20) {
            return Tier.STRANGER;
        }
        if (value < 35) {
            return Tier.COLD;
        }
        if (value < 55) {
            return Tier.NEUTRAL;
        }
        if (value < 75) {
            return Tier.WARM;
        }
        if (value < 90) {
            return Tier.FRIEND;
        }
        return Tier.DEVOTED;
    }

    /** One-line snapshot for the per-turn [relationship] sticky note. */
    public String describe() {
        return "affection " + value + " (" + tier() + ")";
    }
}
