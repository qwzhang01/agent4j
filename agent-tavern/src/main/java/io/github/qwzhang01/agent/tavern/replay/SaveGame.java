package io.github.qwzhang01.agent.tavern.replay;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.tavern.relation.Relationship;
import io.github.qwzhang01.agent.tavern.world.WorldState;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A game's full state snapshot - the save file's in-memory form (Stage 16
 * M16.4, blueprint D6: a game snapshot, NOT a run checkpoint).
 * <p>
 * Contrast of the near-twins: a Stage 6 checkpoint is a single workflow run's
 * pause state (cursor + blackboard, indexed by runId); a SaveGame is the
 * WHOLE game's domain state (world + relationships + every character's
 * dialogue history + event bookkeeping, indexed by gameId). Similar shape,
 * different lifetime - forcing one onto the other drags runId semantics
 * where they do not belong.
 *
 * @param gameId              which game this save belongs to
 * @param world               the world at save time (the replay endpoint check)
 * @param relationships       tracked relationships at save time
 * @param characterHistories  each character's dialogue history (AgentState messages)
 * @param firedEventIds       once-bookkeeping for story events (restored into the
 *                            EventEvaluator on load)
 */
public record SaveGame(
        String gameId,
        WorldState world,
        Map<String, Relationship> relationships,
        Map<String, List<ChatMessage>> characterHistories,
        Set<String> firedEventIds
) {

    public SaveGame {
        if (gameId == null || gameId.isBlank()) {
            throw new IllegalArgumentException("gameId must not be null or blank");
        }
        if (world == null) {
            throw new IllegalArgumentException("world must not be null");
        }
        relationships = relationships == null ? Map.of() : Map.copyOf(relationships);
        characterHistories = characterHistories == null ? Map.of() : Map.copyOf(characterHistories);
        firedEventIds = firedEventIds == null ? Set.of() : Set.copyOf(firedEventIds);
    }
}
