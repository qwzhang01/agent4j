package io.github.qwzhang01.agent.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
    private volatile Instant timedOutAt;
    /** 0 = pending, 1 = fired, 2 = timed out. CAS so fire and timeout cannot both win. */
    private final AtomicInteger outcome = new AtomicInteger(0);

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

    /**
     * Atomically claim this trigger as fired.
     *
     * @return true if this caller won (was still pending)
     */
    public boolean tryMarkFired() {
        if (outcome.compareAndSet(0, 1)) {
            this.firedAt = Instant.now();
            return true;
        }
        return false;
    }

    /**
     * Atomically claim this trigger as timed out.
     *
     * @return true if this caller won (was still pending)
     */
    public boolean tryMarkTimedOut() {
        if (outcome.compareAndSet(0, 2)) {
            this.timedOutAt = Instant.now();
            return true;
        }
        return false;
    }

    /** Called by EventBroker when the event fires. Prefer {@link #tryMarkFired()}. */
    public void markFired() {
        tryMarkFired();
    }

    /** Called by EventBroker when the wait times out without a fire. Prefer {@link #tryMarkTimedOut()}. */
    public void markTimedOut() {
        tryMarkTimedOut();
    }

    public boolean isFired() {
        return outcome.get() == 1 || firedAt != null;
    }

    public boolean wasTimedOut() {
        return outcome.get() == 2 || timedOutAt != null;
    }

    public boolean isTimedOut(Instant now) {
        return timeout != null && !isFired() && now.isAfter(registeredAt.plus(timeout));
    }
}
