package io.github.qwzhang01.agent.core.run;

/**
 * Unified failure taxonomy (/2.2 groundwork, harness contract 1.3).
 * <p>
 * v0.1.3 reality: only the sandbox has a typed FailureKind
 * (SANDBOX_FAILURE / BLOCKED_BY_POLICY / TIMEOUT / CODE_FAILURE); every
 * other boundary emits free strings "Tool not found: ...". This enum is
 * the core-side anchor the roadmap unified taxonomy builds on.
 * Modules map their local failures onto these kinds; nobody invents new
 * string prefixes.
 * <p>
 * v1 scope : CANCELLED, TIMEOUT, INPUT_INVALID (used by the new
 * run-context machinery). The remaining roadmap classes (permission
 * denied, approval-waiting, model failure, tool failure, resource
 * exhausted, protocol failure, recovery failure) land with -3
 * consumers; the enum is open for extension without breaking switch
 * statements that handle a default branch.
 */
public enum FailureKind {

    /** Input failed validation before any execution started. */
    INPUT_INVALID,

    /** Permission check denied the operation (governance boundary). */
    PERMISSION_DENIED,

    /** Execution paused waiting for an approval decision. */
    APPROVAL_WAITING,

    /** The model provider call failed (network, auth, 5xx, parse). */
    MODEL_FAILURE,

    /** Tool execution failed (business or system error inside the tool). */
    TOOL_FAILURE,

    /** A configured timeout or deadline expired. */
    TIMEOUT,

    /** The run was cancelled by a caller. Control flow, not a failure. */
    CANCELLED,

    /** Budget or resource limit exhausted (tokens, money, sandbox budget). */
    RESOURCE_EXHAUSTED,

    /** Protocol-level failure (MCP/A2A/Webhook wire errors). */
    PROTOCOL_FAILURE,

    /** Recovery/checkpoint failure (version mismatch, corrupt state). */
    RECOVERY_FAILURE
}
