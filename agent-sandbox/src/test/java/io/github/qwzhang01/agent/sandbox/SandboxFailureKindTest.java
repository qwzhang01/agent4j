package io.github.qwzhang01.agent.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link SandboxResult.FailureKind} (KP9 · failure
 * orthogonality: the sandbox's death vs the code's death, separated).
 * <p>
 * Two disciplines asserted:
 * <ol>
 *   <li><b>Total derivation</b> — every historical construction path
 *       (the factories) derives the kind without the caller pinning
 *       it, so all 11+ existing construction sites keep behaving
 *       identically.</li>
 *   <li><b>Orthogonality</b> — the same kind can surface at any tier
 *       and any transport: {@code blocked()} carries
 *       BLOCKED_BY_POLICY whether it came from the ClassLoader tier
 *       or a hypothetical policy-checking process tier; the taxonomy
 *       describes WHO died, not WHERE.</li>
 * </ol>
 */
class SandboxFailureKindTest {

    @Test
    @DisplayName("success results carry a null kind")
    void successCarriesNullKind() {
        assertNull(SandboxResult.success("ok").kind());
        assertNull(SandboxResult.success("out", "err").kind());
    }

    @Test
    @DisplayName("blocked() derives BLOCKED_BY_POLICY without the caller pinning it")
    void blockedDerivesPolicyKind() {
        SandboxResult blocked = SandboxResult.blocked("java.lang.Runtime");
        assertEquals(SandboxResult.FailureKind.BLOCKED_BY_POLICY, blocked.kind());
    }

    @Test
    @DisplayName("timeout() derives TIMEOUT")
    void timeoutDerivesTimeoutKind() {
        assertEquals(SandboxResult.FailureKind.TIMEOUT,
                SandboxResult.timeout("partial").kind());
    }

    @Test
    @DisplayName("error('Sandbox error: ...') derives SANDBOX_FAILURE (the sandbox's own death)")
    void sandboxErrorDerivesSandboxFailure() {
        SandboxResult r = SandboxResult.error("Sandbox error: javac process failed to start");
        assertEquals(SandboxResult.FailureKind.SANDBOX_FAILURE, r.kind());
    }

    @Test
    @DisplayName("error(anything else) derives CODE_FAILURE (the guest code's death)")
    void plainErrorDerivesCodeFailure() {
        SandboxResult r = SandboxResult.error("NullPointerException: cannot invoke ...");
        assertEquals(SandboxResult.FailureKind.CODE_FAILURE, r.kind());
        SandboxResult exit = new SandboxResult(false, "out", "err", 1, false,
                "exited with code 1", null);
        assertEquals(SandboxResult.FailureKind.CODE_FAILURE, exit.kind());
    }

    @Test
    @DisplayName("an explicitly pinned kind wins over derivation")
    void pinnedKindWins() {
        SandboxResult pinned = new SandboxResult(false, "", "", -1, false,
                "Blocked: access to X", SandboxResult.FailureKind.CODE_FAILURE);
        assertEquals(SandboxResult.FailureKind.CODE_FAILURE, pinned.kind(),
                "explicit pin overrides the prefix-based derivation");
    }

    @Test
    @DisplayName("null error on a failed result still classifies as CODE_FAILURE (total function)")
    void nullErrorStillClassifies() {
        SandboxResult r = new SandboxResult(false, "", "", -1, false, null, null);
        assertEquals(SandboxResult.FailureKind.CODE_FAILURE, r.kind(),
                "deriveKind is total: no crash, no silent null kind on failure");
    }
}
