package io.github.qwzhang01.agent.sandbox;

/**
 * Result of sandbox execution.
 * <p>
 * Failure orthogonality (KP9): a failure has two independent axes —
 * WHO died (the sandbox machinery vs the guest code) and WHAT the
 * caller should do about it (retry in a stronger tier vs fix the code
 * vs collect a bug report). v1 flattened both axes into the free-form
 * {@code error} string, forcing consumers to string-match
 * ("Blocked:...") — {@link FailureKind} restores the axes as data.
 * <p>
 * Derivation is total (the compact constructor derives the kind from
 * the other fields, so every historical construction site keeps
 * compiling and behaves identically); an explicit kind still wins so
 * tests and future callers can pin it.
 *
 * @param success  whether execution completed without error
 * @param stdout   captured standard output
 * @param stderr   captured standard error
 * @param exitCode process exit code (-1 for ClassLoader sandbox)
 * @param timedOut whether execution was killed due to timeout
 * @param error    error message if execution failed
 * @param kind     failure classification; null when {@code success} is true
 */
public record SandboxResult(
        boolean success,
        String stdout,
        String stderr,
        int exitCode,
        boolean timedOut,
        String error,
        FailureKind kind
) {

    /**
     * Failure taxonomy: the sandbox's death vs the code's death, and
     * the retry semantics of each. Orthogonal to {@link SandboxTier}:
     * the same kind can surface at any tier (a compilation error is
     * CODE_FAILURE whether it happened in-process or in a subprocess).
     */
    public enum FailureKind {
        /** The sandbox machinery itself failed (compile infra crash, JVM spawn failure). Sandbox bug — retrying is pointless; collect a report. */
        SANDBOX_FAILURE,

        /** The guest code tripped a policy refusal (blocked class access). Retrying in a stronger tier is the DESIGNED escalation path. */
        BLOCKED_BY_POLICY,

        /** The guest code exceeded its time budget. A retry in any tier times out the same way — the escalation loop deliberately does NOT retry these. */
        TIMEOUT,

        /** The guest code itself failed: compile error, runtime exception, non-zero exit. Fix the code, not the sandbox. */
        CODE_FAILURE
    }

    public SandboxResult {
        // Compact constructor: derive the kind when the caller didn't pin it.
        if (kind == null) {
            kind = deriveKind(success, timedOut, error);
        }
    }

    /**
     * Six-arg source-compatibility constructor (the pre-FailureKind
     * signature): the kind is derived, never null-pinned. Keeps every
     * existing construction site (ProcessSandbox, CommandRunner, tests)
     * compiling and behaving identically.
     */
    public SandboxResult(boolean success, String stdout, String stderr,
                         int exitCode, boolean timedOut, String error) {
        this(success, stdout, stderr, exitCode, timedOut, error, null);
    }

    private static SandboxResult.FailureKind deriveKind(boolean success, boolean timedOut, String error) {
        if (success) {
            return null;
        }
        if (timedOut) {
            return SandboxResult.FailureKind.TIMEOUT;
        }
        if (error != null && error.startsWith("Blocked:")) {
            return SandboxResult.FailureKind.BLOCKED_BY_POLICY;
        }
        if (error != null && error.startsWith("Sandbox error:")) {
            return SandboxResult.FailureKind.SANDBOX_FAILURE;
        }
        return SandboxResult.FailureKind.CODE_FAILURE;
    }

    public static SandboxResult success(String stdout) {
        return new SandboxResult(true, stdout, "", 0, false, null, null);
    }

    public static SandboxResult success(String stdout, String stderr) {
        return new SandboxResult(true, stdout, stderr, 0, false, null, null);
    }

    public static SandboxResult error(String error) {
        return new SandboxResult(false, "", "", -1, false, error, null);
    }

    public static SandboxResult timeout(String partialOutput) {
        notBlank(partialOutput);
        return new SandboxResult(false, partialOutput, "", -1, true, "Execution timed out", null);
    }

    public static SandboxResult blocked(String blockedClass) {
        return new SandboxResult(false, "", "", -1, false,
                "Blocked: access to " + blockedClass + " is not allowed", null);
    }

    private static void notBlank(String s) {
        if (s == null) {
            throw new IllegalArgumentException("partialOutput must not be null");
        }
    }
}
