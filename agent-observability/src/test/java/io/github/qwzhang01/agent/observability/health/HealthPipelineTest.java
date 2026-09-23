package io.github.qwzhang01.agent.observability.health;

import io.github.qwzhang01.agent.core.agent.AgentEvent;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.observability.metrics.ModelCallMetrics;
import io.github.qwzhang01.agent.observability.metrics.RunMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit invariants of the health pipeline (KP7 E7): the deterministic math
 * (nearest-rank, two-layer completion), the window discipline, the drift
 * tripwire state machine, and the reproducibility contract. The simulated
 * production scenario lives in {@code E7HealthPipelineExperimentTest}.
 */
class HealthPipelineTest {

    private static RunMetrics run(String id, AgentState.Status status, long durationMs,
                                  long denied, long errors, long costMicros) {
        return new RunMetrics(id, "support", status, null, durationMs,
                0, (int) errors, 0, (int) denied,
                new ModelResponse.TokenUsage(0, 0, 0, 0), costMicros);
    }

    private static AgentEvent.Done done(String answer) {
        return new AgentEvent.Done(answer, new AgentState());
    }

    private static void feedRuns(HealthPipeline pipeline, RunMetrics... runs) {
        for (RunMetrics r : runs) {
            pipeline.onRun(r);
        }
    }

    @Test
    @DisplayName("snapshot without runs is rejected - an empty window is not an eval")
    void snapshotWithoutRunsIsRejected() {
        HealthPipeline pipeline = new HealthPipeline();
        assertThrows(IllegalArgumentException.class, pipeline::snapshot);
    }

    @Test
    @DisplayName("window evicts oldest rows, keeping the latest N")
    void windowEvictsOldestRuns() {
        HealthPipeline pipeline = new HealthPipeline(3);
        feedRuns(pipeline,
                run("a", AgentState.Status.DONE, 10, 0, 0, 0),
                run("b", AgentState.Status.DONE, 20, 0, 0, 0),
                run("c", AgentState.Status.DONE, 30, 0, 0, 0),
                run("d", AgentState.Status.DONE, 40, 0, 0, 0));
        HealthReport report = pipeline.snapshot();
        // window = {20, 30, 40}: P50 rank ceil(1.5)=2 -> 30, P95 rank ceil(2.85)=3 -> 40
        assertEquals(3, report.windowRuns());
        assertEquals(30, report.latencyP50Ms());
        assertEquals(40, report.latencyP95Ms());
    }

