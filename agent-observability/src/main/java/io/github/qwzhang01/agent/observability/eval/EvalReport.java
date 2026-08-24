package io.github.qwzhang01.agent.observability.eval;

import java.util.List;
import java.util.Objects;

/**
 * Gate verdict over one evaluation run (Stage 18 D7) - the report IS the
 * release gate's input.
 * <p>
 * Verdict semantics (three states, honestly labeled):
 * <ul>
 *   <li>{@link #FAIL} - passRate dropped below the gate threshold
 *       ({@code passRate < minPassRate}, strict - landing exactly on it
 *       passes, the same on-the-line convention as BudgetBook), OR regressed
 *       against the baseline ({@code passRate < baseline.passRate}) - fixing
 *       one case while breaking another is the textbook regression this gate
 *       exists to catch. The threshold floor applies on EVERY run including
 *       the first: a gate that waves 0% through because "no baseline yet"
 *       would be decorative</li>
 *   <li>{@link #BASELINE_ABSENT} - above the floor, but no baseline to
 *       compare against yet. The first run of a dataset ESTABLISHES the
 *       baseline; pretending to compare against nothing would be a fabricated
 *       gate, so the verdict honestly says so (the operator decides whether
 *       first-run promotion is acceptable policy)</li>
 *   <li>{@link #PASS} - at or above threshold AND at or above baseline</li>
 * </ul>
 * Reproducibility is structural: the record carries no timestamps and no
 * ambient state - same dataset + same deterministic subject = equal reports
 * (field-by-field {@code equals}). That equality is the gate's lifeline and
 * is under test.
 *
 * @param passRate fraction of cases that passed, in [0.0, 1.0]
 * @param results  per-case outcomes, dataset order preserved
 * @param baseline the report this one is gated against, null on first run
 * @param verdict  gate verdict (never null)
 */
public record EvalReport(double passRate, List<CaseResult> results,
                         EvalReport baseline, Verdict verdict) {

    public EvalReport {
        results = List.copyOf(results);
        if (results.isEmpty()) {
            throw new IllegalArgumentException("results must not be empty - an empty eval is not an eval");
        }
        Objects.requireNonNull(verdict, "verdict");
    }

    /**
     * Compute passRate and verdict from per-case results.
     *
     * @param baseline     previous report to gate against, null when none yet
     * @param minPassRate  gate threshold in (0.0, 1.0]; strictly below fails
     */
    public static EvalReport of(List<CaseResult> results, EvalReport baseline, double minPassRate) {
        if (minPassRate <= 0.0 || minPassRate > 1.0) {
            throw new IllegalArgumentException("minPassRate must be within (0.0, 1.0]: " + minPassRate);
        }
        long passed = results.stream().filter(CaseResult::passed).count();
        double passRate = (double) passed / results.size();
        Verdict verdict = verdictOf(passRate, baseline, minPassRate);
        return new EvalReport(passRate, results, baseline, verdict);
    }

    static Verdict verdictOf(double passRate, EvalReport baseline, double minPassRate) {
        if (passRate < minPassRate) {
            return Verdict.FAIL;  // absolute floor: enforced on every run, baseline or not
        }
        if (baseline == null) {
            return Verdict.BASELINE_ABSENT;  // above floor, nothing to compare against - establish, don't fake
        }
        if (passRate < baseline.passRate()) {
            return Verdict.FAIL;  // relative regression: fixing one case while breaking another
        }
        return Verdict.PASS;
    }

    /** How many cases passed. */
    public long passedCount() {
        return results.stream().filter(CaseResult::passed).count();
    }

    /** The failed cases only - the gate's fix list (F5: failure detail per case). */
    public List<CaseResult> failureDetails() {
        return results.stream().filter(r -> !r.passed()).toList();
    }

    public enum Verdict {PASS, FAIL, BASELINE_ABSENT}

    /**
     * One case's outcome within a report.
     *
     * @param caseId which case
     * @param passed whether its expectation held
     * @param detail human-readable: the expectation plus, on failure, what was
     *               actually observed (truncated for the report; operators
     *               replay for full text)
     */
    public record CaseResult(String caseId, boolean passed, String detail) {
        public CaseResult {
            if (caseId == null || caseId.isBlank()) {
                throw new IllegalArgumentException("caseId must not be null or blank");
            }
            Objects.requireNonNull(detail, "detail");
        }
    }
}
