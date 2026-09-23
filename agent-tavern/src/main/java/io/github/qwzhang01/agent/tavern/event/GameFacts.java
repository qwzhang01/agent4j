package io.github.qwzhang01.agent.tavern.event;

import io.github.qwzhang01.agent.tavern.relation.Relationship;
import io.github.qwzhang01.agent.tavern.world.WorldState;

import java.util.Map;

/**
 * The read-only facts an {@link EventRule} condition evaluates against:
 * world + relationships + turn number, snapshotted at the settlement point
 * .
 * <p>
 * This is the game-domain cousin of 's ambient facts: rules never
 * touch live mutable state, they see a frozen view. A character missing from
 * the map reads as the neutral default.
 */
public record GameFacts(WorldState world, Map<String, Relationship> relationships, int turnNo) {

    public GameFacts {
        if (world == null) {
            throw new IllegalArgumentException("world must not be null");
        }
        relationships = relationships == null ? Map.of() : Map.copyOf(relationships);
    }

    /** Relationship with a character; untracked characters are neutral 50. */
    public Relationship relationship(String characterId) {
        return relationships.getOrDefault(characterId, Relationship.initial());
    }
}
