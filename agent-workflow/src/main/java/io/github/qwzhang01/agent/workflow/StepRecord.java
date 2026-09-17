package io.github.qwzhang01.agent.workflow;

/**
 * One executed step in a workflow run (trace entry).
 * <p>
 * This is the workflow-level equivalent of AgentState's step tracking.
 * Stage 14 (RL trajectory export) consumes these records directly.
 * <p>
 * Stage 6 additions: PAUSED and CANCELLED statuses.
 * <p>
 * Harness 3.2 additions (2026-09-17): visit ordinal and start/end
 * timestamps. The ordinal is assigned by {@link WorkflowState#record}
 * (1-based position in the trace — every entry gets one, restored traces
 * keep their original numbering); {@code startedAt}/{@code endedAt} are
 * epoch millis of the node execution window. Null on legacy rows and on
 * control-flow entries where no node execution began (e.g. a cancel
 * observed at the loop head) — absence is honest, never faked.
 *
 * @param nodeId       node that executed
 * @param status       SUCCESS / FAILED / PAUSED / CANCELLED
 * @param durationMs   wall time of all attempts
 * @param attempts     total execution attempts (1 + retries)
 * @param summary      short output summary or exception message
 * @param visitOrdinal 1-based position in the run's trace (null = legacy row)
 * @param startedAt    epoch ms when node execution began (null = not recorded)
 * @param endedAt      epoch ms when node execution ended (null = not recorded)
 */
public record StepRecord(String nodeId, Status status, long durationMs, int attempts, String summary,
                         Integer visitOrdinal, Long startedAt, Long endedAt) {

    public enum Status {SUCCESS, FAILED, PAUSED, CANCELLED}

    /**
     * Legacy 5-field shape (pre-0.1.4 rows and control-flow entries).
     * Kept source- and binary-compatible: visit metadata stays null.
     */
    public StepRecord(String nodeId, Status status, long durationMs, int attempts, String summary) {
        this(nodeId, status, durationMs, attempts, summary, null, null, null);
    }

    public static StepRecord success(String nodeId, long durationMs, int attempts, String summary) {
        return new StepRecord(nodeId, Status.SUCCESS, durationMs, attempts, summary);
    }

    /** Visit-aware success: carries the execution window; ordinal assigned on record. */
    public static StepRecord success(String nodeId, long durationMs, int attempts, String summary,
                                      long startedAt, long endedAt) {
        return new StepRecord(nodeId, Status.SUCCESS, durationMs, attempts, summary,
                null, startedAt, endedAt);
    }

    public static StepRecord failed(String nodeId, long durationMs, int attempts, String error) {
        return new StepRecord(nodeId, Status.FAILED, durationMs, attempts, error);
    }

    /** Visit-aware failure: carries the execution window; ordinal assigned on record. */
    public static StepRecord failed(String nodeId, long durationMs, int attempts, String error,
                                    long startedAt, long endedAt) {
        return new StepRecord(nodeId, Status.FAILED, durationMs, attempts, error,
                null, startedAt, endedAt);
    }

    public static StepRecord paused(String nodeId, String reason) {
        return new StepRecord(nodeId, Status.PAUSED, 0, 0, reason);
    }

    /** Visit-aware pause: the node started executing and then requested a pause. */
    public static StepRecord paused(String nodeId, String reason, long startedAt, long endedAt) {
        return new StepRecord(nodeId, Status.PAUSED, 0, 0, reason, null, startedAt, endedAt);
    }

    public static StepRecord cancelled(String nodeId) {
        return new StepRecord(nodeId, Status.CANCELLED, 0, 0, "Run cancelled");
    }

    /** Assign the visit ordinal (used by {@link WorkflowState#record}). */
    public StepRecord withVisitOrdinal(int ordinal) {
        return new StepRecord(nodeId, status, durationMs, attempts, summary,
                ordinal, startedAt, endedAt);
    }
}
