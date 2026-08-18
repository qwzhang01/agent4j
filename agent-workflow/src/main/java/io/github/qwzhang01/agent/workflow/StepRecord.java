package io.github.qwzhang01.agent.workflow;

/**
 * One executed step in a workflow run (trace entry).
 * <p>
 * This is the workflow-level equivalent of AgentState's step tracking.
 * Stage 14 (RL trajectory export) consumes these records directly.
 * <p>
 * Stage 6 additions: PAUSED and CANCELLED statuses.
 *
 * @param nodeId     node that executed
 * @param status     SUCCESS / FAILED / PAUSED / CANCELLED
 * @param durationMs wall time of all attempts
 * @param attempts   total execution attempts (1 + retries)
 * @param summary    short output summary or exception message
 */
public record StepRecord(String nodeId, Status status, long durationMs, int attempts, String summary) {

    public enum Status {SUCCESS, FAILED, PAUSED, CANCELLED}

    public static StepRecord success(String nodeId, long durationMs, int attempts, String summary) {
        return new StepRecord(nodeId, Status.SUCCESS, durationMs, attempts, summary);
    }

    public static StepRecord failed(String nodeId, long durationMs, int attempts, String error) {
        return new StepRecord(nodeId, Status.FAILED, durationMs, attempts, error);
    }

    public static StepRecord paused(String nodeId, String reason) {
        return new StepRecord(nodeId, Status.PAUSED, 0, 0, reason);
    }

    public static StepRecord cancelled(String nodeId) {
        return new StepRecord(nodeId, Status.CANCELLED, 0, 0, "Run cancelled");
    }
}
