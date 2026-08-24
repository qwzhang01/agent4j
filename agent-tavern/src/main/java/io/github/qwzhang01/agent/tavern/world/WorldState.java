package io.github.qwzhang01.agent.tavern.world;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The game world as an immutable domain blackboard (Stage 16, blueprint D3).
 * <p>
 * Record-style: {@link #apply(WorldEffect)} returns a NEW state, the receiver
 * is never mutated. The single mutation point in a running game is the turn
 * engine, which swaps its {@code world} field on each applied effect - tools
 * only submit instructions (see {@link SetWorldFlagTool}), they never apply
 * effects themselves. One apply point means one place to audit and record.
 * <p>
 * Lifespan contrast (blueprint §1): a WorkflowState lives for one run, a
 * WorldState lives for one game, a MemoryStore lives across games.
 * "Turn 8 in the great hall" is world state, not memory and not run state.
 *
 * @param turnCount how many turns have been entered (0 before the first turn)
 * @param location  where the scene takes place
 * @param flags     named world flags (immutable copy)
 */
public record WorldState(int turnCount, String location, Map<String, String> flags) {

    public WorldState {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("location must not be null or blank");
        }
        // LinkedHashMap copy, not Map.copyOf: describe() renders flags in
        // insertion order, and [world] notes + replay summaries must be stable
        flags = flags == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(flags));
    }

    /** A fresh world: turn 0, given location, no flags. */
    public static WorldState initial(String location) {
        return new WorldState(0, location, Map.of());
    }

    /**
     * Apply one effect, returning the new state (this instance is untouched).
     * <p>
     * {@code switch} pattern matching is Java 21; on 17 the sealed three-case
     * chain is written with instanceof patterns (JEP 394) - the trailing throw
     * is unreachable by sealing, kept only because 17 cannot prove exhaustion.
     */
    public WorldState apply(WorldEffect effect) {
        if (effect == null) {
            throw new IllegalArgumentException("effect must not be null");
        }
        if (effect instanceof WorldEffect.SetFlag f) {
            Map<String, String> next = new LinkedHashMap<>(flags);
            next.put(f.key(), f.value());
            return new WorldState(turnCount, location, next);
        }
        if (effect instanceof WorldEffect.ClearFlag c) {
            Map<String, String> next = new LinkedHashMap<>(flags);
            next.remove(c.key());
            return new WorldState(turnCount, location, next);
        }
        if (effect instanceof WorldEffect.SetLocation l) {
            return new WorldState(turnCount, l.location(), flags);
        }
        throw new IllegalStateException("unreachable: WorldEffect is sealed with three cases");
    }

    /** Enter the next turn (the engine calls this once per played turn). */
    public WorldState nextTurn() {
        return new WorldState(turnCount + 1, location, flags);
    }

    /** Value of a named flag, if set. */
    public Optional<String> flag(String key) {
        return Optional.ofNullable(flags.get(key));
    }

    /**
     * Human-readable snapshot, used both for the per-turn {@code [world]}
     * context injection and for logging/replay summaries.
     */
    public String describe() {
        StringBuilder sb = new StringBuilder("Turn ").append(turnCount)
                .append(" · ").append(location);
        if (!flags.isEmpty()) {
            sb.append(" · ").append(flags.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(", ")));
        }
        return sb.toString();
    }
}
