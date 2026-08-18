package io.github.qwzhang01.agent.scheduler;

import io.github.qwzhang01.agent.workflow.ExecutionResult;
import io.github.qwzhang01.agent.workflow.GraphRuntime;
import io.github.qwzhang01.agent.workflow.Workflow;
import io.github.qwzhang01.agent.workflow.nodes.ActionNode;
import io.github.qwzhang01.agent.workflow.runtime.RunManager;
import io.github.qwzhang01.agent.workflow.runtime.RunState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

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

        awaitRunStatus(runManager, r1.resumeToken().runId(), RunState.SUCCEEDED, 2000);
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
        awaitRunStatus(runManager, r1.resumeToken().runId(), RunState.SUCCEEDED, 2000);
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
        awaitRunStatus(runManager, r1.resumeToken().runId(), RunState.SUCCEEDED, 2000);
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
        awaitRunStatus(runManager, r1.resumeToken().runId(), RunState.SUCCEEDED, 2000);
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
        awaitRunStatus(runManager, pid, RunState.SUCCEEDED, 2000);

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
        queue.enqueue(AsyncTask.of("run-1", "high-task", TaskPriority.HIGH, "wf"));
        queue.enqueue(AsyncTask.of("run-1", "urgent-task", TaskPriority.URGENT, "wf"));

        assertEquals(TaskPriority.URGENT, queue.pollNext().priority());
        assertEquals(TaskPriority.HIGH, queue.pollNext().priority());
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

    @Test
    void eventTimeoutFailsRun() throws Exception {
        Workflow wf = Workflow.builder("timeout-flow")
                .node(io.github.qwzhang01.agent.scheduler.nodes.WaitEventNode.of(
                        "wait", "never-arrives", Duration.ofMillis(120)))
                .node(ActionNode.of("after", ctx -> "should-not-run"))
                .edge(Workflow.START, "wait")
                .edge("wait", "after")
                .edge("after", Workflow.END)
                .build();

        ExecutionResult r1 = runManager.start(wf, "input");
        assertTrue(r1.isPaused());
        String runId = r1.resumeToken().runId();

        awaitRunStatus(runManager, runId, RunState.FAILED, 2000);
        var run = runManager.getRun(runId);
        assertTrue(run.getErrorMessage().contains("timed out"),
                "expected timeout error, got: " + run.getErrorMessage());
        assertNull(run.getState().get("after"));
    }

    @Test
    void manualResumeBeforeEventRePauses() {
        Workflow wf = Workflow.builder("repause-flow")
                .node(io.github.qwzhang01.agent.scheduler.nodes.WaitEventNode.of(
                        "wait", "later", Duration.ofSeconds(30)))
                .edge(Workflow.START, "wait")
                .edge("wait", Workflow.END)
                .build();

        ExecutionResult r1 = runManager.start(wf, "input");
        assertTrue(r1.isPaused());
        ExecutionResult r2 = runManager.resume(r1.resumeToken().runId());
        assertTrue(r2.isPaused(), "manual resume before the event must re-pause, not fail as timeout");
    }

    @Test
    void exceedingTokenBudgetFailsPausedRun() {
        Workflow wf = Workflow.builder("budget-flow")
                .node(io.github.qwzhang01.agent.scheduler.nodes.WaitEventNode.of("wait", "evt"))
                .edge(Workflow.START, "wait")
                .edge("wait", Workflow.END)
                .build();

        ExecutionResult r1 = runManager.start(wf, "input");
        assertTrue(r1.isPaused());
        String runId = r1.resumeToken().runId();

        scheduler.setBudget(runId, 100);
        assertFalse(scheduler.consumeTokens(runId, 150));
        assertEquals(RunState.FAILED, runManager.getRun(runId).getStatus());
        assertEquals("token_exceeded", runManager.getRun(runId).getErrorMessage());
        assertThrows(io.github.qwzhang01.agent.workflow.WorkflowException.class,
                () -> runManager.resume(runId));
    }

    @Test
    void restorePausedRunsAfterSchedulerRestart() throws Exception {
        Workflow wf = Workflow.builder("restore-flow")
                .node(io.github.qwzhang01.agent.scheduler.nodes.ScheduleResumeNode.of(
                        "wait", Duration.ofHours(2)))
                .node(ActionNode.of("done", ctx -> "restored"))
                .edge(Workflow.START, "wait")
                .edge("wait", "done")
                .edge("done", Workflow.END)
                .build();

        ExecutionResult r1 = runManager.start(wf, "input");
        assertTrue(r1.isPaused());
        String runId = r1.resumeToken().runId();

        scheduler.shutdown();

        ScheduledExecutorService fresh = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "test-scheduler-restore");
            t.setDaemon(true);
            return t;
        });
        scheduler = new TaskScheduler(runManager, fresh);
        runManager.setRuntime(new GraphRuntime().scheduler(scheduler));
        scheduler.start();
        assertEquals(1, scheduler.restorePausedRuns(Duration.ofMillis(80)));

        awaitRunStatus(runManager, runId, RunState.SUCCEEDED, 2000);
    }

    @Test
    void concurrentPollDoesNotDuplicateTasks() throws Exception {
        AsyncTaskQueue queue = new AsyncTaskQueue();
        int n = 80;
        for (int i = 0; i < n; i++) {
            queue.enqueue(AsyncTask.of("run", "t-" + i, TaskPriority.NORMAL, "wf"));
        }

        Set<String> ids = java.util.Collections.synchronizedSet(new HashSet<>());
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger extras = new AtomicInteger();
        Thread t1 = new Thread(() -> drain(queue, ids, extras, start));
        Thread t2 = new Thread(() -> drain(queue, ids, extras, start));
        t1.start();
        t2.start();
        start.countDown();
        t1.join(2000);
        t2.join(2000);

        assertEquals(n, ids.size(), "each task must be consumed exactly once");
        assertEquals(0, extras.get());
        assertTrue(queue.isEmpty());
    }

    private static void drain(AsyncTaskQueue queue, Set<String> ids, AtomicInteger extras, CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        AsyncTask task;
        while ((task = queue.pollNext()) != null) {
            if (!ids.add(task.taskId())) {
                extras.incrementAndGet();
            }
        }
    }

    private static void awaitRunStatus(RunManager mgr, String runId, RunState expected, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            var run = mgr.getRun(runId);
            if (run != null && run.getStatus() == expected) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted waiting for " + expected);
            }
        }
        var run = mgr.getRun(runId);
        fail("timed out waiting for " + expected + ", last=" + (run == null ? "null" : run.getStatus()
                + " err=" + run.getErrorMessage()));
    }
}