    @Test
    @DisplayName("windowSize below 1 is rejected")
    void windowSizeBelowOneIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new HealthPipeline(0));
    }

    @Test
    @DisplayName("process completion counts DONE only - ERROR and MAX_STEPS are not completed")
    void processCompletionCountsDoneOnly() {
        HealthPipeline pipeline = new HealthPipeline();
        feedRuns(pipeline,
                run("a", AgentState.Status.DONE, 10, 0, 0, 0),
                run("b", AgentState.Status.DONE, 10, 0, 0, 0),
                run("c", AgentState.Status.DONE, 10, 0, 0, 0),
                run("d", AgentState.Status.ERROR, 10, 0, 0, 0),
                run("e", AgentState.Status.MAX_STEPS_EXCEEDED, 10, 0, 0, 0));
        HealthReport report = pipeline.snapshot();
        assertEquals(5, report.windowRuns());
        assertEquals(3, report.completedRuns());
        assertEquals(0.6, report.processCompletionRate(), 1e-9);
    }

    @Test
    @DisplayName("nearest-rank percentiles on an even window (n=20)")
    void percentilesEvenWindow() {
        HealthPipeline pipeline = new HealthPipeline();
        for (int i = 0; i < 20; i++) {
            pipeline.onRun(run("r" + i, AgentState.Status.DONE, 100L + 100L * i, 0, 0, 0));
        }
        HealthReport report = pipeline.snapshot();
        // sorted 100..2000: P50 rank ceil(10)=10 -> 1000; P95 rank ceil(19)=19 -> 1900
        assertEquals(1000, report.latencyP50Ms());
        assertEquals(1900, report.latencyP95Ms());
    }

    @Test
    @DisplayName("nearest-rank percentiles on an odd window (n=5)")
    void percentilesOddWindow() {
        HealthPipeline pipeline = new HealthPipeline();
        feedRuns(pipeline,
                run("a", AgentState.Status.DONE, 50, 0, 0, 0),
                run("b", AgentState.Status.DONE, 10, 0, 0, 0),
                run("c", AgentState.Status.DONE, 30, 0, 0, 0),
                run("d", AgentState.Status.DONE, 20, 0, 0, 0),
                run("e", AgentState.Status.DONE, 40, 0, 0, 0));
        HealthReport report = pipeline.snapshot();
        // sorted 10,20,30,40,50: P50 rank ceil(2.5)=3 -> 30; P95 rank ceil(4.75)=5 -> 50
        assertEquals(30, report.latencyP50Ms());
        assertEquals(50, report.latencyP95Ms());
    }

    @Test
    @DisplayName("cost aggregates across the window and averages per task")
    void costAggregatesAndAverages() {
        HealthPipeline pipeline = new HealthPipeline();
        feedRuns(pipeline,
                run("a", AgentState.Status.DONE, 10, 0, 0, 100),
                run("b", AgentState.Status.DONE, 10, 0, 0, 300));
        HealthReport report = pipeline.snapshot();
        assertEquals(400, report.totalCostMicros());
        assertEquals(200.0, report.avgCostPerTaskMicros(), 1e-9);
        assertTrue(report.costKnown());
    }

    @Test
    @DisplayName("zero cost is honestly flagged as unknown, not free")
    void zeroCostIsUnknownNotFree() {
        HealthPipeline pipeline = new HealthPipeline();
        feedRuns(pipeline, run("a", AgentState.Status.DONE, 10, 0, 0, 0));
        HealthReport report = pipeline.snapshot();
        assertEquals(0, report.totalCostMicros());
        assertEquals(false, report.costKnown());
    }

    @Test
    @DisplayName("safety signals sum denied tool calls and model call errors")
    void safetySignalsSum() {
        HealthPipeline pipeline = new HealthPipeline();
        feedRuns(pipeline,
                run("a", AgentState.Status.DONE, 10, 1, 1, 0),
                run("b", AgentState.Status.DONE, 10, 2, 3, 0));
        HealthReport report = pipeline.snapshot();
        assertEquals(3, report.deniedToolCalls());
        assertEquals(4, report.modelCallErrors());
    }

    @Test
    @DisplayName("only Done events feed drift - deltas and tool results are not answers")
    void onlyDoneFeedsDrift() {
        HealthPipeline pipeline = new HealthPipeline();
        pipeline.accept(new AgentEvent.ContentDelta("partial"));
        pipeline.accept(new AgentEvent.ToolFinished("t1", "search", "{\"hits\": []}"));
        pipeline.accept(new AgentEvent.ToolStarted(null));
        feedRuns(pipeline, run("a", AgentState.Status.DONE, 10, 0, 0, 0));
        HealthReport report = pipeline.snapshot();
        assertEquals(0, report.drift().sampleCount());
        assertEquals(HealthReport.Drift.Status.BASELINE_BUILDING, report.drift().status());

        pipeline.accept(done("abc"));
        HealthReport after = pipeline.snapshot();
        assertEquals(1, after.drift().sampleCount());
    }

    @Test
    @DisplayName("drift stays BASELINE_BUILDING while samples are scarce")
    void driftBuildsBaselineWhileSamplesLow() {
        HealthPipeline pipeline = new HealthPipeline();
        feedRuns(pipeline, run("a", AgentState.Status.DONE, 10, 0, 0, 0));
        for (int i = 0; i < MIN_DRIFT_SAMPLES() - 1; i++) {
            pipeline.accept(done("x".repeat(100)));
        }
        HealthReport report = pipeline.snapshot();
        assertEquals(HealthReport.Drift.Status.BASELINE_BUILDING, report.drift().status());
        assertEquals(4, report.drift().sampleCount());
    }

    @Test
    @DisplayName("drift is STABLE within the band (ratio 1.075 vs 0.30 band)")
    void driftStableWithinBand() {
        HealthPipeline pipeline = new HealthPipeline();
        feedRuns(pipeline, run("a", AgentState.Status.DONE, 10, 0, 0, 0));
        for (int i = 0; i < 6; i++) {
            pipeline.accept(done("x".repeat(100)));
        }
        pipeline.snapshot();  // reconcile: baseline = 100

        for (int i = 0; i < 6; i++) {
            pipeline.accept(done("y".repeat(115)));
        }
        HealthReport report = pipeline.snapshot();
        // window avg = (6*100 + 6*115) / 12 = 107.5 -> ratio 1.075, inside the band
        assertEquals(HealthReport.Drift.Status.STABLE, report.drift().status());
        assertEquals(107.5, report.drift().currentAvgAnswerChars(), 1e-9);
        assertEquals(100.0, report.drift().baselineAvgAnswerChars(), 1e-9);
        assertEquals(1.075, report.drift().ratio(), 1e-9);
    }

    @Test
    @DisplayName("drift trips SUSPECTED outside the band (ratio 1.5)")
    void driftSuspectedOutsideBand() {
        HealthPipeline pipeline = new HealthPipeline();
        feedRuns(pipeline, run("a", AgentState.Status.DONE, 10, 0, 0, 0));
        for (int i = 0; i < 6; i++) {
            pipeline.accept(done("x".repeat(100)));
        }
        pipeline.snapshot();  // reconcile: baseline = 100

        for (int i = 0; i < 6; i++) {
            pipeline.accept(done("z".repeat(200)));
        }
        HealthReport report = pipeline.snapshot();
        // window avg = (600 + 1200) / 12 = 150 -> ratio 1.5, outside the band
        assertEquals(HealthReport.Drift.Status.SUSPECTED, report.drift().status());
        assertEquals(150.0, report.drift().currentAvgAnswerChars(), 1e-9);
        assertEquals(1.5, report.drift().ratio(), 1e-9);
    }

    @Test
    @DisplayName("empty answers after empty baseline stay STABLE; output appearing from nothing is SUSPECTED")
    void driftEdgeCasesOnZeroBaseline() {
        HealthPipeline pipeline = new HealthPipeline();
        feedRuns(pipeline, run("a", AgentState.Status.DONE, 10, 0, 0, 0));
        for (int i = 0; i < 6; i++) {
            pipeline.accept(done(""));
        }
        pipeline.snapshot();  // baseline = 0

        HealthReport stillEmpty = pipeline.snapshot();
        assertEquals(HealthReport.Drift.Status.STABLE, stillEmpty.drift().status());

        pipeline.accept(done("suddenly verbose"));
        HealthReport outputAppeared = pipeline.snapshot();
        assertEquals(HealthReport.Drift.Status.SUSPECTED, outputAppeared.drift().status());
    }

    // reconciliation and reproducibility

    @Test
    @DisplayName("snapshot is a reconciliation point: re-snapshotting without data is STABLE at 1.0")
    void snapshotReconcilesBaselineForward() {
        HealthPipeline pipeline = new HealthPipeline();
        feedRuns(pipeline, run("a", AgentState.Status.DONE, 10, 0, 0, 0));
        for (int i = 0; i < 6; i++) {
            pipeline.accept(done("x".repeat(100)));
        }
        HealthReport first = pipeline.snapshot();
        assertEquals(HealthReport.Drift.Status.BASELINE_BUILDING, first.drift().status());

        HealthReport second = pipeline.snapshot();
        assertEquals(HealthReport.Drift.Status.STABLE, second.drift().status());
        assertEquals(1.0, second.drift().ratio(), 1e-9);
        // the numeric section is untouched by reconciliation
        assertEquals(first.windowRuns(), second.windowRuns());
        assertEquals(first.latencyP50Ms(), second.latencyP50Ms());
        // and the drift section did change - documented non-idempotence
        assertNotEquals(first.drift().status(), second.drift().status());
    }

    @Test
    @DisplayName("reproducibility: two instances fed identically produce equal reports")
    void reproducibleAcrossInstances() {
        RunMetrics[] runs = {
                run("a", AgentState.Status.DONE, 100, 1, 0, 500),
                run("b", AgentState.Status.ERROR, 300, 0, 2, 500),
                run("c", AgentState.Status.DONE, 200, 0, 0, 500),
                run("d", AgentState.Status.MAX_STEPS_EXCEEDED, 400, 0, 0, 500),
                run("e", AgentState.Status.DONE, 150, 0, 0, 500)
        };
        HealthPipeline first = new HealthPipeline();
        HealthPipeline second = new HealthPipeline();
        feedRuns(first, runs);
        feedRuns(second, runs);
        for (int i = 0; i < 6; i++) {
            String answer = "x".repeat(80 + 10 * i);
            first.accept(done(answer));
            second.accept(done(answer));
        }
        assertEquals(first.snapshot(), second.snapshot());
    }

    @Test
    @DisplayName("call-level sink methods are no-ops - they do not touch the report")
    void callLevelSinksAreNoOps() {
        HealthPipeline pipeline = new HealthPipeline();
        feedRuns(pipeline, run("a", AgentState.Status.DONE, 10, 0, 0, 42));
        HealthReport before = pipeline.snapshot();

        pipeline.onModelCall(new ModelCallMetrics("gpt-x", 5, 1, 2, 3, "stop", null));
        HealthReport after = pipeline.snapshot();

        assertEquals(before.windowRuns(), after.windowRuns());
        assertEquals(before.totalCostMicros(), after.totalCostMicros());
        assertEquals(before.latencyP50Ms(), after.latencyP50Ms());
        assertEquals(before.deniedToolCalls(), after.deniedToolCalls());
        assertEquals(before.modelCallErrors(), after.modelCallErrors());
    }

    private static int MIN_DRIFT_SAMPLES() {
        return HealthPipeline.MIN_DRIFT_SAMPLES;
    }
}
