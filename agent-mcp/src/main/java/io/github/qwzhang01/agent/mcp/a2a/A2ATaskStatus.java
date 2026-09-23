package io.github.qwzhang01.agent.mcp.a2a;

/**
 * Task lifecycle states, in the A2A protocol's dialect (not our own words).
 * <p>
 * The wire labels are exactly the spec's: a task moves
 * {@code submitted -> working -> completed | failed | input-required |
 * canceled | rejected}. Before this enum existed the in-process client
 * tracked status as free strings "running" -- close, but a dialect is a
 * contract: a remote peer reading "running" cannot map it to any spec state.
 * Every status this framework emits or accepts now round-trips through
 * these labels.
 * <p>
 * {@code input-required} is the state that separates A2A from a plain RPC:
 * the remote agent did some work and is waiting for more input before the
 * task can continue. v1 surfaces it to the caller as data (see
 * {@link A2AClient#sendTask}); continuing such a task needs message history
 * support that is deliberately v2.
 */
public enum A2ATaskStatus {

    /** Accepted by the server, not started yet. */
    SUBMITTED("submitted"),
    /** Being worked on right now (the old in-process label "running" maps here). */
    WORKING("working"),
    /** Agent paused: needs more input from the caller before continuing. */
    INPUT_REQUIRED("input-required"),
    /** Done; artifacts carry the result. */
    COMPLETED("completed"),
    FAILED("failed"),
    /** Caller (or server policy) canceled the task. */
    CANCELED("canceled"),
    /** Server refused the task before running it (e.g. blocked by an inbound sanitizer). */
    REJECTED("rejected");

    private final String label;

    A2ATaskStatus(String label) {
        this.label = label;
    }

    /** The wire label used in JSON ({@code "input-required"}, not the enum name). */
    public String label() {
        return label;
    }

    /**
     * Parse a wire label. Returns null for null/unknown labels instead of
     * throwing: a peer may evolve new states, and the caller decides whether
     * that is fail-open (treat as working) or fail-closed (treat as failed).
     */
    public static A2ATaskStatus fromLabel(String label) {
        if (label == null) {
            return null;
        }
        for (A2ATaskStatus status : values()) {
            if (status.label.equals(label)) {
                return status;
            }
        }
        return null;
    }
}
