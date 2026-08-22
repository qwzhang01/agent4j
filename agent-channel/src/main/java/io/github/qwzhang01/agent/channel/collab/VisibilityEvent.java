package io.github.qwzhang01.agent.channel.collab;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One execution-visibility event (Stage 12 M12.3, design D6).
 * <p>
 * Every milestone of the channel agent's work is published as an event:
 * task lifecycle, handoffs, agent replies. Channel members (humans or
 * frontends) subscribe to the stream instead of polling, and the
 * {@link TaskBoard} is a MATERIALIZED VIEW of this same stream - one
 * source of truth for visibility (and a bridge target for Stage 9
 * auditing, since events carry full attribution).
 *
 * @param eventId   unique event id
 * @param channelId the channel this event belongs to
 * @param type      what happened
 * @param taskId    the task involved (null for conversation-level events)
 * @param actor     who did it: a userId, or the agentId for agent actions
 * @param target    structural counterpart (e.g. handoff's toUser, the thing
 *                  waited on); null when not applicable
 * @param detail    human-readable summary
 * @param timestamp when it happened
 */
public record VisibilityEvent(
        String eventId,
        String channelId,
        Type type,
        String taskId,
        String actor,
        String target,
        String detail,
        Instant timestamp
) {

    /** The kinds of milestones the channel agent publishes. */
    public enum Type {
        /** A task was created on the board (detail = description). */
        TASK_STARTED,
        /** Generic progress note on a task. */
        TASK_PROGRESS,
        /** The task is paused waiting for a human decision (target = what). */
        WAITING_HUMAN,
        /** A waited task resumed (target = who resumed it). */
        RESUMED,
        /** The task reached SUCCEEDED (detail = summary). */
        TASK_COMPLETED,
        /** The task reached FAILED (detail = reason). */
        TASK_FAILED,
        /** Task ownership moved: actor = from, target = to (detail = note). */
        TASK_HANDOFF,
        /** The agent replied in conversation (conversation-level, taskId null). */
        AGENT_REPLIED,
        /** An ambient instruction pushed proactively (M12.4; taskId null). */
        NOTIFICATION_SENT
    }

    public VisibilityEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(channelId, "channelId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    /**
     * Convenience factory: id and timestamp auto-generated.
     */
    public static VisibilityEvent of(String channelId, Type type, String taskId,
                                     String actor, String target, String detail) {
        return new VisibilityEvent(UUID.randomUUID().toString(), channelId, type,
                taskId, actor, target, detail, Instant.now());
    }
}
