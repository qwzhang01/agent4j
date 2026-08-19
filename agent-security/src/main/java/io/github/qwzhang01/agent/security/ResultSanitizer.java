package io.github.qwzhang01.agent.security;

/**
 * Sanitizes tool execution results to defend against Prompt Injection (Stage 9 D5).
 * <p>
 * Scans for known injection patterns (role spoofing, instruction override,
 * sensitive exfiltration) and applies a sanitization strategy when detected.
 * <p>
 * v1 uses pattern matching, not semantic analysis. Semantic-level detection
 * (LLM judge) can be added in v2 without changing this interface.
 */
public interface ResultSanitizer {

    /**
     * Scan the tool result and return a sanitized version if needed.
     *
     * @param result the raw tool output
     * @return sanitize result (modified=true if patterns were found)
     */
    SanitizeResult sanitize(String result);
}
