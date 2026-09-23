package io.github.qwzhang01.agent.observability.ops;

import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.observability.cost.BudgetAlarmEvent;
import io.github.qwzhang01.agent.observability.cost.BudgetDimension;
import io.github.qwzhang01.agent.observability.cost.BudgetExhaustedException;
import io.github.qwzhang01.agent.observability.metrics.RunMetrics;
import io.github.qwzhang01.agent.observability.version.RunRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Translators feeding the {@link OpsEventBus} : every signal the
 * roadmap names gets a bridge from its native type to an {@link
 * OpsEventBus.OpsEvent} with coordinates and a recommended action.
 * <p>
 * No new instrumentation points: the bridges READ the exceptions, alarm
 * events and registry rows that already exist. A deployment wires the bus
 * once and gets budget exhaustion, run failures and guardrail/sandbox/A2A
 * denials (via their shared text-prefix contract) as one stream.
 */
public final class OpsEventFactories {

    private OpsEventFactories() {
    }

    /** Budget exhaustion (the blocking kind) - always page-worthy. */
    public static OpsEventBus.OpsEvent budgetExhausted(BudgetExhaustedException cause, String runId) {
        Objects.requireNonNull(cause, "cause");
        return new OpsEventBus.OpsEvent(
                OpsEventBus.Kind.BUDGET_EXHAUSTED,
                3,
                Instant.now(),
                runId,
                null,
                null,
                null,
                null,
                "budget exhausted: " + cause.getMessage(),
                "raise the limit on the hot axis or let the run stop (do not retry: "
                        + "retrying does not refill a budget)");
    }

    /** Budget warning (WARN, non-blocking) - attention line, not a page. */
    public static OpsEventBus.OpsEvent budgetWarning(BudgetAlarmEvent alarm) {
        Objects.requireNonNull(alarm, "alarm");
        return new OpsEventBus.OpsEvent(
                OpsEventBus.Kind.BUDGET_EXHAUSTED,
                alarm.percentUsed() >= 90 ? 2 : 1,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                "budget " + alarm.percentUsed() + "% used on " + alarm.dimension() + " '" + alarm.key()
                        + "' (" + alarm.usedTokens() + "/" + alarm.limitTokens() + " tokens)",
                "check the burn rate on this axis; raise the limit or throttle the workload "
                        + "before it denies calls");
    }

    /**
     * Tool-result governance signal (guardrail hit, sandbox escalation,
     * A2A refusal): detected via the shared {@code [PREFIX] ...} text
     * contract the governed chains already emit.
     */
    public static Optional<OpsEventBus.OpsEvent> fromToolResult(
            String toolResult, String runId, String stepId, String toolName,
            String versionCombination) {
        if (toolResult == null || toolResult.isBlank()) {
            return Optional.empty();
        }
        if (toolResult.startsWith("[DENIED] ")) {
            String body = toolResult.substring("[DENIED] ".length());
            boolean budget = body.contains("budget exhausted");
            return Optional.of(new OpsEventBus.OpsEvent(
                    budget ? OpsEventBus.Kind.BUDGET_EXHAUSTED : OpsEventBus.Kind.GUARDRAIL_HIT,
                    budget ? 2 : 2,
                    Instant.now(),
                    runId,
                    stepId,
                    toolName,
                    null,
                    versionCombination,
                    budget ? "tool call denied by budget: " + body
                            : "tool call denied by guardrail: " + body,
                    budget
                            ? "check the run's tool-call budget and the loop's usage pattern"
                            : "review the denial reason against the guardrail policy; "
                            + "a denied spike is the leading injection indicator"));
        }
        if (toolResult.startsWith("[RATE_LIMITED] ")) {
            return Optional.of(new OpsEventBus.OpsEvent(
                    OpsEventBus.Kind.GUARDRAIL_HIT,
                    1,
                    Instant.now(),
                    runId,
                    stepId,
                    toolName,
                    null,
                    versionCombination,
                    "tool call rate-limited: " + toolResult.substring("[RATE_LIMITED] ".length()),
                    "check the rate policy for this tool and the calling pattern; "
                            + "sustained limiting means the policy or the workload must change"));
        }
        if (toolResult.startsWith("[SANDBOX_ESCALATED] ") || toolResult.startsWith("[ESCALATED] ")) {
            return Optional.of(new OpsEventBus.OpsEvent(
                    OpsEventBus.Kind.SANDBOX_ESCALATION,
                    2,
                    Instant.now(),
                    runId,
                    stepId,
                    toolName,
                    null,
                    versionCombination,
                    "sandbox escalated: " + toolResult,
                    "verify the escalation reason against the sandbox policy; "
                            + "escalation frequency is the sandbox-permissiveness signal"));
        }
        if (toolResult.startsWith("[A2A_REFUSED] ")) {
            return Optional.of(new OpsEventBus.OpsEvent(
                    OpsEventBus.Kind.A2A_REFUSED,
                    2,
                    Instant.now(),
                    runId,
                    stepId,
                    toolName,
                    null,
                    versionCombination,
                    "remote agent refused the task: " + toolResult.substring("[A2A_REFUSED] ".length()),
                    "check the remote agent's trust gate and the task's capability "
                            + "requirements; refusals cluster around auth and scope drift"));
        }
        return Optional.empty();
    }

    /**
     * Run failure - the anchor event: coordinates from the metrics row,
     * version combination from the registry (7.4 "locate to Run and version".
     */
    public static OpsEventBus.OpsEvent runFailed(
            RunMetrics metrics, RunRegistry registry) {
        Objects.requireNonNull(metrics, "metrics");
        String combination = registry == null ? null
                : registry.byRunId(metrics.runId())
                        .map(r -> r.combination())
                        .orElse(null);
        boolean providerScope = metrics.modelCallErrors() > 0;
        return new OpsEventBus.OpsEvent(
                OpsEventBus.Kind.RUN_FAILED,
                3,
                Instant.now(),
                metrics.runId(),
                null,
                metrics.deniedToolCalls() > 0 ? "(multiple)" : null,
                providerScope ? "(see model errors)" : null,
                combination,
                "run failed: " + (metrics.lastError() == null ? metrics.status().name() : metrics.lastError())
                        + " (" + metrics.modelCallErrors() + " model errors, "
                        + metrics.deniedToolCalls() + " denied tools)",
                providerScope
                        ? "start from the provider error series (by model id) and the "
                                + "fallback rate; then replay the run's steps"
                        : "start from the run's step events and tool latency quantiles; "
                                + "replay the failing case from the trajectory");
    }

    /** Convenience list form for batch translation of sampled tool results. */
    public static List<OpsEventBus.OpsEvent> fromToolResults(
            List<String> toolResults, String runId, String stepId, String toolName,
            String versionCombination) {
        List<OpsEventBus.OpsEvent> out = new ArrayList<>();
        for (String result : toolResults) {
            fromToolResult(result, runId, stepId, toolName, versionCombination)
                    .ifPresent(out::add);
        }
        return out;
    }
}
