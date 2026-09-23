package io.github.qwzhang01.agent.observability.ops;

import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.observability.cost.BudgetAlarmEvent;
import io.github.qwzhang01.agent.observability.cost.BudgetDimension;
import io.github.qwzhang01.agent.observability.cost.BudgetExhaustedException;
import io.github.qwzhang01.agent.observability.metrics.RunMetrics;
import io.github.qwzhang01.agent.observability.version.ComponentVersion;
import io.github.qwzhang01.agent.observability.version.RunRecord;
import io.github.qwzhang01.agent.observability.version.RunRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 7.4 acceptance for the translators: every native signal type gets
 * an OpsEvent with the right kind, severity and - always - a recommended
 * action an operator can follow.
 */
class OpsEventFactoriesTest {

    @Test
    void budgetExhaustedExceptionBecomesAPageWorthyEvent() {
        BudgetExhaustedException cause = new BudgetExhaustedException(
                "TENANT budget exhausted for 'acme': used 900 of 1000", 100, 1000);

        OpsEventBus.OpsEvent event = OpsEventFactories.budgetExhausted(cause, "run-7");

        assertEquals(OpsEventBus.Kind.BUDGET_EXHAUSTED, event.kind());
        assertTrue(event.pageWorthy());
        assertEquals("run-7", event.runId());
        assertTrue(event.message().contains("TENANT"));
        assertTrue(event.recommendedAction().contains("do not retry"),
                "the retry-won't-refill discipline must survive translation");
    }

    @Test
    void budgetWarningSeverityTracksHowCloseToTheLine() {
        OpsEventBus.OpsEvent eighty = OpsEventFactories.budgetWarning(
                new BudgetAlarmEvent(BudgetDimension.USER, "alice", 800, 1000, 80));
        OpsEventBus.OpsEvent ninetyFive = OpsEventFactories.budgetWarning(
                new BudgetAlarmEvent(BudgetDimension.USER, "alice", 950, 1000, 95));

        assertEquals(1, eighty.severity());
        assertEquals(2, ninetyFive.severity());
        assertTrue(ninetyFive.message().contains("95%"));
    }

    @Test
    void toolResultPrefixesMapToTheirKinds() {
        Optional<OpsEventBus.OpsEvent> denied =
                OpsEventFactories.fromToolResult("[DENIED] policy: secrets blocked",
                        "run-1", "step-2", "fs.read", "PROMPT v3");
        assertTrue(denied.isPresent());
        assertEquals(OpsEventBus.Kind.GUARDRAIL_HIT, denied.get().kind());
        assertEquals("fs.read", denied.get().toolName());
        assertEquals("step-2", denied.get().stepId());
        assertEquals("PROMPT v3", denied.get().versionCombination());

        Optional<OpsEventBus.OpsEvent> budgetDenied =
                OpsEventFactories.fromToolResult(
                        "[DENIED] budget exhausted: run tool-call limit 3 reached (used 3)",
                        "run-1", "step-2", "search", null);
        assertTrue(budgetDenied.isPresent());
        assertEquals(OpsEventBus.Kind.BUDGET_EXHAUSTED, budgetDenied.get().kind());

        Optional<OpsEventBus.OpsEvent> escalated =
                OpsEventFactories.fromToolResult("[SANDBOX_ESCALATED] needed network",
                        "run-1", "step-3", "shell", null);
        assertTrue(escalated.isPresent());
        assertEquals(OpsEventBus.Kind.SANDBOX_ESCALATION, escalated.get().kind());

        Optional<OpsEventBus.OpsEvent> refused =
                OpsEventFactories.fromToolResult("[A2A_REFUSED] capability mismatch",
                        "run-1", "step-4", "remote-agent", null);
        assertTrue(refused.isPresent());
        assertEquals(OpsEventBus.Kind.A2A_REFUSED, refused.get().kind());

        Optional<OpsEventBus.OpsEvent> normal =
                OpsEventFactories.fromToolResult("42", "run-1", "step-5", "calc", null);
        assertTrue(normal.isEmpty(), "a normal result is not an ops event");
    }

    @Test
    void runFailureCarriesVersionCombinationFromTheRegistry() {
        RunRegistry registry = new RunRegistry();
        registry.add(new RunRecord("run-1", "support",
                List.of(ComponentVersion.of(ComponentVersion.Kind.PROMPT, "support-system", "v3"),
                        ComponentVersion.of(ComponentVersion.Kind.MODEL, "premium", "2026-09.1")),
                metrics("run-1", "provider 500", 2, 1)));

        OpsEventBus.OpsEvent event = OpsEventFactories.runFailed(metrics("run-1", "provider 500", 2, 1),
                registry);

        assertEquals(OpsEventBus.Kind.RUN_FAILED, event.kind());
        assertEquals("run-1", event.runId());
        assertTrue(event.versionCombination().contains("PROMPT support-system@v3"),
                event.versionCombination());
        assertTrue(event.versionCombination().contains("MODEL premium@2026-09.1"));
        assertTrue(event.recommendedAction().contains("provider"),
                "provider-scoped failures point at the provider series first");
    }

    @Test
    void runFailureWithoutRegistryStaysHonest() {
        RunMetrics row = metrics("run-2", "kaput", 0, 0);
        OpsEventBus.OpsEvent event = OpsEventFactories.runFailed(row, null);

        assertEquals(null, event.versionCombination(), "no registry: no fabricated combination");
        assertTrue(event.recommendedAction().contains("step"),
                "non-provider failures point at steps and tool latency first");
    }

    @Test
    void batchTranslationKeepsOnlyGovernanceSignals() {
        List<OpsEventBus.OpsEvent> events = OpsEventFactories.fromToolResults(
                List.of("ok result", "[DENIED] blocked", "also ok", "[A2A_REFUSED] nope"),
                "run-1", "step-1", "mixed", null);

        assertEquals(2, events.size());
        assertEquals(OpsEventBus.Kind.GUARDRAIL_HIT, events.get(0).kind());
        assertEquals(OpsEventBus.Kind.A2A_REFUSED, events.get(1).kind());
    }

    private static RunMetrics metrics(String runId, String lastError, int modelErrors, int denied) {
        return new RunMetrics(runId, "support", AgentState.Status.ERROR, lastError, 500,
                3, modelErrors, 2, denied, new ModelResponse.TokenUsage(10, 5, 15, 0), 100);
    }
}
