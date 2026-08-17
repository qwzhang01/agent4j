package io.github.qwzhang01.agent.workflow;

import io.github.qwzhang01.agent.workflow.nodes.ActionNode;
import io.github.qwzhang01.agent.workflow.nodes.JoinPolicy;
import io.github.qwzhang01.agent.workflow.nodes.ParallelNode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M5.4 - control flow: node retry, onError edges, parallel fork-join.
 */
class ControlFlowTest {

    // ============ Retry ============

    @Test
    void retryPolicyRecoversFlakyNode() {
        FlakyNode flaky = new FlakyNode("flaky", 2);

        Workflow wf = Workflow.builder("retry")
                .node(flaky, RetryPolicy.fixed(2, 1))
                .edge(Workflow.START, "flaky")
                .edge("flaky", Workflow.END)
                .build();

        ExecutionResult result = new GraphRuntime().run(wf, "in");

        assertTrue(result.isSucceeded());
        assertEquals("recovered", result.output());
        assertEquals(3, flaky.calls());
        // Trace records total attempts
        assertEquals(3, result.trace().get(0).attempts());
    }

    @Test
    void exhaustedRetriesFailTheWorkflow() {
        Workflow wf = Workflow.builder("no-retry")
                .node(new FlakyNode("boom", Integer.MAX_VALUE), RetryPolicy.fixed(1, 1))
                .edge(Workflow.START, "boom")
                .edge("boom", Workflow.END)
                .build();

        ExecutionResult result = new GraphRuntime().run(wf, "in");

        assertFalse(result.isSucceeded());
        assertTrue(result.errorMessage().contains("failed after 2 attempt(s)"));
        assertEquals(StepRecord.Status.FAILED, result.trace().get(0).status());
    }

    @Test
    void onErrorEdgeRoutesFailureToRecoveryNode() {
        Workflow wf = Workflow.builder("error-route")
                .node(new FlakyNode("boom", Integer.MAX_VALUE))
                .node(ActionNode.of("recover", ctx -> "recovered via " + ctx.input()))
                .edge(Workflow.START, "boom")
                .edge("boom", Workflow.END)
                .onError("boom", "recover")
                .edge("recover", Workflow.END)
                .build();

        ExecutionResult result = new GraphRuntime().run(wf, "in");

        assertTrue(result.isSucceeded());
        assertEquals("recovered via flaky failure #1", result.output());
        // Trace: boom FAILED, recover SUCCESS
        assertEquals(StepRecord.Status.FAILED, result.trace().get(0).status());
        assertEquals("recover", result.trace().get(1).nodeId());
    }

    // ============ onError edges ============

    @Test
    void parallelAllOfJoinsEveryBranch() {
        ParallelNode fanout = ParallelNode.builder("fanout")
                .branch("left", ActionNode.of("leftStep", ctx -> {
                    Thread.sleep(100);
                    return "L";
                }))
                .branch("right", ActionNode.of("rightStep", ctx -> {
                    Thread.sleep(100);
                    return "R";
                }))
                .join(JoinPolicy.ALL_OF)
                .build();

        Workflow wf = Workflow.builder("parallel")
                .node(fanout)
                .edge(Workflow.START, "fanout")
                .edge("fanout", Workflow.END)
                .build();

        long start = System.currentTimeMillis();
        ExecutionResult result = new GraphRuntime().run(wf, "in");
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(result.isSucceeded());
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) result.output();
        assertEquals("L", out.get("left"));
        assertEquals("R", out.get("right"));

        // Branch nodes wrote to the blackboard
        assertEquals("L", result.state().get("leftStep"));
        assertEquals("R", result.state().get("rightStep"));

        // 2 x 100ms branches ran concurrently: must be faster than sequential
        assertTrue(elapsed < 190, "branches did not run in parallel (elapsed=" + elapsed + "ms)");
    }

    // ============ Parallel ============

    @Test
    void parallelAnyOfTakesFirstFinished() {
        ParallelNode race = ParallelNode.builder("race")
                .branch("slow", ActionNode.of("slowStep", ctx -> {
                    Thread.sleep(200);
                    return "SLOW";
                }))
                .branch("fast", ActionNode.of("fastStep", ctx -> {
                    Thread.sleep(10);
                    return "FAST";
                }))
                .join(JoinPolicy.ANY_OF)
                .build();

        Workflow wf = Workflow.builder("race-flow")
                .node(race)
                .edge(Workflow.START, "race")
                .edge("race", Workflow.END)
                .build();

        ExecutionResult result = new GraphRuntime().run(wf, "in");

        assertTrue(result.isSucceeded());
        assertEquals("FAST", result.output());
    }

    @Test
    void failingBranchFailsTheParallelNode() {
        ParallelNode fanout = ParallelNode.builder("fanout")
                .branch("ok", ActionNode.of("okStep", ctx -> "fine"))
                .branch("bad", ActionNode.of("badStep", ctx -> {
                    throw new IllegalStateException("branch exploded");
                }))
                .join(JoinPolicy.ALL_OF)
                .build();

        Workflow wf = Workflow.builder("parallel-fail")
                .node(fanout)
                .edge(Workflow.START, "fanout")
                .edge("fanout", Workflow.END)
                .build();

        ExecutionResult result = new GraphRuntime().run(wf, "in");

        assertFalse(result.isSucceeded());
        assertTrue(result.errorMessage().contains("branch exploded"));
    }

    /**
     * Fails the first N executions, then succeeds.
     */
    private static final class FlakyNode implements WorkflowNode {
        private final AtomicInteger calls = new AtomicInteger();
        private final int failFirstN;
        private final String id;

        FlakyNode(String id, int failFirstN) {
            this.id = id;
            this.failFirstN = failFirstN;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public NodeResult execute(NodeContext ctx) {
            if (calls.incrementAndGet() <= failFirstN) {
                throw new IllegalStateException("flaky failure #" + calls.get());
            }
            return NodeResult.of("recovered");
        }

        int calls() {
            return calls.get();
        }
    }
}
