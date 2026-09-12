package io.github.qwzhang01.agent.observability.health;

import io.github.qwzhang01.agent.core.agent.AgentEvent;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.observability.metrics.RunMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * E7 experiment: one simulated night of production traffic through the
 * health pipeline - the KP7 five-indicator coverage matrix materialized in
 * one report, with a prompt regression in the second half to exercise the
 * drift tripwire end to end.
 * <p>
 * NOT a unit test of correctness (that lives in {@code HealthPipelineTest}) -
 * this is the experiment harness. All numbers are hand-computable and
 * asserted exactly; the printed two-window table is the data source for
 * {@code experiment-kp7-online-evaluation.md}.
 * <p>
 * Simulation model (deterministic, no randomness):
 * <ul>
 *   <li>phase 1 "the quiet half": 16 clean DONE runs, durations 100..1600 ms,
 *       200-char answers, two governance denials, one model error</li>
 *   <li>phase 2 "the loud half": 12 DONE + 2 ERROR + 2 MAX_STEPS runs,
 *       durations 1700..3200 ms, 380-char answers (a prompt regression made
 *       the agent twice as verbose), one more denial, two more model errors</li>
 *   <li>uniform cost 500_000 microUSD per run (0.5 USD/task, 32 tasks = 16 USD)</li>
 *   <li>only DONE runs emit {@code AgentEvent.Done} - failed runs emit Error,
 *       so their answers never enter the content window</li>
 * </ul>
 */
class E7HealthPipelineExperimentTest {

    private static final long COST_PER_RUN_MICROS = 500_000;
    private static final int QUIET_ANSWER_CHARS = 200;
    private static final int LOUD_ANSWER_CHARS = 380;

    @Test
    @DisplayName("one night, two windows: quiet half builds the baseline, loud half trips the tripwire")
    void oneNightOfTraffic() {
        HealthPipeline pipeline = new HealthPipeline();

        // ---- phase 1: the quiet half (runs n-00..n-15) ----
        for (int i = 0; i < 16; i++) {
            long denied = (i == 3 || i == 7) ? 2 : 0;
            long errors = i == 5 ? 1 : 0;
            pipeline.onRun(run("n-" + i, AgentState.Status.DONE,
                    100L + 100L * i, denied, errors));
            pipeline.accept(new AgentEvent.Done("x".repeat(QUIET_ANSWER_CHARS), new AgentState()));
        }
        HealthReport quiet = pipeline.snapshot();

        // ---- phase 2: the loud half (runs n-16..n-31, prompt regression) ----
        for (int i = 16; i < 32; i++) {
            AgentState.Status status = i < 18 ? AgentState.Status.ERROR
                    : i < 20 ? AgentState.Status.MAX_STEPS_EXCEEDED
                    : AgentState.Status.DONE;
            long denied = i == 27 ? 2 : 0;
            long errors = (i == 21 || i == 29) ? 1 : 0;
            pipeline.onRun(run("n-" + i, status, 100L + 100L * i, denied, errors));
            if (status == AgentState.Status.DONE) {
                pipeline.accept(new AgentEvent.Done("x".repeat(LOUD_ANSWER_CHARS), new AgentState()));
            }
        }
        HealthReport loud = pipeline.snapshot();

        // ---- quiet window: everything healthy, drift baseline building ----
        assertEquals(16, quiet.windowRuns());
        assertEquals(16, quiet.completedRuns());
        assertEquals(1.0, quiet.processCompletionRate(), 1e-9);
        // durations 100..1600 (n=16): P50 rank ceil(8)=8 -> 800; P95 rank ceil(15.2)=16 -> 1600
        assertEquals(800, quiet.latencyP50Ms());
        assertEquals(1600, quiet.latencyP95Ms());
        assertEquals(16 * COST_PER_RUN_MICROS, quiet.totalCostMicros());
        assertEquals((double) COST_PER_RUN_MICROS, quiet.avgCostPerTaskMicros(), 1e-9);
        assertEquals(4, quiet.deniedToolCalls());
        assertEquals(1, quiet.modelCallErrors());
        assertEquals(HealthReport.Drift.Status.BASELINE_BUILDING, quiet.drift().status());
        assertEquals(QUIET_ANSWER_CHARS, quiet.drift().currentAvgAnswerChars(), 1e-9);
        assertEquals(16, quiet.drift().sampleCount());

        // ---- loud window: full night (32 runs, default window keeps all) ----
        assertEquals(32, loud.windowRuns());
        assertEquals(28, loud.completedRuns());
        assertEquals(0.875, loud.processCompletionRate(), 1e-9);
        // durations 100..3200 (n=32): P50 rank ceil(16)=16 -> 1600; P95 rank ceil(30.4)=31 -> 3100
        assertEquals(1600, loud.latencyP50Ms());
        assertEquals(3100, loud.latencyP95Ms());
        assertEquals(32 * COST_PER_RUN_MICROS, loud.totalCostMicros());
        assertEquals(6, loud.deniedToolCalls());
        assertEquals(3, loud.modelCallErrors());

        // ---- drift: 16 quiet answers (200) + 12 loud answers (380) ----
        // content avg = (16*200 + 12*380) / 28 = 7760/28 ~= 277.143 -> ratio ~= 1.386 -> SUSPECTED
        assertEquals(HealthReport.Drift.Status.SUSPECTED, loud.drift().status());
        assertEquals(28, loud.drift().sampleCount());
        assertEquals(7760.0 / 28.0, loud.drift().currentAvgAnswerChars(), 1e-9);
        assertEquals(QUIET_ANSWER_CHARS, loud.drift().baselineAvgAnswerChars(), 1e-9);
        assertEquals((7760.0 / 28.0) / QUIET_ANSWER_CHARS, loud.drift().ratio(), 1e-9);

        printComparison(quiet, loud);
    }

