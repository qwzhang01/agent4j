package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.model.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 9 Tool Parallelism unit tests: bounded width, declaration-order
 * join, failure isolation with readable error strings.
 */
class ParallelToolExecutorTest {

    @Test
    @DisplayName("dispatchAll joins in declaration order even when completion order differs")
    void declarationOrderJoin() throws Exception {
        // first tool is slow, second fast: completion order flips, join
        // order must not.
        ParallelToolExecutor executor = new ParallelToolExecutor(
                java.util.concurrent.Executors.newFixedThreadPool(8));
        List<ParallelToolExecutor.Dispatch> dispatches = List.of(
                new ParallelToolExecutor.Dispatch(
                        ToolCall.of("c1", "slow", "{}"), call -> {
                    Thread.sleep(80);
                    return "slow result";
                }),
                new ParallelToolExecutor.Dispatch(
                        ToolCall.of("c2", "fast", "{}"), call -> "fast result"));

        long start = System.currentTimeMillis();
        List<String> results = executor.dispatchAll(dispatches);

        assertEquals(List.of("slow result", "fast result"), results,
                "results written back in declaration order");
        assertTrue(System.currentTimeMillis() - start < 150,
                "tools actually ran in parallel (not sequentially 80ms+0ms)");
    }

    @Test
    @DisplayName("maxConcurrent clamps width: surplus runs sequentially after a slot frees")
    void widthClamped() throws Exception {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrentSeen = new AtomicInteger();
        ParallelToolExecutor executor = new ParallelToolExecutor(
                java.util.concurrent.Executors.newFixedThreadPool(8), 2);

        List<ParallelToolExecutor.Dispatch> dispatches = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            dispatches.add(new ParallelToolExecutor.Dispatch(
                    ToolCall.of("c" + i, "t", "{}"), call -> {
                int now = concurrent.incrementAndGet();
                maxConcurrentSeen.accumulateAndGet(now, Math::max);
                Thread.sleep(30);
                concurrent.decrementAndGet();
                return "r" + call.id();
            }));
        }

        List<String> results = executor.dispatchAll(dispatches);

        assertEquals(6, results.size());
        assertTrue(results.get(0).startsWith("rc0"), "join keyed back by declaration index");
        assertTrue(maxConcurrentSeen.get() <= 2,
                "width clamped at maxConcurrent=2, saw " + maxConcurrentSeen.get());
    }

    @Test
    @DisplayName("one tool failing converts to a readable error string; siblings keep results")
    void failureBecomesReadableErrorString() throws Exception {
        ParallelToolExecutor executor = new ParallelToolExecutor(
                java.util.concurrent.Executors.newFixedThreadPool(8));
        List<ParallelToolExecutor.Dispatch> dispatches = List.of(
                new ParallelToolExecutor.Dispatch(
                        ToolCall.of("c1", "good", "{}"), call -> "ok"),
                new ParallelToolExecutor.Dispatch(
                        ToolCall.of("c2", "bad", "{}"), call -> {
                    throw new IllegalStateException("db unreachable");
                }),
                new ParallelToolExecutor.Dispatch(
                        ToolCall.of("c3", "alsoGood", "{}"), call -> "fine"));

        List<String> results = executor.dispatchAll(dispatches);

        assertEquals("ok", results.get(0));
        assertEquals("[ERROR] tool 'bad' failed: db unreachable", results.get(1),
                "failure surfaces as a model-readable error string");
        assertEquals("fine", results.get(2));
    }

    @Test
    @DisplayName("independent tools genuinely overlap (latch rendezvous)")
    void toolsGenuinelyOverlap() throws Exception {
        // Two tools that each wait for the other to START: only genuine
        // parallelism completes; sequential execution would deadlock on
        // the latches (with timeouts so the test can never hang forever).
        CountDownLatch bothStarted = new CountDownLatch(2);
        ParallelToolExecutor executor = new ParallelToolExecutor(
                java.util.concurrent.Executors.newFixedThreadPool(8));

        List<ParallelToolExecutor.Dispatch> dispatches = List.of(
                new ParallelToolExecutor.Dispatch(
                        ToolCall.of("c1", "a", "{}"), call -> {
                    bothStarted.countDown();
                    if (!bothStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        return "sibling never started (sequential?)";
                    }
                    return "a-ok";
                }),
                new ParallelToolExecutor.Dispatch(
                        ToolCall.of("c2", "b", "{}"), call -> {
                    bothStarted.countDown();
                    if (!bothStarted.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        return "sibling never started (sequential?)";
                    }
                    return "b-ok";
                }));

        List<String> results = executor.dispatchAll(dispatches);

        assertEquals(List.of("a-ok", "b-ok"), results,
                "both tools must overlap; sequential fallback would time out");
    }

    @Test
    @DisplayName("joinUntil turns unfinished calls into TIMEOUT when the deadline fires")
    void joinHonorsDeadline() {
        ParallelToolExecutor executor = new ParallelToolExecutor(
                java.util.concurrent.Executors.newFixedThreadPool(8));
        java.time.Instant deadline = java.time.Instant.now().plusMillis(40);
        List<ParallelToolExecutor.Dispatch> dispatches = List.of(
                new ParallelToolExecutor.Dispatch(
                        ToolCall.of("c1", "slow", "{}"), call -> {
                    Thread.sleep(400);
                    return "too late";
                }),
                new ParallelToolExecutor.Dispatch(
                        ToolCall.of("c2", "alsoSlow", "{}"), call -> {
                    Thread.sleep(400);
                    return "also too late";
                }));

        List<String> results = executor.dispatchAll(dispatches, deadline);

        assertEquals(2, results.size());
        assertTrue(results.get(0).startsWith("[TIMEOUT]"), results.get(0));
        assertTrue(results.get(1).startsWith("[TIMEOUT]"), results.get(1));
        assertTrue(results.get(0).contains("slow"));
        assertTrue(results.get(1).contains("alsoSlow"));
    }

    @Test
    @DisplayName("a deadline already in the past skips dispatch entirely")
    void pastDeadlineSkipsDispatch() {
        AtomicInteger started = new AtomicInteger();
        ParallelToolExecutor executor = new ParallelToolExecutor(
                java.util.concurrent.Executors.newFixedThreadPool(2));
        List<ParallelToolExecutor.Dispatch> dispatches = List.of(
                new ParallelToolExecutor.Dispatch(
                        ToolCall.of("c1", "never", "{}"), call -> {
                    started.incrementAndGet();
                    return "ran";
                }));

        List<String> results = executor.dispatchAll(
                dispatches, java.time.Instant.now().minusSeconds(1));

        assertEquals(0, started.get(), "must not start work after the deadline");
        assertTrue(results.get(0).startsWith("[TIMEOUT]"));
    }
}
