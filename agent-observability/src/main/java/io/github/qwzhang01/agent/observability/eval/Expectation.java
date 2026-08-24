package io.github.qwzhang01.agent.observability.eval;

import java.util.Objects;

/**
 * Deterministic assertion over one evaluated run (Stage 18 D7) - the v1
 * judgment vocabulary of the regression gate.
 * <p>
 * v1 is DELIBERATELY deterministic-only (four kinds): the gate's lifeline is
 * reproducibility - same dataset, same mock, same report. An LLM-as-judge
 * brings its own nondeterminism AND its own token cost, so the judge slot is
 * a v2 extension of this interface, not a flag on these four. A gate that
 * flakes blocks releases for no reason; a gate that always passes blocks
 * nothing.
 * <p>
 * What is being judged is an {@link Outcome} - not just the answer text:
 * {@link MaxTokens} asserts the run stayed within a token budget,
 * {@link ToolCallCount} asserts the tool usage shape. Both are honest
 * failure-shape assertions for the classic runaway modes (a fix-loop that
 * called the tool 15 times, a bloated run that burned 50k tokens).
 * <p>
 * Unit tests assert code logic; the eval set asserts model/prompt/tool
 * BEHAVIOR - "does this prompt still answer these cases acceptably" is
 * something only the eval set can guard.
 */
public sealed interface Expectation {

    /**
     * Test this expectation against one evaluated outcome.
     *
     * @param outcome what the subject produced (never null)
     * @return true when satisfied
     */
    boolean test(Outcome outcome);

    /** Human-readable one-liner for reports ("contains '道歉'"). */
    String describe();

    /**
     * What one evaluated run produced - the input every expectation judges.
     *
     * @param finalText     the subject's final answer text ("" when none)
     * @param totalTokens   total tokens consumed by the run (0 when unknown)
     * @param toolCallCount tool calls issued during the run
     */
    record Outcome(String finalText, long totalTokens, int toolCallCount) {
        public Outcome {
            finalText = finalText == null ? "" : finalText;
        }
    }

    /** Final text equals the expected string exactly. */
    record ExactMatch(String expected) implements Expectation {
        public ExactMatch {
            Objects.requireNonNull(expected, "expected");
        }

        @Override
        public boolean test(Outcome outcome) {
            return expected.equals(outcome.finalText());
        }

        @Override
        public String describe() {
            return "exact match \"" + expected + "\"";
        }
    }

    /** Final text contains the fragment. */
    record Contains(String fragment) implements Expectation {
        public Contains {
            Objects.requireNonNull(fragment, "fragment");
        }

        @Override
        public boolean test(Outcome outcome) {
            return outcome.finalText().contains(fragment);
        }

        @Override
        public String describe() {
            return "contains \"" + fragment + "\"";
        }
    }

    /** Run stayed within the token ceiling (inclusive - landing on it passes). */
    record MaxTokens(long max) implements Expectation {
        public MaxTokens {
            if (max < 0) {
                throw new IllegalArgumentException("max must not be negative: " + max);
            }
        }

        @Override
        public boolean test(Outcome outcome) {
            return outcome.totalTokens() <= max;
        }

        @Override
        public String describe() {
            return "at most " + max + " tokens";
        }
    }

    /** Run issued exactly the expected number of tool calls. */
    record ToolCallCount(int expected) implements Expectation {
        public ToolCallCount {
            if (expected < 0) {
                throw new IllegalArgumentException("expected must not be negative: " + expected);
            }
        }

        @Override
        public boolean test(Outcome outcome) {
            return outcome.toolCallCount() == expected;
        }

        @Override
        public String describe() {
            return "exactly " + expected + " tool calls";
        }
    }
}
