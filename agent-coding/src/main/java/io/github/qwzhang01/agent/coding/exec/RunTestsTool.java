package io.github.qwzhang01.agent.coding.exec;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.core.tool.Tool;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code run_tests} - the fixed-referee tool (Stage 17 M17.3, blueprint D3:
 * "the referee cannot be chosen by the refereed").
 * <p>
 * The test command is <b>injected at assembly time</b> and takes no arguments: the
 * model cannot pick its own judge ({@code mvn test -DskipTests} would 'pass' anything).
 * The fixed command still goes through the whitelist - the whitelist is the single
 * source of truth for what may run in this workspace, and the assembly must grant the
 * test command explicitly (blueprint T0 grants {@code [mvn, test]} and uses it).
 * <p>
 * Two entry points: {@link #execute(JsonNode)} is the Tool contract (JSON text for the
 * model); {@link #run(JsonNode)} returns the structured {@link TestResult} verdict for
 * the session's fix-loop wiring (M17.4) - the verdict consumer no longer needs a
 * callback pipe, it simply calls {@code run()} and reads the verdict. The M17.3
 * {@code onTestFailure} listener was superseded by this (honest evolution note).
 */
public final class RunTestsTool implements Tool {

    public static final String NAME = "run_tests";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {},
              "description": "Runs the project's test command. It is fixed at assembly time and cannot be changed - just call it with no arguments."
            }
            """;

    private final List<String> testCommand;
    private final CommandWhitelist whitelist;
    private final CommandRunner runner;

    /**
     * @param testCommand fixed test command, e.g. {@code [mvn, test]} - the referee
     * @param whitelist   the command whitelist (the test command must be granted in it)
     * @param runner      the no-shell executor
     */
    public RunTestsTool(List<String> testCommand, CommandWhitelist whitelist, CommandRunner runner) {
        this.testCommand = List.copyOf(Objects.requireNonNull(testCommand, "testCommand must not be null"));
        if (this.testCommand.isEmpty()) {
            throw new IllegalArgumentException("testCommand must not be empty");
        }
        this.whitelist = Objects.requireNonNull(whitelist, "whitelist must not be null");
        this.runner = Objects.requireNonNull(runner, "runner must not be null");
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Run the project's test suite. The test command is fixed ("
                + String.join(" ", testCommand) + ") and cannot be modified - the referee "
                + "is not chosen by the refereed. Read the output excerpt on failure to "
                + "understand what broke.";
    }

    @Override
    public String getParametersSchema() {
        return SCHEMA;
    }

    @Override
    public String execute(JsonNode arguments) {
        // arguments are deliberately ignored: the referee cannot be chosen by the refereed
        Optional<String> rejection = whitelistRejection();
        if (rejection.isPresent()) {
            return rejection.get();
        }
        return run(arguments).toJson();
    }

    /**
     * Whitelist check for the fixed test command: empty = granted,
     * present = the {@code [REJECTED]} text to return.
     */
    public Optional<String> whitelistRejection() {
        CommandWhitelist.CheckResult check = whitelist.check(testCommand);
        if (check.allowed()) {
            return Optional.empty();
        }
        return Optional.of("[REJECTED] the configured test command is not whitelisted ("
                + check.reason() + "). Allowed command prefixes: " + whitelist.summary());
    }

    /**
     * Execute the fixed test command and return the structured verdict.
     * Assumes the whitelist has been granted (see {@link #whitelistRejection()}).
     */
    public TestResult run(JsonNode arguments) {
        long start = System.nanoTime();
        var result = runner.run(testCommand);
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        return TestResult.from(result, durationMs);
    }
}
