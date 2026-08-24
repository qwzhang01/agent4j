package io.github.qwzhang01.agent.coding.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The argv whitelist - gate 2 of the three-gate command defense (Stage 17 M17.3,
 * blueprint D2: "the first line of defense is that there is no shell").
 * <p>
 * Matching is <b>prefix-based on argv</b>, not string matching on a command line:
 * rule {@code [mvn, test]} allows {@code mvn test -q} and denies {@code mvn clean}.
 * Because there is no shell involved, only {@code argv[0]} is matched against the
 * rule head - injection syntax inside later arguments is inert by construction
 * (it is just an argument, nobody interprets it).
 * <p>
 * Fail-closed: an empty argv, a blank element, or anything not covered by a rule
 * is denied. A rule longer than the argv does not match (the granted command is
 * the full rule, a shorter argv is a different command).
 */
public final class CommandWhitelist {

    /** Verdict of {@link #check(List)}: a reason is present iff denied. */
    public record CheckResult(boolean allowed, String reason) {
        /** Factory for a granted command (the component accessor is named {@code allowed()}). */
        public static CheckResult granted() {
            return new CheckResult(true, null);
        }

        public static CheckResult denied(String reason) {
            return new CheckResult(false, reason);
        }
    }

    private final List<List<String>> rules;

    private CommandWhitelist(Builder builder) {
        this.rules = List.copyOf(builder.rules);
        for (List<String> rule : this.rules) {
            if (rule.isEmpty()) {
                throw new IllegalArgumentException("whitelist rules must not be empty");
            }
            for (String element : rule) {
                if (element == null || element.isBlank()) {
                    throw new IllegalArgumentException(
                            "whitelist rule elements must not be null or blank: " + rule);
                }
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Check an argv against the rules.
     * <p>
     * A rule matches when it is a prefix of the argv (rule length &lt;= argv length and
     * element-wise equal).
     */
    public CheckResult check(List<String> argv) {
        if (argv == null || argv.isEmpty()) {
            return CheckResult.denied("command must not be null or empty");
        }
        for (String element : argv) {
            if (element == null || element.isBlank()) {
                return CheckResult.denied("command arguments must not be null or blank");
            }
        }
        for (List<String> rule : rules) {
            if (matches(rule, argv)) {
                return CheckResult.granted();
            }
        }
        return CheckResult.denied("command not in whitelist: " + argv.get(0)
                + (argv.size() > 1 ? " " + argv.get(1) : ""));
    }

    private static boolean matches(List<String> rule, List<String> argv) {
        if (rule.size() > argv.size()) {
            return false;
        }
        for (int i = 0; i < rule.size(); i++) {
            if (!rule.get(i).equals(argv.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** Human-readable summary of the granted commands (for tool feedback and audit). */
    public String summary() {
        List<String> joined = new ArrayList<>();
        for (List<String> rule : rules) {
            joined.add(String.join(" ", rule));
        }
        return String.join(" | ", joined);
    }

    /** Number of rules (for inspection / audit). */
    public int size() {
        return rules.size();
    }

    public static final class Builder {
        private final List<List<String>> rules = new ArrayList<>();

        /** Grant a command prefix, e.g. {@code rule("mvn", "test")} or {@code rule("java")}. */
        public Builder rule(String... argvPrefix) {
            Objects.requireNonNull(argvPrefix, "argvPrefix must not be null");
            rules.add(List.of(argvPrefix));
            return this;
        }

        public CommandWhitelist build() {
            return new CommandWhitelist(this);
        }
    }
}
