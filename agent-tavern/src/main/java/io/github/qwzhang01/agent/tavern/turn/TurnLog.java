package io.github.qwzhang01.agent.tavern.turn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Append-only turn ledger (Stage 16 M16.2).
 * <p>
 * In-memory for now: appended turns are immutable records and the view is
 * unmodifiable - "append-only" as a memory-level contract (a settled turn can
 * never be edited). The JSONL file form (first line = initial world snapshot
 * envelope, one line per turn, byte-stable after write) lands in M16.4 with
 * {@code GameStore} - the same discipline as Stage 14's sidecar rule: the
 * record is written once and never rewritten.
 */
public final class TurnLog {

    private final List<Turn> turns = new ArrayList<>();
    private final List<Turn> view = Collections.unmodifiableList(turns);

    /**
     * Append a settled turn. The log only ever grows.
     */
    public TurnLog append(Turn turn) {
        if (turn == null) {
            throw new IllegalArgumentException("turn must not be null");
        }
        turns.add(turn);
        return this;
    }

    /** Immutable view of all settled turns, in play order. */
    public List<Turn> turns() {
        return view;
    }

    public int size() {
        return turns.size();
    }
}
