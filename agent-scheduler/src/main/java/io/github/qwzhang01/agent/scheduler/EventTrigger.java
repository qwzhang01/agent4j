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
 * <p>
 * Mutable class (not a record): {@code firedAt} is set by EventBroker when the
 * event fires, so timeout watchers can check {@link #isFired()} to avoid a
 * racy second resume.
 */
public final class EventTrigger {

    private final String triggerId;
    private final String runId;
    private final String eventKey;
    private final Instant registeredAt;
    private final Duration timeout;
    private volatile Instant firedAt;

    public EventTrigger(String triggerId, String runId, String eventKey,
                        Instant registeredAt, Duration timeout) {
        this.triggerId = triggerId;
        this.runId = runId;
        this.eventKey = eventKey;
        this.registeredAt = registeredAt;
        this.timeout = timeout;
    }

    public static EventTrigger of(String runId, String eventKey, Duration timeout) {
        return new EventTrigger(UUID.randomUUID().toString(), runId, eventKey,
                Instant.now(), timeout);
    }

    public String triggerId() { return triggerId; }
    public String runId() { return runId; }
    public String eventKey() { return eventKey; }
    public Instant registeredAt() { return registeredAt; }
    public Duration timeout() { return timeout; }
    public Instant firedAt() { return firedAt; }

    /** Called by EventBroker when the event fires. */
    public void markFired() {
        this.firedAt = Instant.now();
    }

    public boolean isFired() {
        return firedAt != null;
    }

    public boolean isTimedOut(Instant now) {
        return timeout != null && !isFired() && now.isAfter(registeredAt.plus(timeout));
    }
}
