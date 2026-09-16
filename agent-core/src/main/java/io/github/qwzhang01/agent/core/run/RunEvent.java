package io.github.qwzhang01.agent.core.run;

import java.time.Instant;

/**
 * Unified lifecycle events (Stage 1.3 of the harness roadmap).
 * <p>
 * One sealed interface for run-level facts: RunStarted / StepStarted /
 * StepCompleted / RunPaused / RunResumed / RunCanceled / RunFailed /
 * RunCompleted. Every event carries runId, traceId, stepId (when
 * step-scoped), attempt (when retry-scoped), timestamp and duration.
 * These are <b>fact events</b> (execution truth), not telemetry: they
 * describe what the runtime actually did, and are the persistence
 * boundary for Stage 3's event-sourced recovery. Metric objects
 * (agent-observability HealthPipeline) are separate concerns and must
 * never be conflated with these facts.
 * <p>
 * Event schema version 1 (roadmap 1.3: version field for future
 * persistence and cross-process consumers).
 * <p>
 * Dispatch model (v1): push to a {@code Consumer<RunEvent>} sink handed
 * in at the entry boundary. Explicit propagation is the contract;
 * ThreadLocal broadcasters are a compatibility convenience, not the
 * mechanism of record.
 */
public sealed interface RunEvent permits
        RunEvent.RunStarted, RunEvent.StepStarted, RunEvent.StepCompleted,
        RunEvent.RunPaused, RunEvent.RunResumed, RunEvent.RunCanceled,
        RunEvent.RunFailed, RunEvent.RunCompleted {

    /** Event schema version (1 since Stage 1). */
    int SCHEMA_VERSION = 1;

    /** Correlation: the run this fact belongs to. */
    String runId();

    /** Trace correlation across parent/child runs and async callbacks. */
    String traceId();

    /** Wall-clock time the fact occurred. */
    Instant occurredAt();

    /** Schema version of this event instance (always SCHEMA_VERSION today). */
    default int schemaVersion() {
        return SCHEMA_VERSION;
    }

    // ============ Event Types ============

    /** The run began executing (after entry validation). */
    record RunStarted(String runId, String traceId, Instant occurredAt) implements RunEvent {
    }

    /** A step (loop iteration / node execution) began. */
    record StepStarted(String runId, String traceId, String stepId, int stepIndex,
                       int attempt, Instant occurredAt) implements RunEvent {
    }

    /**
     * A step finished. durationMs is wall time; failureKind is null on
     * success (a cancelled/timeout step carries CANCELLED/TIMEOUT, not
     * TOOL_FAILURE - cancellation must not be recorded as business failure).
     */
    record StepCompleted(String runId, String traceId, String stepId, int stepIndex,
                         int attempt, long durationMs, String summary,
                         FailureKind failureKind, Instant occurredAt) implements RunEvent {
    }

    /** The run paused at an approval/waiting point (workflow PAUSED). */
    record RunPaused(String runId, String traceId, String reason, Instant occurredAt) implements RunEvent {
    }

    /** The run resumed from a pause (workflow resume). */
    record RunResumed(String runId, String traceId, String fromStep, Instant occurredAt) implements RunEvent {
    }

    /** The run was cancelled by a caller. Not an error. */
    record RunCanceled(String runId, String traceId, String reason, Instant occurredAt) implements RunEvent {
    }

    /** The run failed. failureKind is mandatory (never free text only). */
    record RunFailed(String runId, String traceId, FailureKind failureKind,
                     String message, Instant occurredAt) implements RunEvent {
    }

    /** The run completed successfully. */
    record RunCompleted(String runId, String traceId, long durationMs,
                        int steps, Instant occurredAt) implements RunEvent {
    }
}
