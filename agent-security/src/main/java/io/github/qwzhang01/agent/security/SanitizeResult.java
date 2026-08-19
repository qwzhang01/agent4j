package io.github.qwzhang01.agent.security;

/**
 * Result of sanitizing a tool's output (Stage 9 D5).
 *
 * @param sanitized the sanitized text (same as input if not modified)
 * @param modified  whether any injection pattern was found and removed
 * @param reason    description of what was detected (null if not modified)
 */
public record SanitizeResult(
        String sanitized,
        boolean modified,
        String reason
) {
    /**
     * No modification needed - the result is clean.
     */
    public static SanitizeResult clean(String original) {
        return new SanitizeResult(original, false, null);
    }

    /**
     * Result was modified - injection patterns were found and removed.
     */
    public static SanitizeResult modified(String sanitized, String reason) {
        return new SanitizeResult(sanitized, true, reason);
    }
}
