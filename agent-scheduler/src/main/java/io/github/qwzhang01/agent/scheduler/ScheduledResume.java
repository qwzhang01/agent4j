package io.github.qwzhang01.agent.scheduler;

import java.time.Instant;
import java.util.UUID;

/**
 * A scheduled resume: automatically resume a paused Run after a delay.
 * <p>
 * Registered by a node (e.g. ScheduleResumeNode) via
 * {@code ctx.scheduler().scheduleResume(runId, delay)}.
 * The scheduler fires {@code RunManager.resume(runId)} when the delay elapses.
 *
 * @param resumeId   unique id
 * @param runId      the Run to resume
 * @param fireAt     when to trigger the resume
 * @param recurring  whether this is a recurring schedule (e.g. check every 2h)
 * @param interval   recurring interval (null if not recurring)
 */
public record ScheduledResume(
        String resumeId,
        String runId,
        Instant fireAt,
        boolean recurring,
        java.time.Duration interval
) {
    public static ScheduledResume once(String runId, Instant fireAt) {
        return new ScheduledResume(UUID.randomUUID().toString(), runId, fireAt, false, null);
    }

    public static ScheduledResume recurring(String runId, Instant firstFireAt, java.time.Duration interval) {
        return new ScheduledResume(UUID.randomUUID().toString(), runId, firstFireAt, true, interval);
    }
}
