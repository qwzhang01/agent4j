package io.github.qwzhang01.agent.examples;

import io.github.qwzhang01.agent.scheduler.AsyncTask;
import io.github.qwzhang01.agent.scheduler.TaskPriority;
import io.github.qwzhang01.agent.scheduler.TaskScheduler;
import io.github.qwzhang01.agent.scheduler.nodes.DispatchTaskNode;
import io.github.qwzhang01.agent.scheduler.nodes.ScheduleResumeNode;
import io.github.qwzhang01.agent.scheduler.nodes.WaitEventNode;
import io.github.qwzhang01.agent.workflow.ExecutionResult;
import io.github.qwzhang01.agent.workflow.GraphRuntime;
import io.github.qwzhang01.agent.workflow.StepRecord;
import io.github.qwzhang01.agent.workflow.Workflow;
import io.github.qwzhang01.agent.workflow.nodes.ActionNode;
import io.github.qwzhang01.agent.workflow.runtime.RunManager;

import java.time.Duration;
import java.util.List;

/**
 * Stage 7 acceptance example: automatic resume via TaskScheduler.
 * <p>
 * Three demos:
 * 1. Scheduled resume: Agent says "check again in 2 seconds" -> auto-resume
 * 2. Event-driven resume: Agent says "wait for CI" -> fire -> auto-resume
 * 3. Async task queue: Agent dispatches 3 sub-tasks -> consumed by priority
 * <p>
 * Run: mvn compile exec:java -pl examples -Dexec.mainClass=io.github.qwzhang01.agent.examples.SchedulerExample
 */
public class SchedulerExample {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Stage 7: TaskScheduler - Automatic Resume ===\n");

        demoScheduledResume();
        demoEventResume();
        demoTaskQueue();

        System.out.println("=== Done ===");
    }

    // ============ Demo 1: Scheduled Resume ============

    private static void demoScheduledResume() throws Exception {
        System.out.println("─".repeat(60));
        System.out.println("Demo 1: Scheduled Resume (check again in 2 seconds)\n");

        RunManager mgr = new RunManager();
        TaskScheduler scheduler = new TaskScheduler(mgr);
        mgr.setRuntime(new GraphRuntime().scheduler(scheduler));
        scheduler.start();

        Workflow wf = Workflow.builder("scheduled-flow")
                .node(ActionNode.of("start", ctx -> "task started"))
                .node(ScheduleResumeNode.of("check-later", Duration.ofSeconds(2)))
                .node(ActionNode.of("done", ctx -> "task completed after resume"))
                .edge(Workflow.START, "start")
                .edge("start", "check-later")
                .edge("check-later", "done")
                .edge("done", Workflow.END)
                .build();

        System.out.println("Starting workflow (will pause at 'check-later')...");
        ExecutionResult r1 = mgr.start(wf, "input");
        printTrace(r1);
        System.out.println("Status: " + r1.status() + " (waiting for auto-resume)\n");

        System.out.println("⏳ Scheduler will auto-resume in 2 seconds...");
        Thread.sleep(3000);

        var run = mgr.getRun(r1.resumeToken().runId());
        System.out.println("Final run status: " + run.getStatus());
        System.out.println("(auto-resumed without manual resume call) ✅\n");
        scheduler.shutdown();
    }

    // ============ Demo 2: Event-Driven Resume ============

    private static void demoEventResume() throws Exception {
        System.out.println("─".repeat(60));
        System.out.println("Demo 2: Event-Driven Resume (wait for CI to pass)\n");

        RunManager mgr = new RunManager();
        TaskScheduler scheduler = new TaskScheduler(mgr);
        mgr.setRuntime(new GraphRuntime().scheduler(scheduler));
        scheduler.start();

        Workflow wf = Workflow.builder("ci-flow")
                .node(ActionNode.of("submit", ctx -> "PR submitted"))
                .node(WaitEventNode.of("wait-ci", "ci-passed:pr-42"))
                .node(ActionNode.of("merge", ctx -> "merged with: " + ctx.input()))
                .edge(Workflow.START, "submit")
                .edge("submit", "wait-ci")
                .edge("wait-ci", "merge")
                .edge("merge", Workflow.END)
                .build();

        System.out.println("Starting workflow (will pause at 'wait-ci')...");
        ExecutionResult r1 = mgr.start(wf, "pr-branch");
        printTrace(r1);
        System.out.println("Status: " + r1.status() + " (waiting for CI event)\n");

        System.out.println("⏳ Simulating CI completion in 1 second...");
        Thread.sleep(1000);

        System.out.println("🔥 CI passed! Firing event 'ci-passed:pr-42'...");
        scheduler.fireEvent("ci-passed:pr-42", "all-checks-green");
        Thread.sleep(300);

        var run = mgr.getRun(r1.resumeToken().runId());
        System.out.println("Final run status: " + run.getStatus());
        // Verify the event payload flowed into the merge node
        var trace = run.getState().getTrace();
        trace.stream()
                .filter(s -> "merge".equals(s.nodeId()))
                .findFirst()
                .ifPresent(s -> System.out.println("merge node output: " + s.summary()));
        System.out.println("(event payload flowed through) ✅\n");
        scheduler.shutdown();
    }

    // ============ Demo 3: Async Task Queue ============

    private static void demoTaskQueue() {
        System.out.println("─".repeat(60));
        System.out.println("Demo 3: Async Task Queue (dispatch 3 sub-tasks by priority)\n");

        RunManager mgr = new RunManager();
        TaskScheduler scheduler = new TaskScheduler(mgr);
        mgr.setRuntime(new GraphRuntime().scheduler(scheduler));
        scheduler.start();

        Workflow wf = Workflow.builder("dispatch-flow")
                .node(ActionNode.of("plan", ctx -> "planning sub-tasks"))
                .node(DispatchTaskNode.of("dispatch", ctx -> List.of(
                        AsyncTask.of(ctx.runId(), "search-A", TaskPriority.NORMAL, "search-flow"),
                        AsyncTask.of(ctx.runId(), "search-B", TaskPriority.URGENT, "search-flow"),
                        AsyncTask.of(ctx.runId(), "search-C", TaskPriority.LOW, "search-flow"))))
                .edge(Workflow.START, "plan")
                .edge("plan", "dispatch")
                .edge("dispatch", Workflow.END)
                .build();

        System.out.println("Starting workflow (dispatches 3 tasks)...");
        ExecutionResult result = mgr.start(wf, "research-topic");
        printTrace(result);

        System.out.println("Queue size: " + scheduler.getTaskQueue().size());
        System.out.println("Consuming by priority:");
        while (!scheduler.getTaskQueue().isEmpty()) {
            AsyncTask task = scheduler.pollNextTask();
            System.out.printf("  -> [%s] %s%n", task.priority(), task.input());
        }
        System.out.println("(URGENT consumed first, LOW last) ✅\n");
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
