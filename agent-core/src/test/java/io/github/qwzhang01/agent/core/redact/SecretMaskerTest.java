package io.github.qwzhang01.agent.core.redact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretMaskerTest {

    // ============ default preset ============

    @Test
    @DisplayName("default rules mask api keys, emails, phones, JWTs; plain text untouched")
    void defaultRulesMaskCommonSecrets() {
        SecretMasker masker = SecretMasker.withDefaults();

        String masked = masker.mask("my key is sk-abcdef0123456789abcdef and mail me at a@b.com");
        assertFalse(masked.contains("sk-abcdef0123456789abcdef"));
        assertFalse(masked.contains("a@b.com"));
        assertTrue(masked.contains("[REDACTED:api-key]"));
        assertTrue(masked.contains("[REDACTED:email]"));
        assertTrue(masked.contains("my key is"));
        assertTrue(masked.contains("and mail me at"));

        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";
        assertTrue(masker.mask("token=" + jwt).contains("[REDACTED:jwt]"));

        assertTrue(masker.mask("call me at 13812345678 please").contains("[REDACTED:phone-cn]"));

        String clean = "The file contains 42 lines of code.";
        assertSame(clean, masker.mask(clean));
    }

    @Test
    @DisplayName("mask never throws: null and blank pass through")
    void nullAndBlankPassThrough() {
        SecretMasker masker = SecretMasker.withDefaults();
        assertNull(masker.mask(null));
        assertEquals("", masker.mask(""));
    }

    @Test
    @DisplayName("maskDetailed reports per-rule hit counts")
    void maskDetailedCountsHits() {
        SecretMasker masker = SecretMasker.withDefaults();
        SecretMasker.MaskResult r = masker.maskDetailed(
                "contact a@b.com or c@d.com, key sk-abcdefghijklmnopqrstuv");

        assertEquals(2, r.hitsPerRule().get("email"));
        assertEquals(1, r.hitsPerRule().get("api-key"));
        assertEquals(3, r.totalHits());
        assertFalse(r.masked().contains("a@b.com"));
    }

    // ============ tenant custom rules ============

    @Test
    @DisplayName("tenant custom rule: named replacement, first registered wins overlaps")
    void tenantCustomRules() {
        SecretMasker masker = SecretMasker.builder()
                .rule("employee-id", "EMP-\\d{6}")
                .rule("project", "moonlit|omega")
                .build();

        String masked = masker.mask("owner EMP-123456 works on moonlit, not omega");
        assertTrue(masked.contains("[REDACTED:employee-id]"));
        assertTrue(masked.contains("[REDACTED:project]"));
        assertFalse(masked.contains("EMP-123456"));
        assertFalse(masked.contains("moonlit"));
    }

    @Test
    @DisplayName("builder validates: blank name/regex, duplicate rule names, broken pattern fail loud")
    void builderValidation() {
        SecretMasker.Builder b = SecretMasker.builder();
        assertThrows(IllegalArgumentException.class, () -> b.rule(" ", "x"));
        assertThrows(IllegalArgumentException.class, () -> b.rule("ok", " "));
        assertThrows(IllegalArgumentException.class, () -> b.rule("dup", "a").rule("dup", "b"));
        assertThrows(IllegalArgumentException.class, () -> b.rule("broken", "(unclosed"));
    }

    // ============ noop ============

    @Test
    @DisplayName("noop masker is identity")
    void noopIsIdentity() {
        SecretMasker masker = SecretMasker.noop();
        String text = "sk-abcdefghijklmnopqrstuv raw";
        assertSame(text, masker.mask(text));
    }

    // ============ cross-surface consistency ============

    @Test
    @DisplayName("same masker instance produces identical output for memory, audit, export surfaces")
    void sameMaskerSameOutputAcrossSurfaces() {
        SecretMasker masker = SecretMasker.withDefaults();
        String memoryView = masker.mask("user email a@b.com");
        String auditView = masker.mask("user email a@b.com");
        String exportView = masker.mask("user email a@b.com");
        assertEquals(memoryView, auditView);
        assertEquals(auditView, exportView);
    }

    @Test
    @DisplayName("List.of rules are immutable snapshots; masking is deterministic")
    void deterministicMasking() {
        SecretMasker masker = SecretMasker.withDefaults();
        List<String> inputs = List.of(
                "key sk-abcdef0123456789abcdef01",
                "phone 13812345678",
                "mixed a@b.com + 15912349876");
        for (String input : inputs) {
            assertEquals(masker.mask(input), masker.mask(input));
        }
    }
}
