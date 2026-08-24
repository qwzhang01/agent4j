package io.github.qwzhang01.agent.coding.exec;

import io.github.qwzhang01.agent.sandbox.SandboxResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 17 M17.3: the verdict record - passed semantics (exit code AND no timeout)
 * and the evidence excerpt (surefire "Tests run:" lines first, failure tail after).
 */
class TestResultTest {

    @Test
    @DisplayName("passed = exit code 0 AND not timed out; timeout is an honest failure")
    void passedSemantics() {
        assertTrue(TestResult.from(SandboxResult.success("ok"), 5).passed());

        TestResult failed = TestResult.from(
                new SandboxResult(false, "out", "err", 1, false, "exited with code 1"), 5);
        assertFalse(failed.passed());
        assertEquals(1, failed.exitCode());

        TestResult timedOut = TestResult.from(SandboxResult.timeout("partial"), 5000);
        assertFalse(timedOut.passed());
        assertTrue(timedOut.timedOut());
    }

    @Test
    @DisplayName("excerpt prefers surefire 'Tests run:' summary lines")
    void excerptPrefersSummaryLines() {
        SandboxResult result = new SandboxResult(false,
                "compiling...\nTests run: 3, Failures: 1, Errors: 0, Skipped: 0\nsome detail\n",
                "", 1, false, "exited with code 1");

        TestResult verdict = TestResult.from(result, 10);

        assertTrue(verdict.outputExcerpt().contains("Tests run: 3, Failures: 1"), verdict.outputExcerpt());
        assertTrue(verdict.outputExcerpt().contains("--- output tail ---"), verdict.outputExcerpt());
        assertTrue(verdict.outputExcerpt().contains("some detail"), "failure tail included");
    }

    @Test
    @DisplayName("a passing run keeps the excerpt short (summary only, no tail)")
    void passingExcerpt() {
        SandboxResult result = SandboxResult.success(
                "Tests run: 5, Failures: 0, Errors: 0, Skipped: 0\nBUILD SUCCESS\n");

        TestResult verdict = TestResult.from(result, 120);

        assertTrue(verdict.outputExcerpt().contains("Tests run: 5, Failures: 0"));
        assertFalse(verdict.outputExcerpt().contains("--- output tail ---"),
                "no tail on success: " + verdict.outputExcerpt());
    }

    @Test
    @DisplayName("a timed-out run is marked explicitly in the excerpt")
    void timeoutMarked() {
        TestResult verdict = TestResult.from(SandboxResult.timeout("partial output"), 30000);

        assertTrue(verdict.outputExcerpt().contains("[TIMED OUT]"), verdict.outputExcerpt());
        assertFalse(verdict.passed());
    }

    @Test
    @DisplayName("no output at all is honestly reported, not a blank string")
    void noOutput() {
        TestResult verdict = TestResult.from(SandboxResult.success(""), 5);
        assertEquals("(no output)", verdict.outputExcerpt());
    }
}
