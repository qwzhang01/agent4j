package io.github.qwzhang01.agent.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * An event-driven resume: automatically resume a paused Run when an external
 * event fires.
 * <p>
 * Registered by a node (e.g. WaitEventNode) via
 * {@code ctx.scheduler().waitForEvent(runId, eventKey)}.
 * When {@code EventBroker.fire(eventKey)} is called, the scheduler resumes the Run.
 *
 * @param triggerId    unique id
 * @param runId        the Run to resume
 * @param eventKey     the event to wait for (e.g. "ci-passed:pr-123")
 * @param registeredAt when this trigger was registered
 * @param timeout      how long to wait before failing (null = no timeout)
 * @param firedAt      when the event fired (null = not yet fired)
 */
public record EventTrigger(
        String triggerId,
        String runId,
        String eventKey,
        Instant registeredAt,
        Duration timeout,
        Instant firedAt
) {
    public static EventTrigger of(String runId, String eventKey, Duration timeout) {
        return new EventTrigger(UUID.randomUUID().toString(), runId, eventKey,
                Instant.now(), timeout, null);
    }

    public boolean isFired() {
        return firedAt != null;
    }

    public boolean isTimedOut(Instant now) {
        return timeout != null && !isFired() && now.isAfter(registeredAt.plus(timeout));
    }
}
