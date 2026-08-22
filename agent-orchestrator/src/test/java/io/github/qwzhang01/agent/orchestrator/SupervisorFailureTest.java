package io.github.qwzhang01.agent.orchestrator;

import io.github.qwzhang01.agent.mcp.a2a.AgentCard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 11 M11.3 tests: retry, timeout, FAIL_FAST / BEST_EFFORT semantics.
 */
class SupervisorFailureTest {

    private static final ObjectMapperShim MAPPER = new ObjectMapperShim();

    /** Minimal JSON shim: builds a task with a prompt payload. */
    static final class ObjectMapperShim {
        WorkerTask task(String worker, String prompt, long timeoutMs, int maxRetries) {
            return new WorkerTask("tid-" + COUNTER.incrementAndGet(), worker, "t",
                    null, timeoutMs, maxRetries);
        }

        private static final java.util.concurrent.atomic.AtomicLong COUNTER =
                new java.util.concurrent.atomic.AtomicLong();
    }

    // ============ Test workers ============

    private static AgentCard testCard(String name) {
        return new AgentCard(name, "test " + name, List.of(), "internal:" + name, "1.0");
    }

    /** Fails the first N calls, succeeds afterwards (transient failure). */
    private static AgentWorker flakyWorker(String name, int failFirstN,
                                           AtomicInteger calls, String output) {
        return new AgentWorker() {
            @Override public String name() { return name; }
            @Override public AgentCard card() { return testCard(name); }
            @Override public WorkerResult execute(WorkerTask task) {
                int call = calls.incrementAndGet();
                if (call <= failFirstN) {
                    return WorkerResult.failure(task, "transient failure #" + call, 1, 1);
                }
                return WorkerResult.success(task, output, 1, 1, 0);
            }
        };
    }

    /** Always fails. */
    private static AgentWorker failingWorker(String name) {
        return new AgentWorker() {
            @Override public String name() { return name; }
            @Override public AgentCard card() { return testCard(name); }
            @Override public WorkerResult execute(WorkerTask task) {
                return WorkerResult.failure(task, "permanent failure", 1, 1);
            }
        };
    }

    /** Sleeps before answering -- timeout target. */
    private static AgentWorker slowWorker(String name, long sleepMs) {
        return new AgentWorker() {
            @Override public String name() { return name; }
            @Override public AgentCard card() { return testCard(name); }
            @Override public WorkerResult execute(WorkerTask task) {
                long start = System.currentTimeMillis();
                try {
                    Thread.sleep(sleepMs);
                    return WorkerResult.success(task, name + " done",
                            System.currentTimeMillis() - start, 1, 0);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return WorkerResult.failure(task, "interrupted",
                            System.currentTimeMillis() - start, 1);
                }
            }
        };
    }

    /** First call sleeps, subsequent calls answer immediately. */
    private static AgentWorker slowThenFastWorker(String name, long firstSleepMs) {
        AtomicInteger calls = new AtomicInteger();
        return new AgentWorker() {
            @Override public String name() { return name; }
            @Override public AgentCard card() { return testCard(name); }
            @Override public WorkerResult execute(WorkerTask task) {
                if (calls.incrementAndGet() == 1) {
                    try {
                        Thread.sleep(firstSleepMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return WorkerResult.failure(task, "interrupted", 1, 1);
                    }
                }
                return WorkerResult.success(task, name + " recovered", 1, 1, 0);
            }
        };
    }

    // ============ Retry ============

    @Test
    void retry_transientFailure_recoversWithAttemptCount() {
        AtomicInteger calls = new AtomicInteger();
        try (AgentSupervisor supervisor = new AgentSupervisor()) {
            supervisor.register(flakyWorker("flaky", 2, calls, "recovered!"));

            WorkerTask task = MAPPER.task("flaky", "x", 0, 2);  // 1 + 2 retries

            SupervisorResult result = supervisor.dispatchAll(
                    List.of(task), new ConcatAggregator(), FailurePolicy.bestEffort());

            assertTrue(result.allSucceeded());
            assertEquals("recovered!", result.results().get(0).output());
            assertEquals(3, result.results().get(0).attempts());  // tried 3 times
            assertEquals(3, calls.get());
        }
    }

    @Test
    void retry_exhausted_reportsFailureWithAttempts() {
        AtomicInteger calls = new AtomicInteger();
        try (AgentSupervisor supervisor = new AgentSupervisor()) {
            // failFirstN=999 -> never succeeds, but every call is counted
            supervisor.register(flakyWorker("dead", 999, calls, "unreachable"));

            WorkerTask task = MAPPER.task("dead", "x", 0, 2);

            SupervisorResult result = supervisor.dispatchAll(
                    List.of(task), new ConcatAggregator(), FailurePolicy.bestEffort());

            assertFalse(result.allSucceeded());
            assertEquals(3, result.results().get(0).attempts());
            assertEquals(3, calls.get());
            assertTrue(result.results().get(0).error().contains("transient failure"));
        }
    }

    @Test
    void retry_backoff_pausesBetweenAttempts() {
        AtomicInteger calls = new AtomicInteger();
        try (AgentSupervisor supervisor = new AgentSupervisor()) {
            supervisor.register(flakyWorker("flaky", 1, calls, "ok"));

            WorkerTask task = MAPPER.task("flaky", "x", 0, 1);  // 2 attempts total
            long start = System.currentTimeMillis();

            SupervisorResult result = supervisor.dispatchAll(
                    List.of(task), new ConcatAggregator(), FailurePolicy.bestEffort(150));

            assertTrue(result.allSucceeded());
            assertTrue(result.durationMs() >= 140,
                    "backoff (150ms) should delay the retry, took " + result.durationMs());
        }
    }

    @Test
    void retry_zeroBudget_failsImmediately() {
        AtomicInteger calls = new AtomicInteger();
        try (AgentSupervisor supervisor = new AgentSupervisor()) {
            supervisor.register(flakyWorker("flaky", 1, calls, "never reached"));

            WorkerTask task = MAPPER.task("flaky", "x", 0, 0);  // no retry

            SupervisorResult result = supervisor.dispatchAll(
                    List.of(task), new ConcatAggregator(), FailurePolicy.bestEffort());

            assertFalse(result.allSucceeded());
            assertEquals(1, result.results().get(0).attempts());
            assertEquals(1, calls.get());  // worker called exactly once
        }
    }

    // ============ Timeout ============

    @Test
    void timeout_slowWorker_failsFastWithTimeoutError() {
        try (AgentSupervisor supervisor = new AgentSupervisor()) {
            supervisor.register(slowWorker("snail", 500));

            WorkerTask task = MAPPER.task("snail", "x", 100, 0);  // 100ms budget

            long start = System.currentTimeMillis();
            SupervisorResult result = supervisor.dispatchAll(
                    List.of(task), new ConcatAggregator(), FailurePolicy.bestEffort());

            assertFalse(result.allSucceeded());
            assertTrue(result.results().get(0).error().contains("timed out after 100"));
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed < 450, "should not wait for the 500ms worker, took " + elapsed);
        }
    }

