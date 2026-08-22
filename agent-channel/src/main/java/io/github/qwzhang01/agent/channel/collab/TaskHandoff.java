package io.github.qwzhang01.agent.channel.collab;

import java.time.Instant;
import java.util.Objects;

/**
 * Record of one task handoff between members (Stage 12 M12.3, design D5).
 * <p>
 * A handoff moves THREE things, not one:
 * <ol>
 *   <li>the conversation state (never rebuilt - the shared AgentState just
 *       continues, plus an injected system note so the model knows the
 *       baton moved)</li>
 *   <li>the working memory (free in v1: channel/task scopes are already
 *       shared through the memory store's scope isolation)</li>
 *   <li>the board ownership (a TASK_HANDOFF event; the TaskBoard view
 *       follows automatically)</li>
 * </ol>
 * This record itself is the audit trail: who gave, who took, why, when.
 *
 * @param taskId     the handed-off task
 * @param fromUser   the previous owner (must equal the owner at handoff time)
 * @param toUser     the new owner (must be a channel member)
 * @param note       why / what state things are in (shown to the model too)
 * @param handedOffAt when it happened
 */
public record TaskHandoff(
        String taskId,
        String fromUser,
        String toUser,
        String note,
        Instant handedOffAt
) {

    public TaskHandoff {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(fromUser, "fromUser must not be null");
        Objects.requireNonNull(toUser, "toUser must not be null");
        if (handedOffAt == null) handedOffAt = Instant.now();
    }
}
