package io.github.qwzhang01.agent.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Default result sanitizer with configurable strategy (Stage 9 D5).
 * <p>
 * Scans tool output for {@link InjectionPattern} matches and applies one of
 * three sanitization strategies when detected:
 * <ul>
 *   <li>{@link Strategy#SANITIZE} - replace matched segments with [REDACTED]</li>
 *   <li>{@link Strategy#TRUNCATE} - cut off from the first match onwards</li>
 *   <li>{@link Strategy#BLOCK} - replace entire output with a blocked message</li>
 * </ul>
 */
public class DefaultResultSanitizer implements ResultSanitizer {

    private static final Logger log = LoggerFactory.getLogger(DefaultResultSanitizer.class);

    /**
     * Sanitization strategy when an injection pattern is detected.
     */
    public enum Strategy {
        /** Replace matched segments with [REDACTED], keep the rest. */
        SANITIZE,
        /** Cut off from the first match onwards. */
        TRUNCATE,
        /** Replace the entire output with a blocked message. */
        BLOCK
    }

    private final Strategy strategy;
    private final List<Pattern> roleSpoofing;
    private final List<Pattern> instructionOverride;
    private final List<Pattern> sensitiveExfil;

    public DefaultResultSanitizer() {
        this(Strategy.SANITIZE);
    }

    public DefaultResultSanitizer(Strategy strategy) {
        this(strategy, InjectionPattern.ROLE_SPOOFING, InjectionPattern.INSTRUCTION_OVERRIDE,
                InjectionPattern.SENSITIVE_EXFIL);
    }

    public DefaultResultSanitizer(Strategy strategy,
                                   List<Pattern> roleSpoofing,
                                   List<Pattern> instructionOverride,
                                   List<Pattern> sensitiveExfil) {
        this.strategy = strategy;
        this.roleSpoofing = roleSpoofing;
        this.instructionOverride = instructionOverride;
        this.sensitiveExfil = sensitiveExfil;
    }

    @Override
    public SanitizeResult sanitize(String result) {
        if (result == null || result.isBlank()) {
            return SanitizeResult.clean(result);
        }

        String category = scan(result);
        if (category == null) {
            return SanitizeResult.clean(result);
        }

        log.warn("[Security] Injection detected ({}), applying {} strategy", category, strategy);

        return switch (strategy) {
            case SANITIZE -> SanitizeResult.modified(sanitizeSegments(result), category);
            case TRUNCATE -> SanitizeResult.modified(truncateAtMatch(result), category);
            case BLOCK -> SanitizeResult.modified(
                    "[BLOCKED: potential prompt injection detected (" + category + ")]",
                    category);
        };
    }

    private String scan(String text) {
        for (Pattern p : roleSpoofing) {
            if (p.matcher(text).find()) return "role-spoofing";
        }
        for (Pattern p : instructionOverride) {
            if (p.matcher(text).find()) return "instruction-override";
        }
        for (Pattern p : sensitiveExfil) {
            if (p.matcher(text).find()) return "sensitive-exfiltration";
        }
        return null;
    }

    private String sanitizeSegments(String text) {
        String result = text;
        for (Pattern p : allPatterns()) {
            result = p.matcher(result).replaceAll("[REDACTED]");
        }
        if (!result.equals(text)) {
            return "[WARNING: sanitized] " + result;
        }
        return result;
    }

    private String truncateAtMatch(String text) {
        int earliest = Integer.MAX_VALUE;
        for (Pattern p : allPatterns()) {
            var m = p.matcher(text);
            if (m.find() && m.start() < earliest) {
                earliest = m.start();
            }
        }
        if (earliest == Integer.MAX_VALUE) return text;
        return text.substring(0, earliest) + "\n[TRUNCATED: potential injection removed]";
    }

    private List<Pattern> allPatterns() {
        return java.util.stream.Stream.of(roleSpoofing, instructionOverride, sensitiveExfil)
                .flatMap(List::stream)
                .toList();
    }
}
