package io.github.qwzhang01.agent.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 11 M11.2 tests: the two default aggregation strategies.
 */
class ResultAggregatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static WorkerResult ok(String worker, String output) {
        WorkerTask task = WorkerTask.of(worker, "t", "p");
        return WorkerResult.success(task, output, 1, 1, 0);
    }

    private static WorkerResult fail(String worker, String error) {
        WorkerTask task = WorkerTask.of(worker, "t", "p");
        return WorkerResult.failure(task, error, 1, 1);
    }

    // ============ ConcatAggregator ============

    @Test
    void concat_mixedResults_marksFailuresInline() {
        String aggregated = new ConcatAggregator().aggregate(List.of(
                ok("researcher", "found 3 libraries"),
                fail("reviewer", "timeout"),
                ok("executor", "wrote demo")));

        assertTrue(aggregated.contains("[researcher] found 3 libraries"));
        assertTrue(aggregated.contains("[reviewer] FAILED: timeout"));
        assertTrue(aggregated.contains("[executor] wrote demo"));
        // three blocks separated by blank lines
        assertEquals(2, aggregated.split("\n\n").length - 1);
    }

    @Test
    void concat_emptyInput_returnsEmptyString() {
        assertEquals("", new ConcatAggregator().aggregate(List.of()));
        assertEquals("", new ConcatAggregator().aggregate(null));
    }

    // ============ FirstSuccessAggregator ============

    @Test
    void firstSuccess_returnsFirstSuccessfulInTaskOrder() {
        String aggregated = new FirstSuccessAggregator().aggregate(List.of(
                fail("mirror-a", "down"),
                ok("mirror-b", "answer from b"),
                ok("mirror-c", "answer from c")));

        // "first" = first successful in dispatch order, not first to finish
        assertEquals("answer from b", aggregated);
    }

    @Test
    void firstSuccess_allFailed_returnsNoSuccessMarker() {
        String aggregated = new FirstSuccessAggregator().aggregate(List.of(
                fail("a", "x"),
                fail("b", "y")));

        assertEquals(FirstSuccessAggregator.NO_SUCCESS, aggregated);
    }

    @Test
    void firstSuccess_emptyInput_returnsEmptyString() {
        assertEquals("", new FirstSuccessAggregator().aggregate(List.of()));
    }
}
