package io.github.qwzhang01.agent.workflow.plan;

import io.github.qwzhang01.agent.workflow.ExecutionResult;
import io.github.qwzhang01.agent.workflow.GraphRuntime;
import io.github.qwzhang01.agent.workflow.Workflow;
import io.github.qwzhang01.agent.workflow.WorkflowState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 9 Planner/Executor integration tests: a Plan lowers to the
 * EXISTING workflow runtime — no new runtime, dependency order enforced
 * by the graph, resume frontier computed from the blackboard.
 */
class PlanExecutorTest {

    // ============ Lowering & execution ============

    @Test
    @DisplayName("plan lowers to a runtime workflow: chain runs in topological declaration order")
    void diamondPlanExecutesOnExistingRuntime() {
        Plan plan = Plan.of("diamond", List.of(
                Plan.Step.of("gather", "collect"),
                Plan.Step.of("left", "transform L"),
                Plan.Step.of("right", "transform R"),
                new Plan.Step("join", "merge", List.of("left", "right"))));

        List<String> executionOrder = new ArrayList<>();
        PlanExecutor executor = new PlanExecutor();
        Workflow wf = executor.toWorkflow(plan, (step, p) -> {
            executionOrder.add(step.id());
            return "out:" + step.id();
        });

        ExecutionResult result = new GraphRuntime().run(wf, "input");

        assertTrue(result.isSucceeded(), "plan workflow must succeed on the existing runtime");
        // Linear chain in declaration (= topological) order: gather, left,
        // right, join. Dependencies are satisfied by execution order.
        assertEquals(List.of("gather", "left", "right", "join"), executionOrder);
        // blackboard holds each step output under its step id
        assertEquals("out:gather", result.state().get("gather"));
        assertEquals("out:join", result.state().get("join"));
    }

    @Test
    @DisplayName("failed step fails the workflow through the runtime's own failure semantics")
    void stepFailureSurfacesAsWorkflowFailure() {
        Plan plan = Plan.of("failing", List.of(
                Plan.Step.of("ok", "fine"),
                new Plan.Step("boom", "explode", List.of("ok"))));

        Workflow wf = new PlanExecutor().toWorkflow(plan, (step, p) -> {
            if ("boom".equals(step.id())) {
                throw new RuntimeException("step exploded");
            }
            return "ok-out";
        });

        ExecutionResult result = new GraphRuntime().run(wf, "in");

        assertFalse(result.isSucceeded());
        // completed prefix is preserved on the blackboard
        assertEquals("ok-out", result.state().get("ok"));
        assertNull(result.state().get("boom"));
    }

    // ============ Resume frontier from blackboard ============

    @Test
    @DisplayName("completedFrom: a step is done when the blackboard holds its output")
    void completedFromBlackboard() {
        Plan plan = Plan.of("resume", List.of(
                Plan.Step.of("a", "first"),
                new Plan.Step("b", "second", List.of("a"))));

        Workflow wf = new PlanExecutor().toWorkflow(plan, (step, p) -> "v-" + step.id());
        ExecutionResult result = new GraphRuntime().run(wf, "in");

        Set<String> completed = new PlanExecutor().completedFrom(result.state(), plan);
        assertEquals(Set.of("a", "b"), completed);

        // a mid-flight blackboard (only a done) recovers the frontier
        WorkflowState partial = WorkflowState.of("in");
        partial.put("a", "v-a");
        assertEquals(Set.of("a"), new PlanExecutor().completedFrom(partial, plan));
    }

    @Test
    @DisplayName("workflow identity carries plan id and version (drift detection via runtime)")
    void workflowIdentityCarriesPlanVersion() {
        Plan plan = Plan.of("drift", List.of(Plan.Step.of("only", "step")));
        Workflow wf = new PlanExecutor().toWorkflow(plan, (s, p) -> "x");

        assertEquals("plan:drift", wf.name());
        assertEquals("v1", wf.version());
    }
}
