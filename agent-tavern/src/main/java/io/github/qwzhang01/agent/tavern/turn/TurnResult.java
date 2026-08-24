package io.github.qwzhang01.agent.tavern.turn;

import java.util.List;

/**
 * Result of one {@code playTurn} call (Stage 16 M16.2).
 * <p>
 * Sealed because there are exactly two outcomes: the turn was played
 * (and logged), or routing failed before the model was ever invoked
 * (blueprint F1: a mention miss must not burn a model call or a turn number).
 */
public sealed interface TurnResult {

    /** The turn was played and appended to the turn log. */
    record Completed(Turn turn) implements TurnResult {
        public Completed {
            if (turn == null) {
                throw new IllegalArgumentException("turn must not be null");
            }
        }
    }

    /**
     * No character was mentioned (or the mention matched nobody): the engine
     * did not run the model, did not advance the turn count, did not log.
     */
    record RoutingMiss(String playerInput, String message, List<String> availableCharacters)
            implements TurnResult {
        public RoutingMiss {
            if (playerInput == null || message == null) {
                throw new IllegalArgumentException("playerInput and message must not be null");
            }
            availableCharacters = availableCharacters == null ? List.of() : List.copyOf(availableCharacters);
        }
    }
}
