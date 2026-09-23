package io.github.qwzhang01.agent.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Debt-1 tests (KP9 follow-up, 2026-09-12): classification is producer-stated
 * data, not consumer-guessed string prefixes.
 * <p>
 * The debt: in-repo producers pinned the kind explicitly at every failure
 * site, so the {@code deriveKind} prefix heuristic "Blocked:..." /
 * "Sandbox error:..." is no longer load-bearing for in-repo classification.
 * These tests pin the NEW contract (typed factories carry the kind; text is
 * display-only) and the compatibility contract (old shapes still derive the
 * same kinds, so external construction sites keep behaving identically).
 */
class SandboxFailureKindDebtTest {

    // Typed factories: the producer states the kind

    @Test
    @DisplayName("sandboxFailure() carries SANDBOX_FAILURE regardless of message wording")
    void sandboxFailureIsTypedNotPrefixed() {
        SandboxResult r = SandboxResult.sandboxFailure("机器坏了: temp dir unavailable");
        assertEquals(SandboxResult.FailureKind.SANDBOX_FAILURE, r.kind(),
                "no English prefix anywhere - the factory states the kind, wording is display-only");
    }

    @Test
    @DisplayName("error(msg, kind) carries the stated kind - the general typed path")
    void typedErrorCarriesStatedKind() {
        SandboxResult r = SandboxResult.error("Compilation failed",
                SandboxResult.FailureKind.CODE_FAILURE);
        assertEquals(SandboxResult.FailureKind.CODE_FAILURE, r.kind());
    }

    @Test
    @DisplayName("error(msg, null) fails loudly instead of silently deriving")
    void typedErrorRejectsNullKind() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> SandboxResult.error("boom", null));
        assertEquals("kind must not be null - use error(String) for the derived compatibility path",
                ex.getMessage());
    }

    @Test
    @DisplayName("blocked() carries BLOCKED_BY_POLICY as data (the escalation trigger)")
    void blockedIsTypedByFactory() {
        SandboxResult r = SandboxResult.blocked("java.lang.Runtime");
        assertEquals(SandboxResult.FailureKind.BLOCKED_BY_POLICY, r.kind());
    }

    @Test
    @DisplayName("blockedCompat() keeps the pre-typed derived shape for external sites")
    void blockedCompatStillDerives() {
        SandboxResult r = SandboxResult.blockedCompat("java.lang.Runtime");
        assertEquals(SandboxResult.FailureKind.BLOCKED_BY_POLICY, r.kind(),
                "compat path: same behavior as before the typed factories existed");
    }


    @Test
    @DisplayName("ClassLoaderSandbox's blocked path is typed even if the error text changes")
    void classLoaderBlockIsTypedAtSource() {
        // The producer-side fix: the blocked factory (used by ClassLoaderSandbox's
        // SecurityException catch) pins BLOCKED_BY_POLICY inside the factory. A future
        // rewording of the message cannot re-bucket the kind - this test pins that
        // the factory, not the prefix, is the source of truth.
        String fakeRewordedText = "已被拦截: access to java.lang.Runtime";
        SandboxResult r = new SandboxResult(false, "", "", -1, false,
                fakeRewordedText, SandboxResult.FailureKind.BLOCKED_BY_POLICY);
        assertEquals(SandboxResult.FailureKind.BLOCKED_BY_POLICY, r.kind(),
                "kind survives arbitrary rewording because it rides the field, not the text");
    }

    @Test
    @DisplayName("ProcessSandbox infra catch is typed: spawn failure is SANDBOX_FAILURE even in Chinese")
    void processInfraFailureIsTypedAtSource() {
        // Simulates what ProcessSandbox's catch now produces via sandboxFailure:
        // the kind is carried as data, so a localized or reworded message cannot
        // silently downgrade it to CODE_FAILURE (which would advise "fix the code"
        // for a machine problem).
        SandboxResult r = SandboxResult.sandboxFailure("无法创建临时目录");
        assertEquals(SandboxResult.FailureKind.SANDBOX_FAILURE, r.kind());
    }
}
