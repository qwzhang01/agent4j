package io.github.qwzhang01.agent.core.redact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactionPolicyTest {

    @Test
    @DisplayName("rawOnly: legacy posture - raw kept, no masker, apply is passthrough")
    void rawOnlyIsLegacyPassthrough() {
        RedactionPolicy policy = RedactionPolicy.rawOnly();
        assertTrue(policy.keepsRaw());
        assertFalse(policy.keepsMasked());
        assertFalse(policy.keepsHash());
        assertFalse(policy.keepsSummary());
        assertNull(policy.masker());

        String secret = "key sk-abcdef0123456789abcdef";
        assertSame(secret, policy.apply(secret));
    }

    @Test
    @DisplayName("maskedOnly: export posture - raw forbidden, masked + hash + summary kept")
    void maskedOnlyForbidsRaw() {
        RedactionPolicy policy = RedactionPolicy.maskedOnly(SecretMasker.withDefaults());
        assertFalse(policy.keepsRaw());
        assertTrue(policy.keepsMasked());
        assertTrue(policy.keepsHash());
        assertTrue(policy.keepsSummary());

        String masked = policy.apply("contact a@b.com");
        assertFalse(masked.contains("a@b.com"));
        assertTrue(masked.contains("[REDACTED:email]"));
    }

    @Test
    @DisplayName("rawPlusMasked: memory/audit posture - raw kept for owner, masked for consumers")
    void rawPlusMaskedKeepsBoth() {
        RedactionPolicy policy = RedactionPolicy.rawPlusMasked(SecretMasker.withDefaults());
        assertTrue(policy.keepsRaw());
        assertTrue(policy.keepsMasked());
        assertTrue(policy.apply("phone 13812345678").contains("[REDACTED:phone-cn]"));
    }

    @Test
    @DisplayName("hashOnly: log posture - no raw, no masked, summary + hash only")
    void hashOnlyKeepsNothingReadable() {
        RedactionPolicy policy = RedactionPolicy.hashOnly();
        assertFalse(policy.keepsRaw());
        assertFalse(policy.keepsMasked());
        assertTrue(policy.keepsHash());
        assertTrue(policy.keepsSummary());
        String text = "anything at all";
        assertSame(text, policy.apply(text));
    }

    @Test
    @DisplayName("factory contracts: maskedOnly/rawPlusMasked reject null masker")
    void factoriesRejectNullMasker() {
        NullPointerException e1 = assertThrowsQuietly(() -> RedactionPolicy.maskedOnly(null));
        NullPointerException e2 = assertThrowsQuietly(() -> RedactionPolicy.rawPlusMasked(null));
        assertNotNull(e1);
        assertNotNull(e2);
    }

    private static NullPointerException assertThrowsQuietly(Runnable r) {
        try {
            r.run();
        } catch (NullPointerException e) {
            return e;
        }
        return null;
    }
}
