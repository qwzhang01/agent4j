package io.github.qwzhang01.agent.workflow;

import io.github.qwzhang01.agent.workflow.nodes.ActionNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M5.2 - conditional routing, fallback, dead end, ambiguity, cycle guard.
 */
class ConditionalRoutingTest {

    /** Router flow: intent node writes a key, edges route by it. */
    private WorkflowBuilder.EdgeSpec routerFlowBuilder() {
        return Workflow.builder("router-flow")
                .node(ActionNode.of("intent", ctx -> String.valueOf(ctx.input()).toUpperCase()))
                .node(ActionNode.of("query", ctx -> "queried:" + ctx.input()))
                .node(ActionNode.of("handoff", ctx -> "handed-off"))
                .edge(Workflow.START, "intent")
                .edge("intent", "query").when(s -> "QUERY".equals(s.get("intent")))
                .edge("intent", "handoff").otherwise()
                .edge("query", Workflow.END)
                .edge("handoff", Workflow.END);
    }

    @Test
    void routesToMatchingConditionalBranch() {
        Workflow wf = routerFlowBuilder().build();
        ExecutionResult result = new GraphRuntime().run(wf, "query");

        assertTrue(result.isSucceeded());
        assertEquals("queried:QUERY", result.output());
    }

    @Test
    void otherwiseCatchesEverythingElse() {
        Workflow wf = routerFlowBuilder().build();
        ExecutionResult result = new GraphRuntime().run(wf, "complaint");

        assertTrue(result.isSucceeded());
        assertEquals("handed-off", result.output());
    }

    @Test
    void noMatchWithoutFallbackIsDeadEnd() {
        Workflow wf = Workflow.builder("dead-end")
                .node(ActionNode.of("intent", ctx -> ctx.input()))
                .node(ActionNode.of("a", ctx -> "a"))
                .edge(Workflow.START, "intent")
                .edge("intent", "a").when(s -> "NEVER".equals(s.get("intent")))
                .edge("a", Workflow.END)
                .build();

        ExecutionResult result = new GraphRuntime().run(wf, "whatever");

        assertFalse(result.isSucceeded());
        assertTrue(result.errorMessage().contains("Dead end"));
    }

    @Test
    void multipleMatchingEdgesIsAmbiguous() {
        Workflow wf = Workflow.builder("ambiguous")
                .node(ActionNode.of("start", ctx -> "go"))
                .node(ActionNode.of("a", ctx -> "a"))
                .node(ActionNode.of("b", ctx -> "b"))
                .edge(Workflow.START, "start")
                .edge("start", "a").when(s -> true)
                .edge("start", "b").when(s -> true)
                .edge("a", Workflow.END)
                .edge("b", Workflow.END)
                .build();

        ExecutionResult result = new GraphRuntime().run(wf, "in");

        assertFalse(result.isSucceeded());
        assertTrue(result.errorMessage().contains("Ambiguous"));
    }

    @Test
    void cycleIsBrokenByMaxSteps() {
        Workflow wf = Workflow.builder("cycle")
                .node(ActionNode.of("a", ctx -> "a"))
                .node(ActionNode.of("b", ctx -> "b"))
                .edge(Workflow.START, "a")
                .edge("a", "b")
                .edge("b", "a")
                .build();

        ExecutionResult result = new GraphRuntime().maxSteps(10).run(wf, "in");

        assertFalse(result.isSucceeded());
        assertTrue(result.errorMessage().contains("Max steps"));
        // It looped exactly until the guard fired
        assertEquals(10, result.trace().size());
    }

    @Test
    void edgeWithoutWhenOrOtherwiseIsUnconditional() {
        // .edge(a, b) with neither when() nor otherwise() resolves to unconditional
        Workflow wf = Workflow.builder("implicit")
                .node(ActionNode.of("a", ctx -> "1"))
                .node(ActionNode.of("b", ctx -> "2"))
                .edge(Workflow.START, "a")
                .edge("a", "b")
                .edge("b", Workflow.END)
                .build();

        ExecutionResult result = new GraphRuntime().run(wf, "in");

        assertTrue(result.isSucceeded());
        assertEquals("2", result.output());
    }
}
