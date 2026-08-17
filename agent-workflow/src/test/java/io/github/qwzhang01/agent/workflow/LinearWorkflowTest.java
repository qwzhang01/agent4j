package io.github.qwzhang01.agent.workflow;

import io.github.qwzhang01.agent.workflow.nodes.ActionNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M5.1 - skeleton + linear execution.
 */
class LinearWorkflowTest {

    @Test
    void linearChainPassesOutputThroughNodes() {
        Workflow wf = Workflow.builder("linear")
                .node(ActionNode.of("a", ctx -> "A(" + ctx.input() + ")"))
                .node(ActionNode.of("b", ctx -> "B(" + ctx.input() + ")"))
                .node(ActionNode.of("c", ctx -> "C(" + ctx.input() + ")"))
                .edge(Workflow.START, "a")
                .edge("a", "b")
                .edge("b", "c")
                .edge("c", Workflow.END)
                .build();

        ExecutionResult result = new GraphRuntime().run(wf, WorkflowState.of("in"));

        assertTrue(result.isSucceeded());
        assertEquals("C(B(A(in)))", result.output());

        // Blackboard holds each node output under its id
        assertEquals("A(in)", result.state().get("a"));
        assertEquals("B(A(in))", result.state().get("b"));
        assertEquals("C(B(A(in)))", result.state().get("c"));
    }

    @Test
    void traceRecordsEveryStep() {
        Workflow wf = Workflow.builder("trace")
                .node(ActionNode.of("x", ctx -> "1"))
                .node(ActionNode.of("y", ctx -> "2"))
                .edge(Workflow.START, "x")
                .edge("x", "y")
                .edge("y", Workflow.END)
                .build();

        ExecutionResult result = new GraphRuntime().run(wf, "q");

        List<StepRecord> trace = result.trace();
        assertEquals(2, trace.size());
        assertEquals("x", trace.get(0).nodeId());
        assertEquals("y", trace.get(1).nodeId());
        assertTrue(trace.stream().allMatch(r -> r.status() == StepRecord.Status.SUCCESS));
        assertEquals(1, trace.get(0).attempts());
    }

    @Test
    void workflowIsImmutableAndReusable() {
        Workflow wf = Workflow.builder("reuse")
                .node(ActionNode.of("n", ctx -> "out:" + ctx.input()))
                .edge(Workflow.START, "n")
                .edge("n", Workflow.END)
                .build();

        ExecutionResult first = new GraphRuntime().run(wf, "run1");
        ExecutionResult second = new GraphRuntime().run(wf, "run2");

        assertTrue(first.isSucceeded());
        assertTrue(second.isSucceeded());
        assertEquals("out:run1", first.output());
        assertEquals("out:run2", second.output());
        assertEquals(1, first.trace().size());
        assertEquals(1, second.trace().size());
    }

    @Test
    void builderRejectsInvalidDefinitions() {
        // Duplicate node id (throws immediately on the second node() call)
        assertThrows(WorkflowException.class, () -> Workflow.builder("dup")
                .node(ActionNode.of("n", ctx -> "x"))
                .node(ActionNode.of("n", ctx -> "x")));

        // Edge to unknown node
        var b2 = Workflow.builder("unknown")
                .node(ActionNode.of("n", ctx -> "x"))
                .edge(Workflow.START, "n")
                .edge("n", "ghost");
        assertThrows(WorkflowException.class, b2::build);

        // No START edge
        var b3 = Workflow.builder("nostart")
                .node(ActionNode.of("n", ctx -> "x"))
                .edge("n", Workflow.END);
        assertThrows(WorkflowException.class, b3::build);

        // Node without outgoing edge (build-time dead end)
        var b4 = Workflow.builder("dangling")
                .node(ActionNode.of("a", ctx -> "x"))
                .node(ActionNode.of("b", ctx -> "x"))
                .edge(Workflow.START, "a")
                .edge("a", Workflow.END);
        assertThrows(WorkflowException.class, b4::build);
    }

    @Test
    void explicitJumpOverridesEdges() {
        Workflow wf = Workflow.builder("jump")
                // Node "a" jumps to "b" explicitly, bypassing the declared edge
                .node(ActionNode.of("a", ctx -> NodeResult.jump("b", "from-a")))
                .node(ActionNode.of("skipped", ctx -> "never"))
                .node(ActionNode.of("b", ctx -> "b-saw:" + ctx.input()))
                .edge(Workflow.START, "a")
                .edge("a", "skipped")  // declared path, but a jumps instead
                .edge("skipped", "b")
                .edge("b", Workflow.END)
                .build();

        ExecutionResult result = new GraphRuntime().run(wf, "q");
        assertTrue(result.isSucceeded());
        assertEquals("b-saw:from-a", result.output());
        // skipped node was never executed
        assertNull(result.state().get("skipped"));
    }
}
