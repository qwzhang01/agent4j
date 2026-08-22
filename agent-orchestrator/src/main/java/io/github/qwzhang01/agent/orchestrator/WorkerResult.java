package io.github.qwzhang01.agent.orchestrator;

import java.util.Objects;

/**
 * A worker's receipt for one {@link WorkerTask} execution (Stage 11 M11.1).
 * <p>
 * Contract: success/failure is DATA, never an exception -- the supervisor
 * dispatches workers in parallel and must be able to aggregate partial failures
 * (D4: one crashed worker must not blow up the whole orchestration).
 * <p>
 * Cost attribution v1 = bookkeeping, not allocation: {@code durationMs} and
 * {@code totalTokens} are recorded per result; how to split the bill across
 * tenants/workers is Stage 18's cost dashboard.
 *
 * @param taskId     the task this result belongs to
 * @param workerName which worker produced it
 * @param success    whether the task completed
 * @param output     the worker's output (null on failure)
 * @param error      failure description (null on success)
 * @param durationMs wall-clock execution time of the (final) attempt
 * @param attempts   how many attempts were made (1 = executed once, no retry)
 * @param totalTokens token usage of the execution; 0 = unknown / not wired
 *                   (v1: the core {@code Agent} interface does not expose token
 *                   stats yet -- Stage 18 observability will wire it)
 */
public record WorkerResult(
        String taskId,
        String workerName,
        boolean success,
        String output,
        String error,
        long durationMs,
        int attempts,
        long totalTokens
) {

    public WorkerResult {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(workerName, "workerName must not be null");
    }

    public static WorkerResult success(WorkerTask task, String output,
                                       long durationMs, int attempts, long totalTokens) {
        return new WorkerResult(task.taskId(), task.workerName(), true,
                output, null, durationMs, attempts, totalTokens);
    }

    public static WorkerResult failure(WorkerTask task, String error,
                                       long durationMs, int attempts) {
        return new WorkerResult(task.taskId(), task.workerName(), false,
                null, error, durationMs, attempts, 0);
    }
}
