package io.github.qwzhang01.agent.core.redact;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex-based secret / PII masker .
 * <p>
 * One masking engine shared by every governed surface - memory reads
 * ({@code MemoryGovernance}), trajectory export ({@code TrajectoryCodec}),
 * and tool-execution audit events ({@code GovernedToolExecutor}) - so a
 * tenant rule set produces the same redacted text everywhere it is applied.
 * This is deliberately NOT the injection-defense {@code ResultSanitizer}
 * (agent-security): that family guards prompt injection; this one guards
 * data leakage. Different threats, different modules, same composition style.
 * <p>
 * Rules are ordered (first registered wins for overlapping spans) and each
 * hit is replaced with {@code [REDACTED:<rule>]} so auditors can see WHICH
 * rule fired without seeing the payload. Rule sets are tenant-customizable:
 * hosts build one masker per tenant from {@link #builder} - the builder IS
 * the customization surface, no registry magic.
 * <p>
 * Failure posture: masking never throws on input text; a null / blank input
 * passes through unchanged. A broken regex fails at builder time, not at
 * mask time.
 */
public final class SecretMasker {

    /** Replacement prefix embedded in every masked span. */
    static final String PLACEHOLDER_PREFIX = "[REDACTED:";

    private final List<Rule> rules;

    private SecretMasker(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * Mask every rule hit in {@code text}. Null / blank passes through
     * unchanged. Returns the same reference when nothing matched.
     */
    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String current = text;
        for (Rule rule : rules) {
            current = rule.apply(current);
        }
        return current;
    }

    /**
     * Mask and report per-rule hit counts (for audit records: "3 emails,
     * 1 api key were masked from this memory".
     */
    public MaskResult maskDetailed(String text) {
        if (text == null || text.isEmpty()) {
            return new MaskResult(text, Map.of(), 0);
        }
        Map<String, Integer> hits = new LinkedHashMap<>();
        String current = text;
        for (Rule rule : rules) {
            Matcher m = rule.pattern().matcher(current);
            int count = 0;
            while (m.find()) {
                count++;
            }
            if (count > 0) {
                hits.put(rule.name(), count);
                current = rule.apply(current);
            }
        }
        return new MaskResult(current, Map.copyOf(hits),
                hits.values().stream().mapToInt(Integer::intValue).sum());
    }

    /** Masker with the default secret + PII preset rules. */
    public static SecretMasker withDefaults() {
        return builder().withDefaultRules().build();
    }

    /** Masker with NO rules - {@link #mask} is a passthrough. */
    public static SecretMasker noop() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Masked text plus per-rule hit counts. {@code totalHits} is the sum
     * over all rules; the masked text is what callers store / return.
     */
    public record MaskResult(String masked, Map<String, Integer> hitsPerRule, int totalHits) {
    }

    record Rule(String name, Pattern pattern) {
        String apply(String text) {
            return pattern.matcher(text).replaceAll(Matcher.quoteReplacement(
                    PLACEHOLDER_PREFIX + name + "]"));
        }
    }

    public static final class Builder {
        private final Map<String, Rule> rules = new LinkedHashMap<>();

        /**
         * Default preset: api keys (OpenAI sk-, AWS AKIA), JWTs, bearer
         * tokens, emails, CN mobile numbers, credit-card-shaped digit runs.
         * Broad on purpose - masking is cheap, leakage is not.
         */
        public Builder withDefaultRules() {
            rule("api-key", "sk-[A-Za-z0-9_-]{16,}");
            rule("aws-key", "AKIA[0-9A-Z]{16}");
            rule("jwt", "eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}");
            rule("bearer-token", "(?i)bearer\\s+[A-Za-z0-9._-]{16,}");
            rule("email", "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
            rule("phone-cn", "(?<!\\d)1[3-9]\\d{9}(?!\\d)");
            rule("card-number", "(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)");
            return this;
        }

        /**
         * Tenant custom rule: anything matching {@code regex} (as a regex)
         * is replaced with {@code [REDACTED:<name>]}.
         *
         * @throws IllegalArgumentException blank name/regex or invalid pattern
         */
        public Builder rule(String name, String regex) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("rule name must not be blank");
            }
            if (regex == null || regex.isBlank()) {
                throw new IllegalArgumentException("rule regex must not be blank: " + name);
            }
            if (rules.containsKey(name)) {
                throw new IllegalArgumentException("duplicate rule name: " + name);
            }
            rules.put(name, new Rule(name, Pattern.compile(regex)));
            return this;
        }

        public SecretMasker build() {
            return new SecretMasker(new ArrayList<>(rules.values()));
        }
    }
}
