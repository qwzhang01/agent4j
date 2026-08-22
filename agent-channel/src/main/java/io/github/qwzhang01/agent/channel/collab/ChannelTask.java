package io.github.qwzhang01.agent.channel.collab;

import io.github.qwzhang01.agent.scheduler.TaskStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * A task on the channel board (Stage 12 M12.3).
 * <p>
 * Deliberately a LIGHT view: taskId / description / owner / status. The
 * lifecycle state machine reuses Stage 7's {@link TaskStatus} verbatim
 * (PENDING -> RUNNING -> WAITING_HUMAN -> ... -> SUCCEEDED/FAILED) - one
 * status vocabulary across the framework, no second enum to keep in sync.
 * <p>
 * Immutable: the only writer is {@link TaskBoard}, and only via
 * {@link VisibilityEvent}s (design D6 - one source of truth).
 *
 * @param taskId      unique task id
 * @param description what the task is about
 * @param owner       current owning member
 * @param status      lifecycle status
 * @param createdAt   when the task appeared on the board
 * @param updatedAt   when it last changed
 */
public record ChannelTask(
        String taskId,
        String description,
        String owner,
        TaskStatus status,
        Instant createdAt,
        Instant updatedAt
) {

    public ChannelTask {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = createdAt;
    }

    ChannelTask withOwner(String newOwner) {
        return new ChannelTask(taskId, description, newOwner, status, createdAt, Instant.now());
    }

    ChannelTask withStatus(TaskStatus newStatus) {
        return new ChannelTask(taskId, description, owner, newStatus, createdAt, Instant.now());
    }

    /**
     * Whether the task is in a terminal state (handoff to it is refused).
     */
    public boolean isTerminal() {
        return status.isTerminal();
    }
}
