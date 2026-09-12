package io.github.qwzhang01.agent.sandbox;

import java.util.List;
import java.util.Objects;

/**
 * Honest enforcement report (KP9): translates a {@link SandboxResult}
 * plus the {@link SandboxTier} that produced it into an explicit
 * "what this tier guarantees / does NOT guarantee" declaration.
 * <p>
 * This is the downstream translator the spectrum table promised: v1
 * had the knowledge (the tier javadoc's escape-surface column) but
 * only in prose — a consumer wanting "did my CLASSLOADER run really
 * isolate anything?" had to already know the spectrum. {@code report()}
 * turns the same knowledge into data, so logs and callers see the
 * honest boundary of every execution WITHOUT reading javadoc.
 * <p>
 * Design discipline (same as HealthPipeline / KP4 routing): a PURE
 * DOWNSTREAM translator. No production class imports it, nothing in
 * the sandbox chain calls it automatically — the caller (a host, a
 * log line, an audit consumer) decides when honesty is wanted. Pull
 * it out and nothing changes.
 * <p>
 * v1 knowledge base (mirrors {@link SandboxTier} javadoc exactly —
 * when the tier docs change, this table must change with them):
 * <pre>
 *   CLASSLOADER guarantees:   in-process execution, output capture, timeout
 *   CLASSLOADER does NOT:    security boundary (reflection/Unsafe/JNI escape)
 *   PROCESS guarantees:      process address space isolation, forcible kill
 *   PROCESS does NOT:        network isolation, FS whitelist (same OS user)
 *   DOCKER/MICROVM/WASM:     documented placeholders, no v1 implementation
 * </pre>
 */
public final class SandboxReport {

    /**
     * What one execution actually delivered, in the tier's own honest
     * terms.
     *
     * @param tier        the tier that executed the code
     * @param outcome     the raw result (success / failure kind)
     * @param guarantees  what this tier structurally guarantees for THIS
     *                    outcome
     * @param notGuaranteed what this tier structurally does NOT guarantee
     *                    (the escape surface that remains open)
     * @param escalationNote non-null when the result reflects a tier
     *                    boundary event (a block that was escalated, a
     *                    budget-exhausted refusal)
     */
    public record Entry(
            SandboxTier tier,
            SandboxResult result,
            List<String> guarantees,
            List<String> notGuaranteed,
            String escalationNote
    ) {
        public Entry {
            Objects.requireNonNull(tier, "tier");
            Objects.requireNonNull(result, "result");
            guarantees = List.copyOf(guarantees);
            notGuaranteed = List.copyOf(notGuaranteed);
        }
    }

    private SandboxReport() {
        // static translator, no instances
    }

    /**
     * Translate one execution into its honest guarantee report.
     * <p>
     * The guarantee sets depend on BOTH the tier and the outcome: a
     * TIMEOUT at any tier still guarantees the kill semantics (that is
     * what timeout means); a BLOCKED result guarantees only the policy
     * refusal itself (the code never ran — nothing else was exercised).
     */
    public static Entry report(SandboxTier tier, SandboxResult result) {
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(result, "result");

        List<String> guarantees;
        List<String> notGuaranteed;
        String escalationNote = null;

        switch (tier) {
            case CLASSLOADER -> {
                guarantees = List.of("in-process execution (no child JVM)",
                        "captured stdout/stderr",
                        "cooperative interrupt on timeout");
                if (isBlocked(result)) {
                    escalationNote = "blocked by load-time policy; code never ran - "
                            + "the refusal itself is the guarantee, no isolation was exercised";
                    notGuaranteed = List.of("security boundary (reflection / Unsafe / JNI escape paths)");
                } else if (result.success()) {
                    notGuaranteed = List.of(
                            "security boundary (reflection / Unsafe / JNI escape paths)",
                            "memory cap (no -Xmx applies in-process)",
                            "filesystem / network isolation (runs as host user)");
                } else if (result.timedOut()) {
                    notGuaranteed = List.of(
                            "security boundary (reflection / Unsafe / JNI escape paths)",
                            "hard kill semantics (interrupt is cooperative - an "
                                    + "infinite loop ignoring interrupts outlives the call)");
                } else {
                    notGuaranteed = List.of("security boundary (reflection / Unsafe / JNI escape paths)");
                }
            }
            case PROCESS -> {
                guarantees = List.of(
                        "process address space isolation (child cannot reach parent JVM heap)",
                        "forcible kill on timeout (destroyForcibly)",
                        "child PID distinct from parent (auditable process boundary)",
                        "optional memory cap via -Xmx (when memoryLimitBytes > 0)");
                if (result.success()) {
                    notGuaranteed = List.of(
                            "network isolation (child can reach the network)",
                            "filesystem whitelist (child runs as the same OS user)",
                            "kernel syscall filtering (no seccomp)");
                } else {
                    notGuaranteed = List.of(
                            "network isolation (child can reach the network)",
                            "filesystem whitelist (child runs as the same OS user)",
                            "kernel syscall filtering (no seccomp)");
                }
            }
            case DOCKER, MICROVMM, WASM -> {
                guarantees = List.of();
                notGuaranteed = List.of(
                        "everything - tier is a documented placeholder, no v1 implementation");
                escalationNote = "placeholder tier: no implementation exists, "
                        + "report cannot claim guarantees";
            }
            default -> throw new IllegalStateException("Unknown tier: " + tier);
        }

        return new Entry(tier, result, guarantees, notGuaranteed, escalationNote);
    }

    private static boolean isBlocked(SandboxResult result) {
        return result.kind() == SandboxResult.FailureKind.BLOCKED_BY_POLICY
                || (!result.success() && !result.timedOut()
                        && result.error() != null && result.error().startsWith("Blocked:"));
    }
}
