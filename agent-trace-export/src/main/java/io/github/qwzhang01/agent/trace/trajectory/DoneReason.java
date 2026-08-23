package io.github.qwzhang01.agent.trace.trajectory;

import io.github.qwzhang01.agent.core.agent.AgentState;

/**
 * Why a run terminated (Stage 14 trajectory terminal marker).
 * <p>
 * Mirrors {@link AgentState.Status} terminal states and adds {@link #CANCELLED}
 * for future workflow-run trajectories. Non-terminal loop statuses
 * (IDLE / RUNNING / EXECUTING_TOOL) map to {@code null} - a trajectory that
 * never reached a terminal status is assembled as ERROR by the session
 * (see RecordingSession), so DoneReason instances only exist for real endings.
 */
public enum DoneReason {
    DONE,
    MAX_STEPS_EXCEEDED,
    ERROR,
    CANCELLED;

    /**
     * Map a loop status to its terminal reason, or null if not terminal.
     */
    public static DoneReason from(AgentState.Status status) {
        return switch (status) {
            case DONE -> DONE;
            case MAX_STEPS_EXCEEDED -> MAX_STEPS_EXCEEDED;
            case ERROR -> ERROR;
            case IDLE, RUNNING, EXECUTING_TOOL -> null;
        };
    }
}
