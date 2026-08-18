package io.github.qwzhang01.agent.scheduler;

import io.github.qwzhang01.agent.workflow.ExecutionResult;
import io.github.qwzhang01.agent.workflow.GraphRuntime;
import io.github.qwzhang01.agent.workflow.Workflow;
import io.github.qwzhang01.agent.workflow.nodes.ActionNode;
import io.github.qwzhang01.agent.workflow.runtime.RunManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 7 tests: scheduled resume, event-driven resume, async task queue, token budget.
 */
class TaskSchedulerTest {

    private ScheduledExecutorService executor;
    private RunManager runManager;
    private TaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        executor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "test-scheduler");
            t.setDaemon(true);
            return t;
        });
        // Single RunManager throughout; scheduler wired via setRuntime
        runManager = new RunManager();
        scheduler = new TaskScheduler(runManager, executor);
        runManager.setRuntime(new GraphRuntime().scheduler(scheduler));
        scheduler.start();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    // ============ M7.1: Scheduled Resume ============

    @Test
    void scheduledResumeAutomaticallyResumesRun() throws Exception {
        Workflow wf = Workflow.builder("scheduled-flow")
                .node(ActionNode.of("a", ctx -> "a-done"))
                .node(io.github.qwzhang01.agent.scheduler.nodes.ScheduleResumeNode.of("wait", Duration.ofMillis(200)))
                .node(ActionNode.of("b", ctx -> "b-done"))
                .edge(Workflow.START, "a")
                .edge("a", "wait")
                .edge("wait", "b")
                .edge("b", Workflow.END)
                .build();

        ExecutionResult r1 = runManager.start(wf, "input");
        assertTrue(r1.isPaused());

        // Wait for auto-resume
        Thread.sleep(600);

        var run = runManager.getRun(r1.resumeToken().runId());
        assertNotNull(run);
        assertEquals(io.github.qwzhang01.agent.workflow.runtime.RunState.SUCCEEDED, run.getStatus());
    }

    // ============ M7.2: Event-Driven Resume ============

    @Test
    void eventFiredResumesRun() throws Exception {
        Workflow wf = Workflow.builder("event-flow")
                .node(ActionNode.of("a", ctx -> "a-done"))
                .node(io.github.qwzhang01.agent.scheduler.nodes.WaitEventNode.of("wait", "ci-passed:pr-123"))
                .node(ActionNode.of("b", ctx -> "b-done"))
                .edge(Workflow.START, "a")
                .edge("a", "wait")
                .edge("wait", "b")
                .edge("b", Workflow.END)
                .build();

        ExecutionResult r1 = runManager.start(wf, "input");
        assertTrue(r1.isPaused());

        scheduler.fireEvent("ci-passed:pr-123", "ci-success");
        Thread.sleep(300);

        var run = runManager.getRun(r1.resumeToken().runId());
        assertNotNull(run);
        assertEquals(io.github.qwzhang01.agent.workflow.runtime.RunState.SUCCEEDED, run.getStatus());
    }

    @Test
    void eventPayloadAvailableToResumedNode() throws Exception {
        Workflow wf = Workflow.builder("payload-flow")
                .node(io.github.qwzhang01.agent.scheduler.nodes.WaitEventNode.of("wait", "data-ready"))
                .node(ActionNode.of("use", ctx -> "got:" + ctx.input()))
                .edge(Workflow.START, "wait")
                .edge("wait", "use")
                .edge("use", Workflow.END)
                .build();

        ExecutionResult r1 = runManager.start(wf, "input");
        assertTrue(r1.isPaused());

        scheduler.fireEvent("data-ready", "payload-xyz");
        Thread.sleep(300);

        var run = runManager.getRun(r1.resumeToken().runId());
        assertNotNull(run);
        assertEquals(io.github.qwzhang01.agent.workflow.runtime.RunState.SUCCEEDED, run.getStatus());
    }

    @Test
    void eventFiredWithoutPayloadStillResumes() throws Exception {
        // Regression (Bug 1): fire(key) with no payload must still be visible
        // via hasEventFired(), otherwise the node re-pauses or times out.
        Workflow wf = Workflow.builder("no-payload-flow")
                .node(io.github.qwzhang01.agent.scheduler.nodes.WaitEventNode.of("wait", "signal-only"))
                .node(ActionNode.of("after", ctx -> "signal received"))
                .edge(Workflow.START, "wait")
                .edge("wait", "after")
                .edge("after", Workflow.END)
                .build();

        ExecutionResult r1 = runManager.start(wf, "input");
        assertTrue(r1.isPaused());

        scheduler.fireEvent("signal-only");  // no payload
        Thread.sleep(300);

        var run = runManager.getRun(r1.resumeToken().runId());
        assertNotNull(run);
        assertEquals(io.github.qwzhang01.agent.workflow.runtime.RunState.SUCCEEDED, run.getStatus(),
                "run should complete even when the event carries no payload");
    }

    @Test
    void resumeTerminalRunIsRejected() throws Exception {
        // Regression (Bug 2): a SUCCEEDED run must not be resumable
        // (would re-execute nodes from a stale cursor).
        Workflow pauseWf = Workflow.builder("pause-flow")
                .node(io.github.qwzhang01.agent.scheduler.nodes.WaitEventNode.of("wait", "evt"))
                .edge(Workflow.START, "wait")
                .edge("wait", Workflow.END)
                .build();
        ExecutionResult paused = runManager.start(pauseWf, "input");
        assertTrue(paused.isPaused());
        String pid = paused.resumeToken().runId();

        scheduler.fireEvent("evt");
        Thread.sleep(300);

        var run = runManager.getRun(pid);
        assertEquals(io.github.qwzhang01.agent.workflow.runtime.RunState.SUCCEEDED, run.getStatus());

        // Now resume the terminal run -> must throw
        assertThrows(io.github.qwzhang01.agent.workflow.WorkflowException.class,
                () -> runManager.resume(pid));
    }

    // ============ M7.3: Async Task Queue ============

    @Test
    void taskQueueConsumesByPriority() {
        AsyncTaskQueue queue = new AsyncTaskQueue();

        queue.enqueue(AsyncTask.of("run-1", "low-task", TaskPriority.LOW, "wf"));
        queue.enqueue(AsyncTask.of("run-1", "normal-task", TaskPriority.NORMAL, "wf"));
        queue.enqueue(AsyncTask.of("run-1", "urgent-task", TaskPriority.URGENT, "wf"));

        assertEquals(TaskPriority.URGENT, queue.pollNext().priority());
        assertEquals(TaskPriority.NORMAL, queue.pollNext().priority());
        assertEquals(TaskPriority.LOW, queue.pollNext().priority());
        assertNull(queue.pollNext());
    }

    @Test
    void dispatchTaskNodeEnqueuesTasks() {
        Workflow wf = Workflow.builder("dispatch-flow")
                .node(io.github.qwzhang01.agent.scheduler.nodes.DispatchTaskNode.of("dispatch",
                        ctx -> java.util.List.of(
                                AsyncTask.of(ctx.runId(), "task-1", TaskPriority.HIGH, "sub-wf"),
                                AsyncTask.of(ctx.runId(), "task-2", TaskPriority.NORMAL, "sub-wf"))))
                .edge(Workflow.START, "dispatch")
                .edge("dispatch", Workflow.END)
                .build();

        ExecutionResult result = runManager.start(wf, "input");
        assertTrue(result.isSucceeded());

        assertEquals(2, scheduler.getTaskQueue().size());
        AsyncTask first = scheduler.pollNextTask();
        assertEquals(TaskPriority.HIGH, first.priority());
    }

    // ============ M7.4: Token Budget ============

    @Test
    void tokenBudgetTracksConsumption() {
        TokenBudget budget = new TokenBudget(1000);
        assertTrue(budget.consume(400));
        assertTrue(budget.consume(500));
        assertFalse(budget.consume(200));
        assertTrue(budget.isExceeded());
    }

    @Test
    void schedulerTokenBudgetPerRun() {
        scheduler.setBudget("run-1", 1000);
        assertTrue(scheduler.consumeTokens("run-1", 600));
        assertTrue(scheduler.consumeTokens("run-1", 400));
        assertFalse(scheduler.consumeTokens("run-1", 1));
    }

    @Test
    void noBudgetMeansUnlimited() {
        assertTrue(scheduler.consumeTokens("unknown-run", 999999999));
    }
}
