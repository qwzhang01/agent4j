package io.github.qwzhang01.agent.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 4.2: anchor {@link TierLimits} - the executable per-tier limit table.
 * <p>
 * These tests pin the deny-by-default contract: every tier denies network,
 * every tier below DOCKER denies process spawn, ADVERSARIAL requires approval,
 * and the spec-default ceilings (timeout/memory/output) stay aligned with
 * {@link SandboxSpec}. If someone loosens a default, these tests fail before
 * red-team does.
 */
class TierLimitsTest {

    @Test
    @DisplayName("PROCESS@UNTRUSTED: deny-by-default - network denied, workspace-only FS, no process spawn")
    void processUntrustedIsDenyByDefault() {
        TierLimits.Limits limits = TierLimits.limitsFor(SandboxTier.PROCESS, SandboxRiskLevel.UNTRUSTED);

        assertTrue(limits.networkDenied(), "network must be denied");
        assertEquals(SandboxSpec.FileAccessPolicy.WORKSPACE_ONLY, limits.fileAccess());
        assertTrue(limits.processSpawnDenied(), "process spawn must be denied below DOCKER");
        assertFalse(limits.requiresApproval(), "UNTRUSTED is high-risk but not ADVERSARIAL");
        assertEquals(TierLimits.DEFAULT_TIMEOUT, limits.timeout());
        assertEquals(TierLimits.DEFAULT_MEMORY_BYTES, limits.memoryLimitBytes());
        assertEquals(TierLimits.DEFAULT_OUTPUT_BYTES, limits.outputLimitBytes());
    }

    @Test
    @DisplayName("STRUCTURAL_TIERS: DOCKER/MICROVMM flip processSpawnDenied (spawn allowed INSIDE container)")
    void structuralTiersFlipProcessSpawn() {
        assertEquals(2, TierLimits.STRUCTURAL_TIERS.size());
        assertEquals(SandboxTier.DOCKER, TierLimits.STRUCTURAL_TIERS.get(0));
        assertEquals(SandboxTier.MICROVMM, TierLimits.STRUCTURAL_TIERS.get(1));

        TierLimits.Limits docker = TierLimits.limitsFor(SandboxTier.DOCKER, SandboxRiskLevel.ADVERSARIAL);
        assertFalse(docker.processSpawnDenied(), "spawn inside the container is allowed at DOCKER");
        assertTrue(docker.cpuLimitDescription().contains("cgroup"),
                "DOCKER CPU is enforced at the container boundary");
        assertTrue(docker.cpuLimitDescription().contains("container boundary"));

        TierLimits.Limits process = TierLimits.limitsFor(SandboxTier.PROCESS, SandboxRiskLevel.UNTRUSTED);
        assertTrue(process.cpuLimitDescription().contains("no CPU cap"),
                "below DOCKER the spec floor is timeout + memory only");
        assertTrue(process.cpuLimitDescription().contains("timeout + memory"));
    }

    @Test
    @DisplayName("ADVERSARIAL at any tier requires approval; lower risks do not")
    void adversarialRequiresApproval() {
        for (SandboxTier tier : SandboxTier.values()) {
            assertTrue(TierLimits.limitsFor(tier, SandboxRiskLevel.ADVERSARIAL).requiresApproval(),
                    "tier " + tier + " at ADVERSARIAL must require approval");
        }
        assertFalse(TierLimits.limitsFor(SandboxTier.PROCESS, SandboxRiskLevel.UNTRUSTED).requiresApproval());
        assertFalse(TierLimits.limitsFor(SandboxTier.CLASSLOADER, SandboxRiskLevel.SEMI_TRUSTED).requiresApproval());
        assertFalse(TierLimits.limitsFor(SandboxTier.CLASSLOADER, SandboxRiskLevel.TRUSTED).requiresApproval());
    }

    @Test
    @DisplayName("networkDenied is true at EVERY tier (guard below DOCKER, namespace at DOCKER+)")
    void networkDeniedAtEveryTier() {
        for (SandboxTier tier : SandboxTier.values()) {
            assertTrue(TierLimits.limitsFor(tier, SandboxRiskLevel.ADVERSARIAL).networkDenied(),
                    "tier " + tier + " must deny network");
        }
    }

    @Test
    @DisplayName("forRisk: default policy composition - SEMI_TRUSTED single-tenant -> CLASSLOADER, multi-tenant -> PROCESS")
    void forRiskComposesDefaultPolicy() {
        assertEquals(SandboxTier.CLASSLOADER, TierLimits.forRisk(SandboxRiskLevel.SEMI_TRUSTED, false).tier());
        assertEquals(SandboxTier.PROCESS, TierLimits.forRisk(SandboxRiskLevel.SEMI_TRUSTED, true).tier());
        assertEquals(SandboxTier.PROCESS, TierLimits.forRisk(SandboxRiskLevel.UNTRUSTED, false).tier());
        assertFalse(TierLimits.forRisk(SandboxRiskLevel.TRUSTED, false).requiresApproval());
    }

    @Test
    @DisplayName("fullTable: all five tiers present with limits consistent with limitsFor")
    void fullTableCoversAllTiers() {
        Map<SandboxTier, TierLimits.Limits> table = TierLimits.fullTable();

        assertEquals(SandboxTier.values().length, table.size());
        for (Map.Entry<SandboxTier, TierLimits.Limits> entry : table.entrySet()) {
            TierLimits.Limits expected = TierLimits.limitsFor(entry.getKey(), entry.getValue().riskLevel());
            assertEquals(expected, entry.getValue(), "tier " + entry.getKey() + " limits must match limitsFor");
        }
        assertEquals(SandboxRiskLevel.ADVERSARIAL, table.get(SandboxTier.DOCKER).riskLevel());
    }

    @Test
    @DisplayName("spec defaults stay aligned: SandboxSpec builder defaults equal TierLimits constants")
    void specDefaultsAlignedWithTierLimits() {
        SandboxSpec spec = SandboxSpec.builder().build();

        assertEquals(TierLimits.DEFAULT_OUTPUT_BYTES, spec.getOutputLimitBytes());
        assertTrue(spec.isNetworkBlocked());
        assertEquals(SandboxSpec.FileAccessPolicy.WORKSPACE_ONLY, spec.getFileAccess());
    }
}
