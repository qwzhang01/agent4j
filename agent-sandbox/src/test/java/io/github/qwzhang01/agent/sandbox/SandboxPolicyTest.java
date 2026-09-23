package io.github.qwzhang01.agent.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SandboxPolicy} (KP9 · tier selection).
 * <p>
 * Validates the risk → tier mapping and the Decision 21 boundary
 * (SEMI_TRUSTED + single-tenant = ClassLoader OK; multi-tenant = escalate to Process).
 */
class SandboxPolicyTest {

    private final SandboxPolicy policy = SandboxPolicy.defaultPolicy();

    @Test
    @DisplayName("TRUSTED always maps to CLASSLOADER (same trust domain)")
    void trusted_alwaysClassLoader() {
        assertEquals(SandboxTier.CLASSLOADER, policy.tierFor(SandboxRiskLevel.TRUSTED));
        assertEquals(SandboxTier.CLASSLOADER, policy.tierFor(SandboxRiskLevel.TRUSTED, true));
        assertEquals(SandboxTier.CLASSLOADER, policy.tierFor(SandboxRiskLevel.TRUSTED, false));
    }

    // SEMI_TRUSTED — Decision 21 boundary

    @Test
    @DisplayName("SEMI_TRUSTED + single-tenant maps to CLASSLOADER (Decision 21 holds)")
    void semiTrusted_singleTenant_classLoader() {
        assertEquals(SandboxTier.CLASSLOADER, policy.tierFor(SandboxRiskLevel.SEMI_TRUSTED, false));
    }

    @Test
    @DisplayName("SEMI_TRUSTED + multi-tenant maps to PROCESS (Decision 21 breaks)")
    void semiTrusted_multiTenant_process() {
        assertEquals(SandboxTier.PROCESS, policy.tierFor(SandboxRiskLevel.SEMI_TRUSTED, true));
    }

    @Test
    @DisplayName("tierFor(SEMI_TRUSTED) without tenancy arg defaults to single-tenant (ClassLoader)")
    void semiTrusted_defaultArg_classLoader() {
        assertEquals(SandboxTier.CLASSLOADER, policy.tierFor(SandboxRiskLevel.SEMI_TRUSTED));
    }

    @Test
    @DisplayName("UNTRUSTED always maps to PROCESS regardless of tenancy")
    void untrusted_alwaysProcess() {
        assertEquals(SandboxTier.PROCESS, policy.tierFor(SandboxRiskLevel.UNTRUSTED, false));
        assertEquals(SandboxTier.PROCESS, policy.tierFor(SandboxRiskLevel.UNTRUSTED, true));
    }

    @Test
    @DisplayName("ADVERSARIAL always maps to PROCESS regardless of tenancy")
    void adversarial_alwaysProcess() {
        assertEquals(SandboxTier.PROCESS, policy.tierFor(SandboxRiskLevel.ADVERSARIAL, false));
        assertEquals(SandboxTier.PROCESS, policy.tierFor(SandboxRiskLevel.ADVERSARIAL, true));
    }


    @Test
    @DisplayName("SEMI_TRUSTED + single-tenant enables optimistic escalation")
    void semiTrusted_singleTenant_optimistic() {
        assertTrue(policy.useOptimisticEscalation(SandboxRiskLevel.SEMI_TRUSTED, false));
    }

    @Test
    @DisplayName("SEMI_TRUSTED + multi-tenant disables optimistic escalation")
    void semiTrusted_multiTenant_notOptimistic() {
        assertFalse(policy.useOptimisticEscalation(SandboxRiskLevel.SEMI_TRUSTED, true));
    }

    @Test
    @DisplayName("UNTRUSTED never uses optimistic escalation")
    void untrusted_notOptimistic() {
        assertFalse(policy.useOptimisticEscalation(SandboxRiskLevel.UNTRUSTED, false));
        assertFalse(policy.useOptimisticEscalation(SandboxRiskLevel.UNTRUSTED, true));
    }

    @Test
    @DisplayName("ADVERSARIAL never uses optimistic escalation")
    void adversarial_notOptimistic() {
        assertFalse(policy.useOptimisticEscalation(SandboxRiskLevel.ADVERSARIAL, false));
    }

    @Test
    @DisplayName("TRUSTED never uses optimistic escalation (goes direct to ClassLoader)")
    void trusted_notOptimistic() {
        assertFalse(policy.useOptimisticEscalation(SandboxRiskLevel.TRUSTED, false));
    }
}