    @Test
    @DisplayName("replaying the whole night into a fresh pipeline reproduces the report bit-for-bit")
    void replayReproducesTheNight() {
        HealthPipeline first = new HealthPipeline();
        HealthPipeline second = new HealthPipeline();
        for (int i = 0; i < 32; i++) {
            AgentState.Status status = i < 16 ? AgentState.Status.DONE
                    : i < 18 ? AgentState.Status.ERROR
                    : i < 20 ? AgentState.Status.MAX_STEPS_EXCEEDED
                    : AgentState.Status.DONE;
            long denied = (i == 3 || i == 7 || i == 27) ? 2 : 0;
            long errors = (i == 5 || i == 21 || i == 29) ? 1 : 0;
            RunMetrics row = run("n-" + i, status, 100L + 100L * i, denied, errors);
            first.onRun(row);
            second.onRun(row);
            if (status == AgentState.Status.DONE) {
                int chars = i < 16 ? QUIET_ANSWER_CHARS : LOUD_ANSWER_CHARS;
                AgentEvent.Done event = new AgentEvent.Done("x".repeat(chars), new AgentState());
                first.accept(event);
                second.accept(event);
            }
        }
        assertEquals(first.snapshot(), second.snapshot());
    }

    // ============ fixtures ============

    private static RunMetrics run(String id, AgentState.Status status, long durationMs,
                                  long denied, long errors) {
        return new RunMetrics(id, "support", status, null, durationMs,
                0, (int) errors, 0, (int) denied,
                new ModelResponse.TokenUsage(0, 0, 0, 0), COST_PER_RUN_MICROS);
    }

    /** The two-window comparison printed for the experiment notes. */
    private static void printComparison(HealthReport quiet, HealthReport loud) {
        System.out.println();
        System.out.println("=== E7 one-night health report (two reconciliation windows) ===");
        System.out.println("indicator              | quiet half (16 runs) | full night (32 runs)");
        System.out.println("-----------------------|----------------------|--------------------");
        System.out.printf("process completion     | %.3f                | %.3f%n",
                quiet.processCompletionRate(), loud.processCompletionRate());
        System.out.printf("cost total (microUSD)  | %d               | %d%n",
                quiet.totalCostMicros(), loud.totalCostMicros());
        System.out.printf("cost per task (microUSD) | %d              | %d%n",
                (long) quiet.avgCostPerTaskMicros(), (long) loud.avgCostPerTaskMicros());
        System.out.printf("latency P50 / P95 (ms) | %d / %d            | %d / %d%n",
                quiet.latencyP50Ms(), quiet.latencyP95Ms(),
                loud.latencyP50Ms(), loud.latencyP95Ms());
        System.out.printf("denied tools / errors  | %d / %d              | %d / %d%n",
                quiet.deniedToolCalls(), quiet.modelCallErrors(),
                loud.deniedToolCalls(), loud.modelCallErrors());
        System.out.printf("drift                  | %s (avg %.0f)      | %s (avg %.1f vs baseline %.0f, ratio %.3f)%n",
                quiet.drift().status(), quiet.drift().currentAvgAnswerChars(),
                loud.drift().status(), loud.drift().currentAvgAnswerChars(),
                loud.drift().baselineAvgAnswerChars(), loud.drift().ratio());
        System.out.println("coverage: completion=process-layer, cost=full, latency=full, safety=process-proxy, drift=tripwire");
    }
}
