package io.github.qwzhang01.agent.security;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Injection patterns for Prompt Injection defense (Stage 9 D5).
 * <p>
 * Three categories of common injection templates:
 * <ol>
 *   <li>Role spoofing - fake system/assistant messages embedded in tool output</li>
 *   <li>Instruction override - "ignore previous instructions" style attacks</li>
 *   <li>Sensitive exfiltration - URLs combined with send/upload instructions</li>
 * </ol>
 * <p>
 * v1 uses regex pattern matching. Semantic-level detection (LLM judge)
 * can be added in v2 without changing the {@link ResultSanitizer} interface.
 */
public final class InjectionPattern {

    private InjectionPattern() {
    }

    // ============ Category 1: Role Spoofing ============

    /**
     * Patterns that attempt to inject fake system/assistant messages.
     */
    public static final List<Pattern> ROLE_SPOOFING = List.of(
            // "[SYSTEM]" or "<|im_start|>system"
            Pattern.compile("\\[SYSTEM\\]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<\\|im_start\\|>(system|assistant)", Pattern.CASE_INSENSITIVE),
            // "<system>" tags
            Pattern.compile("</?system>", Pattern.CASE_INSENSITIVE),
            // "System:" prefix
            Pattern.compile("^\\s*System\\s*:", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE)
    );

    // ============ Category 2: Instruction Override ============

    /**
     * Patterns that attempt to override the model's instructions.
     */
    public static final List<Pattern> INSTRUCTION_OVERRIDE = List.of(
            // "ignore previous/all instructions"
            Pattern.compile("ignore\\s+(all\\s+)?(previous|prior)\\s+instructions", Pattern.CASE_INSENSITIVE),
            // "disregard all"
            Pattern.compile("disregard\\s+all\\s+(prior|previous|above)", Pattern.CASE_INSENSITIVE),
            // "you are now" - identity override
            Pattern.compile("you\\s+are\\s+now\\s+(a|an)\\s+", Pattern.CASE_INSENSITIVE),
            // "forget everything"
            Pattern.compile("forget\\s+(everything|all\\s+previous)", Pattern.CASE_INSENSITIVE),
            // 忽略以上/之前所有指令
            Pattern.compile("忽略(以上|之前|前面)(所有|全部)?指令")
    );

    // ============ Category 3: Sensitive Exfiltration ============

    /**
     * Patterns that attempt to exfiltrate data to external URLs.
     */
    public static final List<Pattern> SENSITIVE_EXFIL = List.of(
            // URL + send/post/upload/transfer
            Pattern.compile("https?://\\S+.*?(send|post|upload|transfer|submit)\\s+(to|it|here)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(send|post|upload|transfer|submit).{0,30}https?://", Pattern.CASE_INSENSITIVE),
            // "send [data] to [url]"
            Pattern.compile("(api[_\\s-]?key|password|token|secret).{0,30}https?://", Pattern.CASE_INSENSITIVE),
            // 发送到 URL
            Pattern.compile("(发送|上传|提交).{0,20}https?://")
    );

    /**
     * All pattern groups, for bulk checking.
     */
    public static final List<List<Pattern>> ALL_GROUPS = List.of(
            ROLE_SPOOFING, INSTRUCTION_OVERRIDE, SENSITIVE_EXFIL
    );

    /**
     * Scan text for any injection pattern match.
     *
     * @return the first matched pattern's category, or null if clean
     */
    public static String scan(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (Pattern p : ROLE_SPOOFING) {
            if (p.matcher(text).find()) return "role-spoofing";
        }
        for (Pattern p : INSTRUCTION_OVERRIDE) {
            if (p.matcher(text).find()) return "instruction-override";
        }
        for (Pattern p : SENSITIVE_EXFIL) {
            if (p.matcher(text).find()) return "sensitive-exfiltration";
        }
        return null;
    }
}
