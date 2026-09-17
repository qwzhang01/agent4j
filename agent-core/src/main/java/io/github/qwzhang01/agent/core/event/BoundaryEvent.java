package io.github.qwzhang01.agent.core.event;

import java.time.Instant;

/**
 * Boundary telemetry events (harness 4.4, roadmap 1.3 deferred batch:
 * Model / Memory / Approval / Sandbox get start/finish/failure facts).
 * <p>
 * These are BYPASS TELEMETRY, not fact events: they observe what a
 * boundary did, never gate execution. The fact-event family is
 * {@link io.github.qwzhang01.agent.core.run.RunEvent} (execution truth,
 * the persistence boundary for event-sourced recovery); these events are
 * the observability twins for boundaries the run-level family does not
 * cover. A throwing sink is the publisher's side-channel problem, never
 * the boundary's.
 * <p>
 * Discipline (same as Stage 7's content red line): events carry STRUCTURE
 * only — ids, scopes, counts, durations, kinds. Never memory content, never
 * approval payloads, never sandboxed code. What crossed the boundary is
 * the audit ledger's business; how long it took and whether it failed is
 * ours.
 * <p>
 * Model boundary note: {@link io.github.qwzhang01.agent.core.agent.AgentEvent}
 * already carries {@code ModelCallStarted/Finished} on the loop path. The
 * {@link ModelBoundaryEvent} family here is the HOST-facing aggregate for
 * the same boundary — it exists so assembly-level consumers (durable runs,
 * batch workers) that do not sit on an agent loop still get the pair from
 * the client decorators that meter them.
 */
