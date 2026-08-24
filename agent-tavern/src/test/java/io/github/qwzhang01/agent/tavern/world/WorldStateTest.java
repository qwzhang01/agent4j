package io.github.qwzhang01.agent.tavern.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 16 M16.2: the world as an immutable domain blackboard.
 * <p>
 * Blueprint D3 under test: changes are instructions (sealed effects), apply
 * returns a new state, and there is no other mutation path.
 */
class WorldStateTest {

    @Test
    @DisplayName("initial state: turn 0, location, no flags; describe renders the basics")
    void initialState() {
        WorldState world = WorldState.initial("tavern-hall");

        assertEquals(0, world.turnCount());
        assertEquals("tavern-hall", world.location());
        assertTrue(world.flags().isEmpty());
        assertEquals("Turn 0 · tavern-hall", world.describe());
    }

    @Test
    @DisplayName("apply SetFlag stores the flag and overwrites a previous value")
    void setFlagSemantics() {
        WorldState world = WorldState.initial("tavern-hall")
                .apply(new WorldEffect.SetFlag("bard-mood", "lively"))
                .apply(new WorldEffect.SetFlag("bard-mood", "electric"));

        assertEquals(Optional.of("electric"), world.flag("bard-mood"));
        assertTrue(world.flag("quarrel").isEmpty(), "unset flags read as empty");
    }

    @Test
    @DisplayName("apply ClearFlag removes an existing flag and tolerates a missing one")
    void clearFlagSemantics() {
        WorldState world = WorldState.initial("tavern-hall")
                .apply(new WorldEffect.SetFlag("quarrel", "brewing"))
                .apply(new WorldEffect.ClearFlag("quarrel"))
                .apply(new WorldEffect.ClearFlag("never-existed"));

        assertTrue(world.flag("quarrel").isEmpty());
    }

    @Test
    @DisplayName("apply SetLocation moves the scene")
    void setLocationSemantics() {
        WorldState world = WorldState.initial("tavern-hall")
                .apply(new WorldEffect.SetLocation("cellar"));

        assertEquals("cellar", world.location());
        assertEquals(0, world.turnCount(), "moving does not consume a turn");
    }

    @Test
    @DisplayName("apply never mutates the receiver - the engine swaps, effects never edit in place")
    void applyIsImmutable() {
        WorldState before = WorldState.initial("tavern-hall");
        WorldState after = before.apply(new WorldEffect.SetFlag("bard-mood", "lively"));

        assertNotSame(before, after);
        assertTrue(before.flag("bard-mood").isEmpty(), "the receiver is untouched");
        assertEquals(Optional.of("lively"), after.flag("bard-mood"));
    }

    @Test
    @DisplayName("nextTurn advances the counter; flags survive the turn boundary")
    void nextTurnSemantics() {
        WorldState world = WorldState.initial("tavern-hall")
                .apply(new WorldEffect.SetFlag("bard-mood", "lively"))
                .nextTurn()
                .nextTurn();

        assertEquals(2, world.turnCount());
        assertEquals(Optional.of("lively"), world.flag("bard-mood"));
    }

    @Test
    @DisplayName("describe lists flags after the location")
    void describeWithFlags() {
        WorldState world = WorldState.initial("tavern-hall")
                .apply(new WorldEffect.SetFlag("bard-mood", "lively"))
                .apply(new WorldEffect.SetFlag("quarrel", "brewing"))
                .nextTurn();

        assertEquals("Turn 1 · tavern-hall · bard-mood=lively, quarrel=brewing", world.describe());
    }

    @Test
    @DisplayName("flags map is an immutable copy - external mutation cannot reach the world")
    void flagsMapIsCopied() {
        Map<String, String> external = new java.util.LinkedHashMap<>();
        external.put("leak", "attempted");
        WorldState world = new WorldState(0, "hall", external);
        external.put("leak", "mutated");

        assertEquals("attempted", world.flag("leak").get());
        assertThrows(UnsupportedOperationException.class,
                () -> world.flags().put("hack", "x"));
    }

    @Test
    @DisplayName("blank location is rejected fail-fast")
    void blankLocationRejected() {
        assertThrows(IllegalArgumentException.class, () -> WorldState.initial(" "));
    }
}
