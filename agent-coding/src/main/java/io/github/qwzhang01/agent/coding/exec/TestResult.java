package io.github.qwzhang01.agent.coding.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.sandbox.SandboxResult;

import java.util.ArrayList;
import java.util.List;

/**
 * The machine verdict of a test run (Stage 17 M17.3, blueprint D3: "the test is the
 * judge - the only objective referee among the three profile scenarios").
 * <p>
 * {@code passed} = exit code 0 AND not timed out (a timeout is an honest failure,
 * not a crash). The {@code outputExcerpt} is the readable evidence: lines matching
 * {@code "Tests run:"} (surefire/JUnit convention) first, plus the tail of the output
 * on failure - the model reads this observation and enters the fix loop naturally
 * (blueprint D4: rhythm in the model).
 * <p>
 * This is also the natural reward signal for Stage 14: {@code passed} maps directly
 * to a rule reward (+1.0) - the coding-trajectory-as-RL-data bridge (v2, blueprint D3).
 *
 * @param passed        exit code 0 and no timeout
 * @param exitCode      process exit code (-1 if it never finished)
 * @param timedOut      killed by the runner's timeout
 * @param durationMs    wall-clock duration of the run
 * @param outputExcerpt readable evidence (test-run summary lines + failure tail)
 */
public record TestResult(boolean passed, int exitCode, boolean timedOut,
                         long durationMs, String outputExcerpt) {

    private static final int MAX_SUMMARY_LINES = 10;
    private static final int FAILURE_TAIL_LINES = 15;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Derive the verdict from a raw {@link SandboxResult}. */
    public static TestResult from(SandboxResult result, long durationMs) {
        boolean passed = result.success() && !result.timedOut();
        String excerpt = buildExcerpt(result, passed);
        return new TestResult(passed, result.exitCode(), result.timedOut(),
                durationMs, excerpt);
    }

    /** JSON projection consumed by both the tool layer and the session's fix-loop wrapper. */
    public String toJson() {
        ObjectNode json = MAPPER.createObjectNode();
        json.put("passed", passed);
        json.put("exit_code", exitCode);
        json.put("timed_out", timedOut);
        json.put("duration_ms", durationMs);
        json.put("output_excerpt", outputExcerpt);
        return json.toString();
    }

    private static String buildExcerpt(SandboxResult result, boolean passed) {
        List<String> lines = new ArrayList<>();
        String full = (result.stdout() == null ? "" : result.stdout())
                + (result.stderr() == null || result.stderr().isBlank() ? "" : "\n" + result.stderr());

        if (result.timedOut()) {
            lines.add("[TIMED OUT] the test command was killed by the timeout");
        }

        // surefire/JUnit summary lines first: "Tests run: 3, Failures: 1, ..."
        List<String> summaryLines = full.lines()
                .filter(l -> l.contains("Tests run:"))
                .limit(MAX_SUMMARY_LINES)
                .toList();
        lines.addAll(summaryLines);

        if (!passed && !result.timedOut() && !full.isBlank()) {
            // tail of the output: the failure detail lives at the end
            List<String> all = full.lines().toList();
            lines.add("--- output tail ---");
            int from = Math.max(0, all.size() - FAILURE_TAIL_LINES);
            for (int i = from; i < all.size(); i++) {
                lines.add(all.get(i));
            }
        }

        if (lines.isEmpty()) {
            // no "Tests run:" pattern - show the tail of the output whatever the verdict,
            // so a passing run is not reported as "(no output)"
            List<String> all = full.lines().toList();
            int from = Math.max(0, all.size() - FAILURE_TAIL_LINES);
            for (int i = from; i < all.size(); i++) {
                lines.add(all.get(i));
            }
        }
        if (lines.isEmpty()) {
            return "(no output)";
        }
        return String.join("\n", lines);
    }
}
