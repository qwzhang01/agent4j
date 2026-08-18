package io.github.qwzhang01.agent.scheduler;

import java.time.Instant;
import java.util.UUID;

/**
 * An async task produced by an Agent at runtime.
 * <p>
 * Unlike ParallelNode (static parallelism defined in the graph), AsyncTaskQueue
 * tasks are dynamically produced by the Agent during execution. The Agent decides
 * "I need to dispatch 3 sub-tasks" at runtime, not at graph-definition time.
 * <p>
 * Each task carries enough to start a new Workflow run independently.
 *
 * @param taskId        unique id
 * @param parentRunId   the Run that produced this task (for tracing)
 * @param input         task input (passed to the new Run)
 * @param priority      queue consumption order
 * @param status        current lifecycle state
 * @param workflowName  which Workflow to run for this task
 * @param createdAt     when the task was enqueued
 * @param startedAt     when a consumer started it
 * @param completedAt   when it reached a terminal state
 * @param result        task output (null until SUCCEEDED)
 */
public record AsyncTask(
        String taskId,
        String parentRunId,
        Object input,
        TaskPriority priority,
        TaskStatus status,
        String workflowName,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Object result
) {
    public static AsyncTask of(String parentRunId, Object input, TaskPriority priority, String workflowName) {
        return new AsyncTask(
                UUID.randomUUID().toString(),
                parentRunId, input, priority,
                TaskStatus.PENDING, workflowName,
                Instant.now(), null, null, null);
    }

    public AsyncTask withStatus(TaskStatus newStatus) {
        return new AsyncTask(taskId, parentRunId, input, priority, newStatus, workflowName,
                createdAt,
                newStatus == TaskStatus.RUNNING ? Instant.now() : startedAt,
                newStatus.isTerminal() ? Instant.now() : completedAt,
                result);
    }

    public AsyncTask withResult(Object result) {
        return new AsyncTask(taskId, parentRunId, input, priority, TaskStatus.SUCCEEDED, workflowName,
                createdAt, startedAt, Instant.now(), result);
    }
}
