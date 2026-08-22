package io.github.qwzhang01.agent.channel.collab;

import io.github.qwzhang01.agent.scheduler.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The channel task board: a MATERIALIZED VIEW of the visibility stream
 * (Stage 12 M12.3, design D6).
 * <p>
 * The board never invents state: it subscribes to
 * {@link ExecutionVisibility} like any other listener and projects events
 * into {@link ChannelTask}s. One stream, two consumers (humans see events,
 * the board shows the current table) - "task status visible to all channel
 * members" with a single source of truth.
 * <p>
 * Unknown-task events are ignored at debug level: the board is a
 * projection, not an enforcement point (validation lives in
 * {@code SharedAgentSession}).
 */
public class TaskBoard implements ExecutionVisibility.Listener {

    private static final Logger log = LoggerFactory.getLogger(TaskBoard.class);

    private final Map<String, ChannelTask> tasks = new ConcurrentHashMap<>();

    // ============ Listener (the only write path) ============

    @Override
    public void onEvent(VisibilityEvent event) {
        switch (event.type()) {
            case TASK_STARTED -> upsert(new ChannelTask(
                    event.taskId(), event.detail(), event.actor(),
                    TaskStatus.RUNNING, event.timestamp(), event.timestamp()));
            case TASK_PROGRESS -> mutate(event.taskId(), t -> t.withStatus(TaskStatus.RUNNING));
            case WAITING_HUMAN -> mutate(event.taskId(), t -> t.withStatus(TaskStatus.WAITING_HUMAN));
            case RESUMED -> mutate(event.taskId(), t -> t.withStatus(TaskStatus.RUNNING));
            case TASK_COMPLETED -> mutate(event.taskId(), t -> t.withStatus(TaskStatus.SUCCEEDED));
            case TASK_FAILED -> mutate(event.taskId(), t -> t.withStatus(TaskStatus.FAILED));
            case TASK_HANDOFF -> mutate(event.taskId(), t -> t.withOwner(event.target()));
            case AGENT_REPLIED, NOTIFICATION_SENT -> { /* conversation-level, no board change */ }
        }
    }

    // ============ Read-only views ============

    /**
     * One task by id.
     */
    public Optional<ChannelTask> task(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    /**
     * All tasks, in creation order.
     */
    public List<ChannelTask> tasks() {
        return tasks.values().stream()
                .sorted(Comparator.comparing(ChannelTask::createdAt))
                .toList();
    }

    /**
     * Tasks in the given status.
     */
    public List<ChannelTask> byStatus(TaskStatus status) {
        return tasks().stream().filter(t -> t.status() == status).toList();
    }

    /**
     * Tasks currently owned by the given member.
     */
    public List<ChannelTask> byOwner(String userId) {
        return tasks().stream().filter(t -> t.owner().equals(userId)).toList();
    }

    /**
     * Board size (for tests / health checks).
     */
    public int size() {
        return tasks.size();
    }

    // ============ Internals ============

    private void upsert(ChannelTask task) {
        tasks.put(task.taskId(), task);
    }

    private void mutate(String taskId, java.util.function.UnaryOperator<ChannelTask> fn) {
        tasks.computeIfPresent(taskId, (id, t) -> fn.apply(t));
        if (!tasks.containsKey(taskId)) {
            log.debug("[board] Ignoring {} for unknown task {}", "event", taskId);
        }
    }
}
