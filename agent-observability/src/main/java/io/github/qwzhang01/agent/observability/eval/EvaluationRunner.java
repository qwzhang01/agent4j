package io.github.qwzhang01.agent.observability.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Batch replay over a dataset (Stage 18 D7): for each case, run the subject,
 * judge deterministically, aggregate into the {@link EvalReport} the release
 * gate consumes.
 * <p>
 * The subject under evaluation is INJECTED - the runner never builds agents:
 * a Mock-backed lambda makes the gate testable (reproducible, free); a real
 * agent wrapped with the M18.1 decorators supplies tokens/tool counts in the
 * {@link Expectation.Outcome}. Assembling that bridge is the caller's one
 * line, keeping this class free of metrics/cost wiring (M18.4 is an
 * independent milestone line).
 * <p>
 * Subject exceptions are case FAILURES, not eval aborts: a case that crashed
 * the subject is a failed case with the failure in its detail - the aggregate
 * stays honest and the remaining cases still get their verdict (an eval that
 * dies on case 1 of 8 tells the operator nothing about the other seven).
 * <p>
 * The report carries no timestamps and no ambient state - same dataset +
 * same deterministic subject = {@code equals}-identical reports. That
 * reproducibility is the gate's lifeline and is under test.
 */
public final class EvaluationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRunner.class);

    /** How much of the actual answer text goes into a failure detail (operators replay for full text). */
    private static final int DETAIL_TEXT_LIMIT = 80;

    /**
     * The system under evaluation: replay one prompt, report what happened.
     * Implemented by Mock lambdas in tests and by real agent wiring in
     * production evaluation.
     */
    @FunctionalInterface
    public interface Subject {

        /**
         * @param prompt the case's prompt
         * @return what the run produced (final text, tokens, tool calls);
         *         never null
         * @throws RuntimeException when the subject itself fails - the case
         *                          is marked failed, the eval continues
         */
        Expectation.Outcome run(String prompt);
    }

    /**
     * Evaluate the whole dataset.
     *
     * @param dataset     cases to replay, in dataset order (report preserves it)
     * @param subject     the system under evaluation
     * @param baseline    previous report to gate against, null when none yet
     *                    (first run ESTABLISHES the baseline - verdict will
     *                    honestly say {@code BASELINE_ABSENT})
     * @param minPassRate gate threshold in (0.0, 1.0]
     * @throws IllegalArgumentException on an empty dataset - an empty eval is
     *                                  not an eval
     */
    public EvalReport evaluate(EvalDataset dataset, Subject subject,
                               EvalReport baseline, double minPassRate) {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(subject, "subject");
        if (dataset.size() == 0) {
            throw new IllegalArgumentException("dataset is empty - an empty eval is not an eval");
        }
        List<EvalReport.CaseResult> results = new ArrayList<>();
        for (EvalCase evalCase : dataset.cases()) {
            results.add(evaluateCase(evalCase, subject));
        }
        return EvalReport.of(results, baseline, minPassRate);
    }

    private EvalReport.CaseResult evaluateCase(EvalCase evalCase, Subject subject) {
        try {
            Expectation.Outcome outcome = Objects.requireNonNull(subject.run(evalCase.prompt()),
                    "subject returned null outcome (case " + evalCase.caseId() + ")");
            boolean passed = evalCase.expectation().test(outcome);
            String detail = passed
                    ? "passed: " + evalCase.expectation().describe()
                    : "FAILED: expected " + evalCase.expectation().describe()
                            + ", got " + actualSummary(outcome);
            return new EvalReport.CaseResult(evalCase.caseId(), passed, detail);
        } catch (RuntimeException e) {
            log.warn("case {} crashed the subject: {}", evalCase.caseId(), e.toString());
            return new EvalReport.CaseResult(evalCase.caseId(), false,
                    "FAILED: subject threw " + e.getClass().getSimpleName()
                            + ": " + e.getMessage() + " (expected " + evalCase.expectation().describe() + ")");
        }
    }

    private static String actualSummary(Expectation.Outcome outcome) {
        String text = outcome.finalText();
        String shown = text.length() <= DETAIL_TEXT_LIMIT ? text : text.substring(0, DETAIL_TEXT_LIMIT) + "...";
        return "text=\"" + shown + "\", tokens=" + outcome.totalTokens()
                + ", toolCalls=" + outcome.toolCallCount();
    }
}