    @Test
    void timeout_countsAsRetryableAttempt() {
        try (AgentSupervisor supervisor = new AgentSupervisor()) {
            supervisor.register(slowThenFastWorker("laggy", 400));  // slow first, fast later

            WorkerTask task = MAPPER.task("laggy", "x", 100, 1);  // timeout + 1 retry

            SupervisorResult result = supervisor.dispatchAll(
                    List.of(task), new ConcatAggregator(), FailurePolicy.bestEffort());

            assertTrue(result.allSucceeded());       // first attempt timed out, retry won
            assertEquals("laggy recovered", result.results().get(0).output());
            assertEquals(2, result.results().get(0).attempts());
        }
    }

    // ============ FAIL_FAST ============

    @Test
    void failFast_firstFailure_cancelsRemainingTasks() {
        try (AgentSupervisor supervisor = new AgentSupervisor()) {
            supervisor.register(failingWorker("doomed"));
            supervisor.register(slowWorker("slow1", 600));
            supervisor.register(slowWorker("slow2", 600));

            List<WorkerTask> tasks = List.of(
                    MAPPER.task("doomed", "x", 0, 0),
                    MAPPER.task("slow1", "x", 0, 0),
                    MAPPER.task("slow2", "x", 0, 0));

            long start = System.currentTimeMillis();
            SupervisorResult result = supervisor.dispatchAll(
                    tasks, new ConcatAggregator(), FailurePolicy.failFast());

            assertFalse(result.allSucceeded());
            assertEquals(3, result.failed());  // 1 real failure + 2 cancelled (all failure data)
            // the slow tasks come back as cancelled failure data, not results
            assertTrue(result.results().get(1).error().contains("cancelled"));
            assertTrue(result.results().get(2).error().contains("cancelled"));
            // and we did NOT wait 600ms for them
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed < 450, "cancelled tasks should not be awaited, took " + elapsed);
        }
    }

    @Test
    void failFast_allSucceed_behavesNormally() {
        try (AgentSupervisor supervisor = new AgentSupervisor()) {
            supervisor.register(slowWorker("ok1", 50));
            supervisor.register(slowWorker("ok2", 50));

            SupervisorResult result = supervisor.dispatchAll(
                    List.of(MAPPER.task("ok1", "x", 0, 0), MAPPER.task("ok2", "x", 0, 0)),
                    new ConcatAggregator(), FailurePolicy.failFast());

            assertTrue(result.allSucceeded());  // happy path unaffected
        }
    }

    // ============ BEST_EFFORT isolation ============

    @Test
    void bestEffort_failureDoesNotAffectOthers() {
        try (AgentSupervisor supervisor = new AgentSupervisor()) {
            supervisor.register(failingWorker("bad"));
            supervisor.register(slowWorker("good1", 30));
            supervisor.register(slowWorker("good2", 30));

            SupervisorResult result = supervisor.dispatchAll(
                    List.of(MAPPER.task("bad", "x", 0, 0),
                            MAPPER.task("good1", "x", 0, 0),
                            MAPPER.task("good2", "x", 0, 0)),
                    new ConcatAggregator(), FailurePolicy.bestEffort());

            assertFalse(result.allSucceeded());
            assertEquals(2, result.succeeded());
            assertEquals(1, result.failed());
            assertTrue(result.aggregated().contains("good1 done"));
            assertTrue(result.aggregated().contains("good2 done"));
        }
    }

    // ============ Policy factory sanity ============

    @Test
    void failurePolicy_factories_andValidation() {
        assertEquals(FailurePolicy.Mode.FAIL_FAST, FailurePolicy.failFast().mode());
        assertEquals(0, FailurePolicy.failFast().retryBackoffMs());
        assertEquals(FailurePolicy.Mode.BEST_EFFORT, FailurePolicy.bestEffort().mode());
        assertEquals(250, FailurePolicy.bestEffort(250).retryBackoffMs());
        assertThrows(IllegalArgumentException.class,
                () -> new FailurePolicy(FailurePolicy.Mode.BEST_EFFORT, -1));
    }
}
