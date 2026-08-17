package io.github.qwzhang01.agent.workflow;

/**
 * One executed step in a workflow run (trace entry).
 * <p>
 * This is the workflow-level equivalent of AgentState's step tracking.
 * Stage 14 (RL trajectory export) consumes these records directly.
 *
 * @param nodeId     node that executed
 * @param status     SUCCESS or FAILED
 * @param durationMs wall time of all attempts
 * @param attempts   total execution attempts (1 + retries)
 * @param summary    short output summary or exception message
 */
public record StepRecord(String nodeId, Status status, long durationMs, int attempts, String summary) {

    public static StepRecord success(String nodeId, long durationMs, int attempts, String summary) {
        return new StepRecord(nodeId, Status.SUCCESS, durationMs, attempts, summary);
    }

    public static StepRecord failed(String nodeId, long durationMs, int attempts, String error) {
        return new StepRecord(nodeId, Status.FAILED, durationMs, attempts, error);
    }

    public enum Status {SUCCESS, FAILED}
}
