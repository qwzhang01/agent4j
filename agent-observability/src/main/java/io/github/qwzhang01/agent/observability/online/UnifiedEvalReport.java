package io.github.qwzhang01.agent.observability.online;

import io.github.qwzhang01.agent.observability.eval.EvalReport;
import io.github.qwzhang01.agent.observability.version.RunRecord;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The unified evaluation report (Stage 7.3 last bar): golden set, online
 * metrics and red-team results in ONE document - the "one report" the
 * roadmap demands, assembled from the three evidence sources that until
 * now lived in three places.
 * <p>
 * Composition, not aggregation: each input stays its own typed section
 * (the golden {@link EvalReport} with its verdict, the {@link OnlineMetrics}
 * window, the optional red-team summary); the report adds no verdict of
 * its own on top - three verdicts in one document is two too many. The
 * READER (release operator) weighs the sections; the REPORT carries them.
 * <p>
 * Red-team summary is a simple pass/runs pair: the red-team harness lives
 * in examples and its full case detail is its own artifact; this report
 * carries the headline the operator needs at release time.
 * <p>
 * Version attribution: the optional {@link RunRecord} combination names
 * which prompt/model/tool triple this report describes - "version
 * automatically associates with evaluation results" (7.3).
 */
public record UnifiedEvalReport(
        String title,
        List<RunRecord> servedCombinations,
        EvalReport goldenSet,
        OnlineMetrics onlineWindow,
        RedTeamSummary redTeam) {

    public UnifiedEvalReport {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be null or blank");
        }
        servedCombinations = servedCombinations == null ? List.of() : List.copyOf(servedCombinations);
        Objects.requireNonNull(goldenSet, "goldenSet");
        Objects.requireNonNull(onlineWindow, "onlineWindow");
        Objects.requireNonNull(redTeam, "redTeam (use RedTeamSummary.notRun() when absent)");
    }

    /** Red-team headline: how many attacks were repelled of how many launched. */
    public record RedTeamSummary(int attacksLaunched, int attacksRepelled, String notes) {

        public RedTeamSummary {
            Objects.requireNonNull(notes, "notes (empty string is fine)");
            if (attacksLaunched < 0 || attacksRepelled < 0 || attacksRepelled > attacksLaunched) {
                throw new IllegalArgumentException(
                        "attacksRepelled must be within [0, attacksLaunched]: "
                                + attacksRepelled + "/" + attacksLaunched);
            }
        }

        /** The honest "we did not run the red team this cycle" row. */
        public static RedTeamSummary notRun() {
            return new RedTeamSummary(0, 0, "not run this cycle");
        }

        public boolean run() {
            return attacksLaunched > 0;
        }

        public double repelRate() {
            return attacksLaunched == 0 ? 0.0 : (double) attacksRepelled / attacksLaunched;
        }
    }

    /** The primary combination this report describes, if exactly one was recorded. */
    public Optional<RunRecord> primaryCombination() {
        return servedCombinations.size() == 1
                ? Optional.of(servedCombinations.get(0))
                : Optional.empty();
    }

    /** One-line headline for the ops dashboard. */
    public String headline() {
        return title + " | golden " + goldenSet.verdict() + " ("
                + String.format("%.0f%%", goldenSet.passRate() * 100) + ")"
                + " | online completion " + String.format("%.1f%%", onlineWindow.taskCompletionRate() * 100)
                + " | red team " + (redTeam.run()
                        ? String.format("%.0f%% repelled", redTeam.repelRate() * 100)
                        : "not run");
    }
}
