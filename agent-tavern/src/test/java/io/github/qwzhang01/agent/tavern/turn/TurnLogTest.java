package io.github.qwzhang01.agent.tavern.turn;

import io.github.qwzhang01.agent.tavern.turn.TurnLog;
import io.github.qwzhang01.agent.tavern.turn.Turn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Stage 16 M16.2: the turn log is append-only (memory-level contract;
 * the JSONL byte-stable form lands in M16.4 with GameStore).
 */
class TurnLogTest {

    private Turn turn(int no) {
        return new Turn(no, "input", "marcus",
                java.util.List.of(new Turn.CharacterResponse("marcus", "reply", false)),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), Instant.now());
    }

    @Test
    @DisplayName("appended turns come back in play order")
    void appendKeepsOrder() {
        TurnLog log = new TurnLog();
        log.append(turn(1)).append(turn(2)).append(turn(3));

        assertEquals(3, log.size());
        assertEquals(1, log.turns().get(0).turnNo());
        assertEquals(3, log.turns().get(2).turnNo());
    }

    @Test
    @DisplayName("the view is unmodifiable - a settled turn can never be edited")
    void viewIsUnmodifiable() {
        TurnLog log = new TurnLog();
        log.append(turn(1));

        assertThrows(UnsupportedOperationException.class,
                () -> log.turns().add(turn(2)));
        assertThrows(UnsupportedOperationException.class,
                () -> log.turns().clear());
    }

    @Test
    @DisplayName("null append is rejected fail-fast")
    void nullAppendRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TurnLog().append(null));
    }
}
