package io.github.qwzhang01.agent.orchestrator;

import io.github.qwzhang01.agent.mcp.a2a.AgentCard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 11 M11.2 tests: parallel dispatch + aggregation + supervisor bookkeeping.
 */
class AgentSupervisorTest {

    // ============ Test workers ============

    private static AgentCard testCard(String name) {
        return new AgentCard(name, "test " + name, List.of(), "internal:" + name, "1.0");
    }

    /** A worker that sleeps before answering -- for parallelism timing tests. */
    private static AgentWorker sleepyWorker(String name, long sleepMs, String output) {
        return new AgentWorker() {
            @Override public String name() { return name; }
            @Override public AgentCard card() { return testCard(name); }
            @Override public WorkerResult execute(WorkerTask task) {
                long start = System.currentTimeMillis();
                try {
                    Thread.sleep(sleepMs);
                    return WorkerResult.success(task, output,
                            System.currentTimeMillis() - start, 1, 0);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return WorkerResult.failure(task, "interrupted",
                            System.currentTimeMillis() - start, 1);
                }
            }
        };
    }

    /** A worker that always fails. */
    private static AgentWorker failingWorker(String name, String error) {
        return new AgentWorker() {
            @Override public String name() { return name; }
            @Override public AgentCard card() { return testCard(name); }
            @Override public WorkerResult execute(WorkerTask task) {
                return WorkerResult.failure(task, error, 0, 1);
            }
        };
    }

    // ============ Parallelism: the whole point of M11.2 ============

    @Test
    void dispatchAll_runsInParallel_totalTimeApproachesMaxNotSum() {
        try (AgentSupervisor supervisor = new AgentSupervisor()) {
            supervisor.register(sleepyWorker("slow", 300, "slow done"));
            supervisor.register(sleepyWorker("medium", 200, "medium done"));
            supervisor.register(sleepyWorker("fast", 100, "fast done"));

            List<WorkerTask> tasks = List.of(
                    WorkerTask.of("slow", "t", "x"),
                    WorkerTask.of("medium", "t", "x"),
                    WorkerTask.of("fast", "t", "x"));

            SupervisorResult result = supervisor.dispatchAll(tasks, new ConcatAggregator());

            assertTrue(result.allSucceeded());
            // sum of sleeps = 600ms; parallel max = 300ms; allow scheduling slack.
            assertTrue(result.durationMs() >= 280,
                    "should take at least the slowest task, was " + result.durationMs());
            assertTrue(result.durationMs() < 550,
                    "should be clearly faster than the sum (600ms), was " + result.durationMs());
        }
    }

    // ============ Result ordering & bookkeeping ============

    @Test
    void dispatchAll_resultsFollowTaskOrder() {
        try (AgentSupervisor supervisor = new AgentSupervisor()) {
            supervisor.register(sleepyWorker("a", 50, "A"));
            supervisor.register(sleepyWorker("b", 0, "B"));

            List<WorkerTask> tasks = List.of(
                    WorkerTask.of("b", "t", "x"),   // dispatch b first
                    WorkerTask.of("a", "t", "x"));

            SupervisorResult result = supervisor.dispatchAll(tasks, new ConcatAggregator());

            assertEquals(2, result.results().size());
            assertEquals("b", result.results().get(0).workerName());  // not "a" despite finishing later
            assertEquals("a", result.results().get(1).workerName());
        }
    }

    @Test
    void dispatchAll_mixedSuccessAndFailure_countsAndAggregatesBoth() {
        try (AgentSupervisor supervisor = new AgentSupervisor()) {
            supervisor.register(sleepyWorker("good1", 0, "first output"));
            supervisor.register(failingWorker("bad", "connection refused"));
            supervisor.register(sleepyWorker("good2", 0, "second output"));

            List<WorkerTask> tasks = List.of(
                    WorkerTask.of("good1", "t", "x"),
                    WorkerTask.of("bad", "t", "x"),
                    WorkerTask.of("good2", "t", "x"));

            SupervisorResult result = supervisor.dispatchAll(tasks, new ConcatAggregator());

            assertFalse(result.allSucceeded());
            assertEquals(3, result.totalTasks());
            assertEquals(2, result.succeeded());
            assertEquals(1, result.failed());
            // aggregation includes successes AND the failure marker
            assertTrue(result.aggregated().contains("first output"));
            assertTrue(result.aggregated().contains("second output"));
            assertTrue(result.aggregated().contains("FAILED"));
            assertTrue(result.aggregated().contains("connection refused"));
        }
    }

    // ============ Failure-as-data contract ============

    @Test
    void dispatchAll_unknownWorker_failsAsDataNeverThrows() {
        try (AgentSupervisor supervisor = new AgentSupervisor()) {
            supervisor.register(sleepyWorker("real", 0, "ok"));

            SupervisorResult result = supervisor.dispatchAll(
                    List.of(WorkerTask.of("ghost", "t", "x")),
                    new ConcatAggregator());

            assertFalse(result.allSucceeded());
            assertEquals(1, result.failed());
            assertTrue(result.results().get(0).error().contains("unknown worker"));
            assertTrue(result.results().get(0).error().contains("ghost"));
        }
    }

    @Test
    void dispatchAll_emptyTaskList_returnsEmptySuccess() {
        try (AgentSupervisor supervisor = new AgentSupervisor()) {
            SupervisorResult result = supervisor.dispatchAll(List.of(), new ConcatAggregator());

            assertTrue(result.allSucceeded());  // vacuously true: nothing failed
            assertEquals(0, result.totalTasks());
            assertEquals("", result.aggregated());
        }
    }

    // ============ Worker pool ============

    @Test
    void register_duplicateName_throws() {
        try (AgentSupervisor supervisor = new AgentSupervisor()) {
            supervisor.register(sleepyWorker("dup", 0, "x"));
            assertThrows(IllegalArgumentException.class,
                    () -> supervisor.register(sleepyWorker("dup", 0, "y")));
        }
    }

    @Test
    void discoverWorkers_listsAllCards() {
        try (AgentSupervisor supervisor = new AgentSupervisor()) {
            supervisor.register(sleepyWorker("w1", 0, "x"));
            supervisor.register(sleepyWorker("w2", 0, "x"));

            List<AgentCard> cards = supervisor.discoverWorkers();

            assertEquals(2, cards.size());
            assertEquals(2, supervisor.workerCount());
            assertNotNull(supervisor.getWorker("w1"));
            assertNull(supervisor.getWorker("nobody"));
        }
    }

    @Test
    void injectedExecutor_isNotShutDownByClose() {
        java.util.concurrent.ExecutorService injected =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try (AgentSupervisor supervisor = new AgentSupervisor(injected)) {
            supervisor.register(sleepyWorker("w", 0, "x"));
            SupervisorResult r = supervisor.dispatchAll(
                    List.of(WorkerTask.of("w", "t", "x")), new ConcatAggregator());
            assertTrue(r.allSucceeded());
        }
        // caller owns the lifecycle: still usable after close()
        assertTrue(!injected.isShutdown());
        injected.shutdown();
    }
}
