package io.github.qwzhang01.agent.observability.metrics;

import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.run.RunContext;
import io.github.qwzhang01.agent.core.tool.ToolExecutor;
import io.github.qwzhang01.agent.observability.cost.BudgetDimension;
import io.github.qwzhang01.agent.observability.cost.BudgetBook;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 7.2 acceptance for {@link BudgetedToolExecutor}: the tool boundary
 * enforces the RUN dimension's tool-call circuit breaker - denial surfaces
 * as {@code [DENIED] ...} text the model can relay, never an exception.
 */
class BudgetedToolExecutorTest {

    @Test
    void eachAllowedCallConsumesOneUnitOfTheRunToolBudget() {
        BudgetBook book = BudgetBook.builder()
                .budget(BudgetDimension.RUN, "run-tools", 5)
                .build();
        CountingExecutor delegate = new CountingExecutor();
        BudgetedToolExecutor executor = BudgetedToolExecutor.wrap(delegate, book, "run-tools");

        executor.execute(call("t1"), ctx());
        executor.execute(call("t2"), ctx());

        assertEquals(2, delegate.executions.get());
        assertEquals(2, book.usedOf(BudgetDimension.RUN, "run-tools"),
                "one tool call = 1 unit on the RUN axis");
    }

    @Test
    void exhaustionDeniesWithTextPrefixInsteadOfThrowing() {
        BudgetBook book = BudgetBook.builder()
                .budget(BudgetDimension.RUN, "run-tools", 1)
                .build();
        CountingExecutor delegate = new CountingExecutor();
        BudgetedToolExecutor executor = BudgetedToolExecutor.wrap(delegate, book, "run-tools");

        String first = executor.execute(call("t1"), ctx());
        assertEquals("echo:t1", first);

        String second = executor.execute(call("t2"), ctx());
        assertTrue(second.startsWith("[DENIED] "), "denial must carry the governance prefix: " + second);
        assertTrue(second.contains("budget exhausted"), second);
        assertEquals(1, delegate.executions.get(), "denied call must not reach the delegate");
        assertEquals(1, book.usedOf(BudgetDimension.RUN, "run-tools"),
                "a denied call consumes nothing");
    }

    @Test
    void legacySingleArgCallSkipsTheGate() {
        BudgetBook book = BudgetBook.builder()
                .budget(BudgetDimension.RUN, "run-tools", 1)
                .build();
        book.recordUsage(BudgetDimension.RUN, "run-tools", 1);  // budget already drained

        CountingExecutor delegate = new CountingExecutor();
        BudgetedToolExecutor executor = BudgetedToolExecutor.wrap(delegate, book, "run-tools");

        // legacy path: no ctx = no gate (would have denied above)
        assertEquals("echo:t1", executor.execute(call("t1")));
        assertEquals(1, delegate.executions.get());
    }

    @Test
    void nullContextDelegatesWithoutGate() {
        BudgetBook book = BudgetBook.builder()
                .budget(BudgetDimension.RUN, "run-tools", 1)
                .build();
        book.recordUsage(BudgetDimension.RUN, "run-tools", 1);

        CountingExecutor delegate = new CountingExecutor();
        BudgetedToolExecutor executor = BudgetedToolExecutor.wrap(delegate, book, "run-tools");

        assertEquals("echo:t1", executor.execute(call("t1"), null));
        assertEquals(1, delegate.executions.get());
    }

    // ============ Helpers ============

    private static ToolCall call(String id) {
        return ToolCall.of(id, "search", "{}");
    }

    private static RunContext ctx() {
        return RunContext.builder()
                .tenantId("acme")
                .userId("alice")
                .agentId("support")
                .runId("run-1")
                .build();
    }

    private static final class CountingExecutor implements ToolExecutor {
        final AtomicInteger executions = new AtomicInteger();

        @Override
        public String execute(ToolCall toolCall) {
            executions.incrementAndGet();
            return "echo:" + toolCall.id();
        }
    }
}
