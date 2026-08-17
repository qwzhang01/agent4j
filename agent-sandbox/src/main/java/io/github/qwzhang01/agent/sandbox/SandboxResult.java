package io.github.qwzhang01.agent.sandbox;

/**
 * Result of sandbox execution.
 *
 * @param success  whether execution completed without error
 * @param stdout   captured standard output
 * @param stderr   captured standard error
 * @param exitCode process exit code (-1 for ClassLoader sandbox)
 * @param timedOut whether execution was killed due to timeout
 * @param error    error message if execution failed
 */
public record SandboxResult(
        boolean success,
        String stdout,
        String stderr,
        int exitCode,
        boolean timedOut,
        String error
) {
    public static SandboxResult success(String stdout) {
        return new SandboxResult(true, stdout, "", 0, false, null);
    }

    public static SandboxResult success(String stdout, String stderr) {
        return new SandboxResult(true, stdout, stderr, 0, false, null);
    }

    public static SandboxResult error(String error) {
        return new SandboxResult(false, "", "", -1, false, error);
    }

    public static SandboxResult timeout(String partialOutput) {
        return new SandboxResult(false, partialOutput, "", -1, true, "Execution timed out");
    }

    public static SandboxResult blocked(String blockedClass) {
        return new SandboxResult(false, "", "", -1, false,
                "Blocked: access to " + blockedClass + " is not allowed");
    }
}
