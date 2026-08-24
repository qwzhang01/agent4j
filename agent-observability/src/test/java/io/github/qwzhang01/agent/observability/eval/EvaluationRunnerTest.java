package io.github.qwzhang01.agent.observability.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvaluationRunnerTest {

    private final EvaluationRunner runner = new EvaluationRunner();

    /** Scripted subject: prompt -> outcome (deterministic, the gate's lifeline). */
    private static EvaluationRunner.Subject subject(Map<String, Expectation.Outcome> script) {
        return prompt -> script.get(prompt);
    }

    private static EvalDataset datasetOf(String... prompts) {
        EvalDataset dataset = EvalDataset.empty();
        for (String prompt : prompts) {
            dataset.add(EvalCase.of("case-" + prompt, prompt, new Expectation.Contains("好")));
        }
        return dataset;
    }

    private static final Expectation.Outcome GOOD = new Expectation.Outcome("回答得好", 100, 1);
    private static final Expectation.Outcome BAD = new Expectation.Outcome("回答崩了", 100, 1);

    // ============ aggregation + verdicts ============

    @Test
    @DisplayName("all pass: first run BASELINE_ABSENT (establishes), rerun against baseline PASS")
    void allPass() {
        EvalDataset dataset = datasetOf("p1", "p2");
        Map<String, Expectation.Outcome> script = Map.of("p1", GOOD, "p2", GOOD);

        EvalReport first = runner.evaluate(dataset, subject(script), null, 1.0);
        assertEquals(EvalReport.Verdict.BASELINE_ABSENT, first.verdict(),
                "first run is honestly labeled - no baseline to compare against");
        assertEquals(1.0, first.passRate());

        EvalReport second = runner.evaluate(dataset, subject(script), first, 1.0);
        assertEquals(EvalReport.Verdict.PASS, second.verdict());
        assertEquals(2, second.passedCount());
        assertEquals(List.of("case-p1", "case-p2"),
                second.results().stream().map(EvalReport.CaseResult::caseId).toList());
        assertTrue(second.results().get(0).detail().contains("contains \"好\""));
    }

    @Test
    @DisplayName("one fail against a green baseline: FAIL, failureDetails() is the fix list, detail shows expected vs actual")
    void oneFail() {
        EvalReport baseline = runner.evaluate(
                datasetOf("p1", "p2"),
                subject(Map.of("p1", GOOD, "p2", GOOD)),
                null, 0.5);

        EvalReport report = runner.evaluate(
                datasetOf("p1", "p2"),
                subject(Map.of("p1", GOOD, "p2", BAD)),
                baseline, 0.5);

        assertEquals(EvalReport.Verdict.FAIL, report.verdict());
        assertEquals(0.5, report.passRate());
        assertEquals(1, report.failureDetails().size());
        EvalReport.CaseResult failed = report.failureDetails().get(0);
        assertEquals("case-p2", failed.caseId());
        assertTrue(failed.detail().contains("contains \"好\""), failed.detail());
        assertTrue(failed.detail().contains("回答崩了"), "actual text surfaces: " + failed.detail());
    }

    @Test
    @DisplayName("threshold floor has no baseline exemption: a FIRST run below threshold is FAIL, not BASELINE_ABSENT")
    void floorEnforcedWithoutBaseline() {
        EvalReport report = runner.evaluate(
                datasetOf("p1", "p2"),
                subject(Map.of("p1", GOOD, "p2", BAD)),
                null, 0.9);

        assertEquals(0.5, report.passRate());
        assertEquals(EvalReport.Verdict.FAIL, report.verdict(),
                "0.5 < 0.9 fails regardless of baseline absence - a gate that waves 0% through would be decorative");
    }

    @Test
    @DisplayName("long actual text is truncated in the detail (operators replay for full text)")
    void longTextTruncated() {
        String longText = "x".repeat(500);
        EvalReport report = runner.evaluate(
                datasetOf("p1"),
                subject(Map.of("p1", new Expectation.Outcome(longText, 0, 0))),
                null, 1.0);

        String detail = report.failureDetails().get(0).detail();
        assertTrue(detail.length() < 250, "detail stays readable: " + detail.length());
        assertTrue(detail.contains("...\", tokens=0"), "truncated text ends with ellipsis inside the summary: " + detail);
    }

    @Test
    @DisplayName("no baseline: BASELINE_ABSENT even at 100% - first run ESTABLISHES the baseline, never fakes a comparison")
    void baselineAbsent() {
        EvalReport report = runner.evaluate(
                datasetOf("p1"), subject(Map.of("p1", GOOD)), null, 1.0);

        assertEquals(EvalReport.Verdict.BASELINE_ABSENT, report.verdict());
        assertNull(report.baseline());
    }

    @Test
    @DisplayName("threshold boundary: passRate == minPassRate passes (strict <, landing on the line allowed)")
    void thresholdBoundary() {
        EvalReport baseline = runner.evaluate(
                datasetOf("p1", "p2", "p3", "p4"),
                subject(Map.of("p1", GOOD, "p2", GOOD, "p3", GOOD, "p4", GOOD)),
                null, 1.0);

        EvalReport report = runner.evaluate(
                datasetOf("p1", "p2", "p3", "p4"),
                subject(Map.of("p1", GOOD, "p2", GOOD, "p3", GOOD, "p4", BAD)),
                baseline, 0.75);

        assertEquals(0.75, report.passRate());
        assertEquals(EvalReport.Verdict.FAIL, report.verdict(),
                "0.75 == threshold but regressed vs baseline 1.0 - regression blocks release");
    }

    @Test
    @DisplayName("regression against baseline fails even ABOVE the threshold")
    void regressionFailsAboveThreshold() {
        EvalReport baseline = runner.evaluate(
                datasetOf("p1", "p2"), subject(Map.of("p1", GOOD, "p2", GOOD)), null, 1.0);

        EvalReport report = runner.evaluate(
                datasetOf("p1", "p2"), subject(Map.of("p1", GOOD, "p2", BAD)),
                baseline, 0.5);

        assertEquals(0.5, report.passRate(), "exactly at threshold");
        assertEquals(EvalReport.Verdict.FAIL, report.verdict(),
                "passRate 0.5 >= threshold 0.5 but < baseline 1.0 - fixing one case while breaking another is the textbook regression");
    }

    @Test
    @DisplayName("baseline carried in the report: the chain supports gate-then-gate-then-gate")
    void baselineChainCarried() {
        EvalReport first = runner.evaluate(datasetOf("p1"), subject(Map.of("p1", GOOD)), null, 1.0);
        EvalReport second = runner.evaluate(datasetOf("p1"), subject(Map.of("p1", GOOD)), first, 1.0);

        assertEquals(EvalReport.Verdict.PASS, second.verdict());
        assertEquals(first, second.baseline());
    }

    // ============ subject failure semantics ============

    @Test
    @DisplayName("subject crash: the case FAILS with the error in its detail, the eval continues")
    void subjectCrashIsCaseFailure() {
        EvalReport report = runner.evaluate(
                datasetOf("p1", "p2"),
                prompt -> "p1".equals(prompt)
                        ? throwBoom()
                        : GOOD,
                null, 0.9);

        assertEquals(EvalReport.Verdict.FAIL, report.verdict());
        assertEquals(1, report.failureDetails().size());
        EvalReport.CaseResult crashed = report.failureDetails().get(0);
        assertEquals("case-p1", crashed.caseId());
        assertTrue(crashed.detail().contains("IllegalStateException"), crashed.detail());
        assertTrue(crashed.detail().contains("provider down"), crashed.detail());
        assertEquals(1, report.passedCount(), "the other seven - here one - still got their verdict");
    }

    private static Expectation.Outcome throwBoom() {
        throw new IllegalStateException("provider down");
    }

    @Test
    @DisplayName("subject returning null outcome: failed case, not an NPE through the runner")
    void nullOutcomeHandled() {
        EvalReport report = runner.evaluate(
                datasetOf("p1"), prompt -> null, null, 1.0);

        assertFalse(report.results().get(0).passed());
        assertTrue(report.results().get(0).detail().contains("NullPointerException"));
    }

    // ============ reproducibility (the gate's lifeline) ============

    @Test
    @DisplayName("reproducible: same dataset + same deterministic subject = equals-identical reports")
    void reproducible() {
        Map<String, Expectation.Outcome> script = Map.of("p1", GOOD, "p2", BAD);

        EvalReport first = runner.evaluate(datasetOf("p1", "p2"), subject(script), null, 0.5);
        EvalReport second = runner.evaluate(datasetOf("p1", "p2"), subject(script), null, 0.5);

        assertEquals(first, second, "record equality field by field - gate output must be a function of its inputs");
    }

    // ============ guards ============

    @Test
    @DisplayName("guards: empty dataset, minPassRate bounds, empty results")
    void guards() {
        assertThrows(IllegalArgumentException.class,
                () -> runner.evaluate(EvalDataset.empty(), subject(Map.of()), null, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> runner.evaluate(datasetOf("p1"), subject(Map.of("p1", GOOD)), null, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> runner.evaluate(datasetOf("p1"), subject(Map.of("p1", GOOD)), null, 1.5));
        assertThrows(IllegalArgumentException.class,
                () -> EvalReport.of(List.of(), null, 1.0));
    }
}
