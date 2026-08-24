package io.github.qwzhang01.agent.tavern.replay;

import io.github.qwzhang01.agent.tavern.relation.Relationship;
import io.github.qwzhang01.agent.tavern.turn.Turn;
import io.github.qwzhang01.agent.tavern.world.WorldState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Step-through replay view of one game's turn log (Stage 16 M16.4,
 * blueprint D7: replay the recording, never re-run the model).
 * <p>
 * {@code stateAt(n)} rebuilds the world and relationships AS THEY WERE at the
 * end of turn n, by re-applying the recorded effects and relationship changes
 * - a pure data-flow re-derivation. No model calls, no rule re-evaluation:
 * the record IS the truth. This is the same discipline as Stage 14's
 * ReplayView (walk the log), at turn granularity instead of model-step
 * granularity.
 * <p>
 * The three replay layers side by side (blueprint §1): Checkpoint resumes a
 * run, Trajectory replays model decisions for training, GameReplay replays
 * narrative history for review. Parallel, unmerged.
 */
public final class GameReplay {

    private final WorldState initialWorld;
    private final Map<String, Relationship> initialRelationships;
    private final List<Turn> turns;

    GameReplay(WorldState initialWorld,
               Map<String, Relationship> initialRelationships,
               List<Turn> turns) {
        this.initialWorld = initialWorld;
        this.initialRelationships = Map.copyOf(initialRelationships);
        this.turns = List.copyOf(turns);
    }

    /**
     * Public factory: assemble a replay view from in-memory parts. Used by
     * {@code TavernGame.replay()} for the current instance's turns.
     */
    public static GameReplay of(WorldState initialWorld,
                                Map<String, Relationship> initialRelationships,
                                List<Turn> turns) {
        return new GameReplay(initialWorld, initialRelationships, turns);
    }

    /** All settled turns, in play order. */
    public List<Turn> turns() {
        return turns;
    }

    /** The game's true initial world (log line 1; the resume path needs it). */
    public WorldState initialWorld() {
        return initialWorld;
    }

    /** The game's initial relationships (log line 1; the resume path needs them). */
    public Map<String, Relationship> initialRelationships() {
        return initialRelationships;
    }

    public int turnCount() {
        return turns.size();
    }

    /**
     * The game state as it stood at the end of turn {@code turnNo}
     * ({@code 0} = the initial state before any turn).
     */
    public ReplaySnapshot stateAt(int turnNo) {
        if (turnNo < 0 || turnNo > turns.size()) {
            throw new IllegalArgumentException(
                    "turnNo must be in [0, " + turns.size() + "], got: " + turnNo);
        }
        WorldState world = initialWorld;
        Map<String, Relationship> relationships = new HashMap<>(initialRelationships);
        for (int i = 0; i < turnNo; i++) {
            Turn t = turns.get(i);
            world = world.nextTurn();
            for (Turn.WorldEffectEntry entry : t.appliedEffects()) {
                world = world.apply(entry.effect());
            }
            for (Turn.RelationshipChange change : t.relationshipChanges()) {
                relationships.put(change.characterId(),
                        new Relationship(change.after(), t.turnNo()));
            }
        }
        return new ReplaySnapshot(world, Map.copyOf(relationships));
    }

    /** The state after the last settled turn (the save-file endpoint). */
    public ReplaySnapshot finalState() {
        return stateAt(turns.size());
    }

    /**
     * Human-readable summary of one turn: who said what, what changed in the
     * world, which relationships moved, which events fired.
     */
    public String describeTurn(int turnNo) {
        if (turnNo < 1 || turnNo > turns.size()) {
            throw new IllegalArgumentException(
                    "turnNo must be in [1, " + turns.size() + "], got: " + turnNo);
        }
        Turn t = turns.get(turnNo - 1);
        StringBuilder sb = new StringBuilder("Turn ").append(t.turnNo())
                .append(" · ").append(t.playerInput()).append('\n');
        for (Turn.CharacterResponse r : t.responses()) {
            sb.append("  ").append(r.characterId()).append(": \"").append(r.text()).append('"');
            if (r.eventDriven()) {
                sb.append("  [event]");
            }
            sb.append('\n');
        }
        for (Turn.WorldEffectEntry e : t.appliedEffects()) {
            sb.append("  world: ").append(describeEffect(e.effect())).append('\n');
        }
        for (Turn.RelationshipChange c : t.relationshipChanges()) {
            sb.append("  relationship: ").append(c.characterId())
                    .append(' ').append(c.delta() >= 0 ? "+" : "").append(c.delta())
                    .append(" (").append(c.before()).append(" -> ").append(c.after()).append(")\n");
        }
        for (String eventId : t.triggeredEventIds()) {
            sb.append("  event: ").append(eventId).append('\n');
        }
        return sb.toString();
    }

    private String describeEffect(io.github.qwzhang01.agent.tavern.world.WorldEffect e) {
        if (e instanceof io.github.qwzhang01.agent.tavern.world.WorldEffect.SetFlag f) {
            return "set " + f.key() + "=" + f.value();
        }
        if (e instanceof io.github.qwzhang01.agent.tavern.world.WorldEffect.ClearFlag c) {
            return "clear " + c.key();
        }
        if (e instanceof io.github.qwzhang01.agent.tavern.world.WorldEffect.SetLocation l) {
            return "move to " + l.location();
        }
        return e.toString();
    }

    /** The rebuilt world + relationships at one point in game history. */
    public record ReplaySnapshot(WorldState world, Map<String, Relationship> relationships) {
        public ReplaySnapshot {
            if (world == null) {
                throw new IllegalArgumentException("world must not be null");
            }
            relationships = relationships == null ? Map.of() : Map.copyOf(relationships);
        }

        /**
         * Relationship with a character at this point; untracked characters
         * read as neutral 50 - the same default semantics as GameFacts and
         * RelationshipMatrix.view.
         */
        public Relationship relationship(String characterId) {
            return relationships.getOrDefault(characterId, Relationship.initial());
        }

        public List<String> characterIdsWithRelationships() {
            return new ArrayList<>(relationships.keySet());
        }
    }
}
