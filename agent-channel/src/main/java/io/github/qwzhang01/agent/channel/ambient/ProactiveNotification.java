package io.github.qwzhang01.agent.channel.ambient;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One proactive push produced by an ambient instruction (Stage 12 M12.4).
 * <p>
 * Attribution (design D4): the actor is the AGENT identity, never the
 * event's originator - "the agent chose to speak", not "the cron job
 * spoke". Sinks receive this record; the session's visibility stream
 * carries the matching NOTIFICATION_SENT event.
 *
 * @param notificationId unique id
 * @param instructionId  which instruction produced it
 * @param channelId      which channel it was pushed to
 * @param actor          the agent id (service identity attribution)
 * @param content        what was said
 * @param importance     the instruction's noise tier
 * @param createdAt      when it was produced
 */
public record ProactiveNotification(
        String notificationId,
        String instructionId,
        String channelId,
        String actor,
        String content,
        AmbientInstruction.Importance importance,
        Instant createdAt
) {

    public ProactiveNotification {
        Objects.requireNonNull(instructionId, "instructionId must not be null");
        Objects.requireNonNull(channelId, "channelId must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(importance, "importance must not be null");
        if (notificationId == null) notificationId = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = Instant.now();
    }
}
