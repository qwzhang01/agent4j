package io.github.qwzhang01.agent.tavern.turn;

import io.github.qwzhang01.agent.tavern.world.WorldEffect;

import java.time.Instant;
import java.util.List;

/**
 * One played turn - the replay data unit of a game (Stage 16, blueprint D7).
 * <p>
 * A turn bundles everything a replay needs for one beat of the game: what the
 * player said, which character answered (plus any event-driven follow-up
 * responses, M16.3), and which world effects and relationship changes were
 * applied while it happened. The domain trajectory (turn-by-turn) and the
 * model trajectory (Stage 14, step-by-step) stay parallel and unmerged:
 * different units, different consumers (game replay vs RL training).
 *
 * @param turnNo              1-based turn number (matches WorldState at play time)
 * @param playerInput         raw player input, mentions included, verbatim
 * @param speakingCharacterId the character the turn was routed to
 * @param responses           in order: the speaking character's reply, then any
 *                            event-driven responses (M16.3)
 * @param appliedEffects      world effects applied during this turn (tool-submitted
 *                            plus event-carried, M16.3), in application order
 * @param relationshipChanges relationship adjustments ACCEPTED this turn (rejected
 *                            ones change nothing and are not recorded), in order
 * @param triggeredEventIds   story events triggered at settlement (M16.3; empty until then)
 * @param timestamp           when the turn was settled
 */
public record Turn(
        int turnNo,
        String playerInput,
        String speakingCharacterId,
        List<CharacterResponse> responses,
        List<WorldEffectEntry> appliedEffects,
        List<RelationshipChange> relationshipChanges,
        List<String> triggeredEventIds,
        Instant timestamp
) {

    public Turn {
        if (playerInput == null) {
            throw new IllegalArgumentException("playerInput must not be null");
        }
        if (speakingCharacterId == null || speakingCharacterId.isBlank()) {
            throw new IllegalArgumentException("speakingCharacterId must not be null or blank");
        }
        responses = responses == null ? List.of() : List.copyOf(responses);
        appliedEffects = appliedEffects == null ? List.of() : List.copyOf(appliedEffects);
        relationshipChanges = relationshipChanges == null ? List.of() : List.copyOf(relationshipChanges);
        triggeredEventIds = triggeredEventIds == null ? List.of() : List.copyOf(triggeredEventIds);
    }

    /**
     * One character's response within a turn. The speaking character's reply
     * has {@code eventDriven = false}; responses the engine elicits from other
     * characters because an event fired (M16.3) carry {@code eventDriven = true}.
     */
    public record CharacterResponse(String characterId, String text, boolean eventDriven) {
        public CharacterResponse {
            if (characterId == null || characterId.isBlank()) {
                throw new IllegalArgumentException("characterId must not be null or blank");
            }
            if (text == null) {
                throw new IllegalArgumentException("text must not be null");
            }
        }
    }

    /**
     * An applied world effect as logged in the turn. The effect value is the
     * instruction itself; it is wrapped (rather than logged bare) so the log
     * can later carry provenance (which tool call / which event produced it)
     * without breaking the record's shape (blueprint D4-style field stability).
     */
    public record WorldEffectEntry(WorldEffect effect) {
        public WorldEffectEntry {
            if (effect == null) {
                throw new IllegalArgumentException("effect must not be null");
            }
        }
    }

    /**
     * One accepted relationship adjustment as logged in the turn - the
     * relationship half of the replay stream (blueprint D7: replay rebuilds
     * world AND relationships turn by turn).
     */
    public record RelationshipChange(String characterId, int delta, int before, int after) {
        public RelationshipChange {
            if (characterId == null || characterId.isBlank()) {
                throw new IllegalArgumentException("characterId must not be null or blank");
            }
        }
    }
}
