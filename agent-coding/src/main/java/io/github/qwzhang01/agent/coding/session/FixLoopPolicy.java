package io.github.qwzhang01.agent.coding.session;

/**
 * The fix-loop budget (Stage 17 M17.4, blueprint D4: "the boundary lives in the engine,
 * the rhythm in the model").
 * <p>
 * Counts <b>failed test runs</b> only - healthy exploration (reading files, staging,
 * re-reading) costs nothing; only "tested and failed, now trying to fix" consumes
 * budget. When the budget is exhausted, {@code run_tests} returns a {@code [LIMIT]}
 * text telling the model to stop fixing and report honestly.
 * <p>
 * Why not just reuse {@code maxSteps} (Stage 2): maxSteps counts every ReAct step - it
 * cannot tell "converging healthily" (read 5 files, stage 2, test once) from "bleeding
 * in a loop" (fix-test-fail x 10). The fix budget is a domain-semantic gate: it counts
 * exactly the failure-retry rounds.
 *
 * @param maxFixIterations maximum number of failed test runs before the budget is
 *                         exhausted; must be at least 1 (the initial test run must
 *                         always be possible - a session that cannot even run tests
 *                         once has no referee at all)
 */
public record FixLoopPolicy(int maxFixIterations) {

    public static final FixLoopPolicy DEFAULT = new FixLoopPolicy(3);

    public FixLoopPolicy {
        if (maxFixIterations < 1) {
            throw new IllegalArgumentException(
                    "maxFixIterations must be at least 1 (the initial test run must be possible): "
                            + maxFixIterations);
        }
    }
}
