package io.github.qwzhang01.agent.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SandboxEscalator} (KP9 · tier selection + auto-escalation).
 * <p>
 * Key scenarios:
 * <ol>
 *   <li>SEMI_TRUSTED + safe code → ClassLoader fast path, no escalation.</li>
 *   <li>SEMI_TRUSTED + dangerous code → ClassLoader blocks → escalates to Process.</li>
 *   <li>UNTRUSTED → skips ClassLoader, routes directly to Process.</li>
 *   <li>ADVERSARIAL → skips ClassLoader, routes directly to Process.</li>
 *   <li>SEMI_TRUSTED + multi-tenant → routes directly to Process (Decision 21 boundary).</li>
 * </ol>
 */
class SandboxEscalatorTest {

    // SEMI_TRUSTED + single-tenant: optimistic path

    @Test
    @DisplayName("SEMI_TRUSTED + safe code: ClassLoader fast path succeeds")
    void semiTrusted_safeCode_classLoaderSucceeds() {
        SandboxEscalator escalator = SandboxEscalator.forRisk(SandboxRiskLevel.SEMI_TRUSTED);

        String code = """
                public class Generated {
                    public static String run() {
                        return "hello from sandbox: " + (6 * 7);
                    }
                }
                """;

        SandboxResult result = escalator.execute("Generated", code);
        assertTrue(result.success(), "safe LLM code must succeed via ClassLoader fast path");
        assertTrue(result.stdout().contains("42"), "arithmetic result must be correct");
    }

    @Test
    @DisplayName("SEMI_TRUSTED + dangerous code: ClassLoader blocks, escalates to Process")
    void semiTrusted_dangerousCode_escalatesToProcess() {
        SandboxEscalator escalator = SandboxEscalator.forRisk(SandboxRiskLevel.SEMI_TRUSTED);

        // Code that tries to access java.io.File — blocked by ClassLoader.
        // After escalation to ProcessSandbox, the code compiles and runs in the subprocess.
        // ProcessSandbox does NOT block at the ClassLoader level, so File access succeeds
        // in the subprocess (the isolation is process-boundary, not class-block).
        // We use ClassLoaderSandbox's `run` signature here because the escalator
        // tries ClassLoader first; the escalated Process sandbox uses `main`.
        // To test the block+escalate path, we use File access code with `run` signature.
        String code = """
                public class Generated {
                    public static String run() throws Exception {
                        java.io.File f = new java.io.File("/tmp/test_escalator");
                        return "file: " + f.getAbsolutePath();
                    }
                }
                """;

        // ClassLoader will BLOCK java.io.File access → escalates to ProcessSandbox.
        // ProcessSandbox compiles the code and runs it. However, since ProcessSandbox
        // expects a `main(String[] args)` entry point, not `run`, it will fail at runtime.
        // What matters for this test: result should NOT be a ClassLoader-blocked result
        // (i.e., the escalator did something beyond just returning the block).
        SandboxResult result = escalator.execute("Generated", code);
        // The escalator should have tried ClassLoader (which blocks), then escalated to Process.
        // ProcessSandbox result may succeed or fail depending on `run` vs `main` but it
        // is NOT a ClassLoader-BLOCKED result — escalation happened.
        assertFalse(SandboxEscalator.isBlocked(result),
                "after escalation to ProcessSandbox, result must not be a ClassLoader-blocked result");
    }

    @Test
    @DisplayName("SEMI_TRUSTED: escalator uses optimistic escalation in single-tenant mode")
    void semiTrusted_singleTenant_usesOptimisticEscalation() {
        SandboxEscalator escalator = SandboxEscalator.forRisk(SandboxRiskLevel.SEMI_TRUSTED, false);
        assertTrue(escalator.getPolicy().useOptimisticEscalation(escalator.getRiskLevel(), false),
                "SEMI_TRUSTED single-tenant must use optimistic escalation");
    }

    // UNTRUSTED: direct to Process

    @Test
    @DisplayName("UNTRUSTED + safe code: routes directly to ProcessSandbox")
    void untrusted_safeCode_routesToProcess() {
        SandboxEscalator escalator = SandboxEscalator.forRisk(SandboxRiskLevel.UNTRUSTED);

        String code = """
                public class Generated {
                    public static void main(String[] args) {
                        System.out.println("process: " + (3 * 3));
                    }
                }
                """;

        SandboxResult result = escalator.execute("Generated", code);
        assertTrue(result.success(), "safe code succeeds in ProcessSandbox");
        assertTrue(result.stdout().contains("9"), "arithmetic result correct");
    }

    @Test
    @DisplayName("UNTRUSTED: does not use optimistic escalation (skips ClassLoader)")
    void untrusted_noOptimisticEscalation() {
        SandboxEscalator escalator = SandboxEscalator.forRisk(SandboxRiskLevel.UNTRUSTED, false);
        assertFalse(escalator.getPolicy().useOptimisticEscalation(escalator.getRiskLevel(), false),
                "UNTRUSTED must not use optimistic escalation");
    }

    // ADVERSARIAL: direct to Process

    @Test
    @DisplayName("ADVERSARIAL: routes directly to ProcessSandbox")
    void adversarial_routesToProcess() {
        SandboxEscalator escalator = SandboxEscalator.forRisk(SandboxRiskLevel.ADVERSARIAL);
        assertEquals(SandboxTier.PROCESS,
                escalator.getPolicy().tierFor(SandboxRiskLevel.ADVERSARIAL),
                "ADVERSARIAL must map to PROCESS tier");
    }

    // SEMI_TRUSTED + multi-tenant: Decision 21 boundary

    @Test
    @DisplayName("SEMI_TRUSTED + multi-tenant: Decision 21 broken, routes to Process")
    void semiTrusted_multiTenant_decisionBoundary() {
        SandboxEscalator escalator = SandboxEscalator.forRisk(SandboxRiskLevel.SEMI_TRUSTED, true);
        assertTrue(escalator.isMultiTenant(), "escalator must know it is in multi-tenant mode");
        assertEquals(SandboxTier.PROCESS,
                escalator.getPolicy().tierFor(SandboxRiskLevel.SEMI_TRUSTED, true),
                "multi-tenant SEMI_TRUSTED must map to PROCESS");
    }

    @Test
    @DisplayName("forRisk() creates escalator with the specified risk level")
    void forRisk_setsRiskLevel() {
        SandboxEscalator escalator = SandboxEscalator.forRisk(SandboxRiskLevel.UNTRUSTED);
        assertEquals(SandboxRiskLevel.UNTRUSTED, escalator.getRiskLevel());
        assertFalse(escalator.isMultiTenant());
    }

    @Test
    @DisplayName("forRisk(risk, true) creates a multi-tenant escalator")
    void forRisk_withMultiTenant_setsFlag() {
        SandboxEscalator escalator = SandboxEscalator.forRisk(SandboxRiskLevel.SEMI_TRUSTED, true);
        assertTrue(escalator.isMultiTenant());
    }
}