public sealed interface BoundaryEvent permits
        BoundaryEvent.MemoryBoundaryEvent, BoundaryEvent.ApprovalBoundaryEvent,
        BoundaryEvent.SandboxBoundaryEvent, BoundaryEvent.ModelBoundaryEvent,
        BoundaryEvent.McpBoundaryEvent, BoundaryEvent.A2ABoundaryEvent {

    /** Boundary telemetry schema version (1 since harness 4.4). */
    int SCHEMA_VERSION = 1;

    /** Wall clock the boundary event occurred. */
    Instant occurredAt();

    /** Schema version of this event instance. */
    default int schemaVersion() {
        return SCHEMA_VERSION;
    }

    // ============ Memory boundary ============

    /**
     * The memory boundary served (or failed) one governed access. One
     * event per {@code MemoryGovernance.query/write} call — the audit
     * ledger already records WHO and WHY; this event records HOW it went
     * (count, duration, masked or not).
     */
    sealed interface MemoryBoundaryEvent extends BoundaryEvent permits
            MemoryAccessed, MemoryAccessFailed {
    }

    /**
     * @param operation  "query" or "write"
     * @param purpose    the declared purpose string (audit ledger's twin)
     * @param resultCount entries returned (query) or 1 (write)
     * @param masked     whether redaction changed consumer-visible content
     * @param durationMs wall-clock duration of the governed access
     */
    record MemoryAccessed(String operation, String purpose, int resultCount,
                          boolean masked, long durationMs, Instant occurredAt)
            implements MemoryBoundaryEvent {
    }

    /**
     * A governed access was refused before the store was touched (no
     * identity, purpose blank, scope outside whitelist). Refusal is a
     * fact worth counting separately from success: governance that never
     * refuses is governance that never engages.
     *
     * @param operation "query" or "write"
     * @param reason    the refusal message
     */
    record MemoryAccessFailed(String operation, String reason, Instant occurredAt)
            implements MemoryBoundaryEvent {
    }

    // ============ Approval boundary ============

    /**
     * The approval boundary produced a decision (or refused to).
     */
    sealed interface ApprovalBoundaryEvent extends BoundaryEvent permits
            ApprovalDecided, ApprovalRefused {
    }

    /**
     * @param approvalId the request's durable id
     * @param runId      the run the request belongs to
     * @param decision   "APPROVED" / "REJECTED" / "EXPIRED" / "REVOKED"
     * @param decidedBy  the decider identity (never the payload)
     */
    record ApprovalDecided(String approvalId, String runId, String decision,
                           String decidedBy, Instant occurredAt)
            implements ApprovalBoundaryEvent {
    }

    /**
     * A decision attempt was refused (conflict: missing request, stale
     * version, double-decide, non-APPROVED revoke). The conflict detail
     * is the store's own exception text — structure, not payload.
     *
     * @param approvalId the request the decision targeted
     * @param reason     the refusal reason
     */
    record ApprovalRefused(String approvalId, String reason, Instant occurredAt)
            implements ApprovalBoundaryEvent {
    }

    // ============ Sandbox boundary ============

    /**
     * The sandbox boundary executed (or refused) guest code.
     */
    sealed interface SandboxBoundaryEvent extends BoundaryEvent permits
            SandboxExecuted, SandboxEscalated, SandboxRefused {
    }

    /**
     * @param tier      which tier served the execution ("CLASSLOADER",
     *                  "PROCESS", ...)
     * @param className the guest class name (an id, not content)
     * @param success   whether the guest code completed successfully
     * @param durationMs wall-clock duration
     */
    record SandboxExecuted(String tier, String className, boolean success,
                           long durationMs, Instant occurredAt)
            implements SandboxBoundaryEvent {
    }

    /**
     * The fast tier blocked the code and the escalator promoted it to the
     * strong tier (the designed path, not an error).
     *
     * @param className what was promoted
     * @param budgetUsedFor attribution key the budget was charged to
     */
    record SandboxEscalated(String className, String budgetUsedFor, Instant occurredAt)
            implements SandboxBoundaryEvent {
    }

    /**
     * Execution was refused: policy block, budget exhaustion, invalid
     * input. Refusal kind is structural ("BLOCKED_BY_POLICY",
     * "BUDGET_SPENT", ...), never guest output.
     *
     * @param refusalKind structural refusal classification
     * @param className   what was refused
     */
    record SandboxRefused(String refusalKind, String className, Instant occurredAt)
            implements SandboxBoundaryEvent {
    }

    // ============ Model boundary (host-facing aggregate) ============

    /**
     * The model boundary as the host sees it (the loop-facing twin is
     * {@code AgentEvent.ModelCallStarted/Finished}; this family serves
     * consumers that sit on a client decorator, not an agent loop).
     */
    sealed interface ModelBoundaryEvent extends BoundaryEvent permits
            ModelServingStarted, ModelServingFinished {
    }

    /** @param modelId the model identity being called */
    record ModelServingStarted(String modelId, Instant occurredAt)
            implements ModelBoundaryEvent {
    }

    /**
     * @param modelId      the model identity that served
     * @param latencyMs    wall-clock duration of the serving call
     * @param tokenCount   total tokens when known, -1 when not reported
     * @param failed       whether the call failed
     * @param failureKind  structural failure classification when failed
     */
    record ModelServingFinished(String modelId, long latencyMs, int tokenCount,
                                boolean failed, String failureKind, Instant occurredAt)
            implements ModelBoundaryEvent {
    }

    // ============ MCP boundary (harness batch 7: the emitter batch) ============

    /**
     * The MCP boundary called (or failed to call) a remote tool. One event
     * per {@code McpClient.callTool} execution — the host-facing aggregate
     * for the protocol boundary, mirroring {@link ModelServingFinished}'s
     * shape: the span opens at finish when latency and outcome are known,
     * never fabricating a duration.
     * <p>
     * Covers both transport shapes (stdio subprocess / SSE remote server):
     * the boundary fact is "a tool crossed the MCP protocol line and how it
     * went", not which transport carried it.
     */
    sealed interface McpBoundaryEvent extends BoundaryEvent permits
            McpToolCallFinished, McpToolCallFailed {
    }

    /**
     * @param serverName the MCP server's name (descriptor identity, an id)
     * @param toolName   the tool that was called (an id, never args/results)
     * @param latencyMs  wall-clock duration of the call
     */
    record McpToolCallFinished(String serverName, String toolName, long latencyMs,
                               Instant occurredAt)
            implements McpBoundaryEvent {
    }

    /**
     * The call never completed: schema refusal before the wire, transport
     * death mid-call, protocol error, or a cancellation signal. The
     * failureKind is structural ("SCHEMA_INVALID", "TRANSPORT", "TIMEOUT",
     * "CANCELLED", "PROTOCOL"), never tool output or args content.
     *
     * @param serverName  the MCP server's name
     * @param toolName    the tool that was attempted
     * @param failureKind structural failure classification
     */
    record McpToolCallFailed(String serverName, String toolName, String failureKind,
                             Instant occurredAt)
            implements McpBoundaryEvent {
    }

    // ============ A2A boundary (harness batch 7: the emitter batch) ============

    /**
     * The A2A boundary delegated (or failed to delegate) a task. One event
     * per {@code A2AClient.sendTask} execution — the protocol boundary twin
     * of the MCP family, covering both the in-process and HTTP transports
     * (which transport carried the task is the client's business; the
     * boundary fact is "a task crossed the A2A protocol line and how it
     * went").
     */
    sealed interface A2ABoundaryEvent extends BoundaryEvent permits
            A2ATaskSent, A2ATaskFailed {
    }

    /**
     * @param taskId    the task's id (sender-assigned pre-wire, an id)
     * @param recipient the recipient agent's name (an id, never payload)
     * @param latencyMs wall-clock duration of the delegation
     */
    record A2ATaskSent(String taskId, String recipient, long latencyMs,
                       Instant occurredAt)
            implements A2ABoundaryEvent {
    }

    /**
     * The delegation never completed: unknown recipient, the peer agent
     * ended in an error state, transport/protocol failure. The failureKind
     * is structural ("UNKNOWN_RECIPIENT", "AGENT_FAILED", "TRANSPORT",
     * "PROTOCOL"), never task payload.
     *
     * @param taskId      the task that was attempted
     * @param recipient   the intended recipient
     * @param failureKind structural failure classification
     */
    record A2ATaskFailed(String taskId, String recipient, String failureKind,
                         Instant occurredAt)
            implements A2ABoundaryEvent {
    }
}
