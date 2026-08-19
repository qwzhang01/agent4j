package io.github.qwzhang01.agent.examples;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.scheduler.TaskScheduler;
import io.github.qwzhang01.agent.scheduler.nodes.DynamicSchedulerNode;
import io.github.qwzhang01.agent.workflow.ExecutionResult;
import io.github.qwzhang01.agent.workflow.GraphRuntime;
import io.github.qwzhang01.agent.workflow.StepRecord;
import io.github.qwzhang01.agent.workflow.Workflow;
import io.github.qwzhang01.agent.workflow.nodes.ActionNode;
import io.github.qwzhang01.agent.workflow.nodes.AgentNode;
import io.github.qwzhang01.agent.workflow.runtime.RunManager;

/**
 * Stage 7 acceptance example: agent-driven scheduling.
 * <p>
 * Unlike SchedulerExample (developer-chosen static parameters), this demo
 * shows the LLM deciding the scheduling parameters at runtime:
 * <pre>{@code
 * Demo 1: user says "watch PR #99 CI" -> LLM outputs {"action":"wait_event",
 *         "event_key":"ci-passed:pr-99"} -> DynamicSchedulerNode registers it
 * Demo 2: user says "check later" -> LLM outputs {"action":"schedule",
 *         "delay_seconds":1} -> auto-resume after the LLM-chosen delay
 * }</pre>
 * The graph definition contains NO hardcoded eventKey or delay - the LLM
 * (via the upstream AgentNode's output on the blackboard) drives both.
 * <p>
 * Run: mvn compile exec:java -pl examples -Dexec.mainClass=io.github.qwzhang01.agent.examples.LlmDrivenSchedulerExample
 */
public class LlmDrivenSchedulerExample {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Stage 7: Agent-Driven Scheduling (LLM decides) ===\n");

        demoLlmChosenEvent();
        demoLlmChosenDelay();

        System.out.println("=== Done ===");
    }

    // ============ Demo 1: LLM chooses WHAT to wait for ============

    private static void demoLlmChosenEvent() throws Exception {
        System.out.println("─".repeat(60));
        System.out.println("Demo 1: LLM chooses the event key at runtime\n");

        RunManager mgr = new RunManager();
        TaskScheduler scheduler = new TaskScheduler(mgr);
        mgr.setRuntime(new GraphRuntime().scheduler(scheduler));
        scheduler.start();

        // The "LLM": sees the user request, outputs a structured intent.
        // In production this is a real model call; here it is scripted.
        Agent decideAgent = new SimpleAgent(new AgentConfig(
                "decide-agent",
                "Output a scheduling intent as JSON.",
                MockModelClient.scripted().respondText(
                        "{\"action\":\"wait_event\",\"event_key\":\"ci-passed:pr-99\"}"),
                null, 5));

        // Graph: NO hardcoded eventKey anywhere
        Workflow wf = Workflow.builder("llm-event-flow")
                .node(AgentNode.of("decide", decideAgent))       // LLM writes intent to blackboard
                .node(DynamicSchedulerNode.of("wait", "decide")) // reads intent, registers trigger
                .node(ActionNode.of("merge", ctx -> "merged with: " + ctx.input()))
                .edge(Workflow.START, "decide")
                .edge("decide", "wait")
                .edge("wait", "merge")
                .edge("merge", Workflow.END)
                .build();

        System.out.println("User: watch PR #99 CI and merge when it passes");
        System.out.println("LLM intent: {\"action\":\"wait_event\",\"event_key\":\"ci-passed:pr-99\"}\n");

        ExecutionResult r1 = mgr.start(wf, "watch PR #99");
        printTrace(r1);
        System.out.println("Status: " + r1.status() + " (paused - LLM chose to wait for ci-passed:pr-99)\n");

        System.out.println("🔥 CI passed! Firing the LLM-chosen event...");
        scheduler.fireEvent("ci-passed:pr-99", "all-checks-green");
        Thread.sleep(300);

        var run = mgr.getRun(r1.resumeToken().runId());
        System.out.println("Final status: " + run.getStatus());
        run.getState().getTrace().stream()
                .filter(s -> "merge".equals(s.nodeId()) && s.status() == StepRecord.Status.SUCCESS)
                .findFirst()
                .ifPresent(s -> System.out.println("merge output: " + s.summary()));
        System.out.println("(eventKey chosen by LLM, not by the graph definition) ✅\n");
        scheduler.shutdown();
    }

    // ============ Demo 2: LLM chooses HOW LONG to wait ============

    private static void demoLlmChosenDelay() throws Exception {
        System.out.println("─".repeat(60));
        System.out.println("Demo 2: LLM chooses the delay at runtime\n");

        RunManager mgr = new RunManager();
        TaskScheduler scheduler = new TaskScheduler(mgr);
        mgr.setRuntime(new GraphRuntime().scheduler(scheduler));
        scheduler.start();

        Agent decideAgent = new SimpleAgent(new AgentConfig(
                "decide-agent",
                "Output a scheduling intent as JSON.",
                MockModelClient.scripted().respondText(
                        "{\"action\":\"schedule\",\"delay_seconds\":1}"),
                null, 5));

        // Graph: NO hardcoded delay anywhere
        Workflow wf = Workflow.builder("llm-schedule-flow")
                .node(AgentNode.of("decide", decideAgent))
                .node(DynamicSchedulerNode.of("check-later", "decide"))
                .node(ActionNode.of("report", ctx -> "check completed after resume"))
                .edge(Workflow.START, "decide")
                .edge("decide", "check-later")
                .edge("check-later", "report")
                .edge("report", Workflow.END)
                .build();

        System.out.println("User: check the data freshness later");
        System.out.println("LLM intent: {\"action\":\"schedule\",\"delay_seconds\":1}\n");

        ExecutionResult r1 = mgr.start(wf, "check later");
        printTrace(r1);
        System.out.println("Status: " + r1.status() + " (paused - LLM chose a 1s delay)\n");

        System.out.println("⏳ Scheduler will auto-resume after the LLM-chosen delay...");
        Thread.sleep(1800);

        var run = mgr.getRun(r1.resumeToken().runId());
        System.out.println("Final status: " + run.getStatus());
        System.out.println("(delay chosen by LLM, auto-resumed with no manual call) ✅\n");
        scheduler.shutdown();
    }

    private static void printTrace(ExecutionResult result) {
        for (StepRecord step : result.trace()) {
            var icon = switch (step.status()) {
                case SUCCESS -> "✓";
                case FAILED -> "✗";
                case PAUSED -> "⏸";
                case CANCELLED -> "⊘";
            };
            System.out.printf("  %s %-16s %s%n", icon, step.nodeId(),
                    step.summary() != null ? step.summary() : "");
        }
        System.out.println();
    }
}
