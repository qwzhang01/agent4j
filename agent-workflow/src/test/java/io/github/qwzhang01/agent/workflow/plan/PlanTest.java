package io.github.qwzhang01.agent.workflow.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 9 Planner contract tests: structural validation, versioning,
 * resume frontier.
 */
class PlanTest {

    @Test
    @DisplayName("valid plan builds; declaration order is preserved")
    void validPlanBuilds() {
        Plan plan = Plan.of("p1", List.of(
                Plan.Step.of("gather", "collect inputs"),
                Plan.Step.of("analyze", "analyze data"),
                new Plan.Step("report", "write report", List.of("gather", "analyze"))));

        assertEquals("p1", plan.planId());
        assertEquals(3, plan.steps().size());
        assertEquals(Set.of("gather", "analyze", "report"), plan.stepIds());
    }

    @Test
    @DisplayName("dependency on an unknown or later step is rejected (no cycles, topo order enforced)")
    void unknownOrForwardDependencyRejected() {
        // forward reference: "b" does not exist yet at declaration position
        assertThrows(IllegalArgumentException.class, () -> Plan.of("p", List.of(
                new Plan.Step("a", "first", List.of("b")),
                Plan.Step.of("b", "second"))));

        // self cycle via forward reference of itself
        assertThrows(IllegalArgumentException.class, () -> Plan.of("p", List.of(
                new Plan.Step("a", "me", List.of("a")))));

        // plain unknown id
        assertThrows(IllegalArgumentException.class, () -> Plan.of("p", List.of(
                new Plan.Step("a", "x", List.of("ghost")))));
    }

    @Test
    @DisplayName("duplicate and blank ids rejected; empty plan rejected")
    void identityInvariants() {
        assertThrows(IllegalArgumentException.class, () -> Plan.of("p", List.of()));
        assertThrows(IllegalArgumentException.class, () -> Plan.of("p", List.of(
                Plan.Step.of("a", "x"),
                Plan.Step.of("a", "y"))));
        assertThrows(IllegalArgumentException.class, () -> Plan.of("p", List.of(
                new Plan.Step(" ", "x", List.of()))));
    }

    @Test
    @DisplayName("every plan starts at version 1 (structural mutations bump it via rebuild)")
    void planVersioning() {
        Plan plan = Plan.of("v", List.of(Plan.Step.of("only", "step")));
        assertEquals(1, plan.planVersion());
        // A rebuilt plan with an extra step is a NEW plan identity; the
        // executor versions the lowering with planVersion so a drifted
        // resume is refused by DEFINITION_VERSION_MISMATCH.
        Plan rebuilt = Plan.of("v", List.of(
                Plan.Step.of("only", "step"),
                Plan.Step.of("added", "later step")));
        assertEquals(1, rebuilt.planVersion());
        assertNotSame(plan, rebuilt);
    }

    @Test
    @DisplayName("readySteps: only dep-satisfied, not-yet-done steps are the resume frontier")
    void readyStepsComputeFrontier() {
        Plan plan = Plan.of("diamond", List.of(
                Plan.Step.of("a", "start"),
                new Plan.Step("b", "left", List.of("a")),
                new Plan.Step("c", "right", List.of("a")),
                new Plan.Step("d", "join", List.of("b", "c"))));

        // nothing done: only "a" is ready
        assertEquals(List.of("a"), ids(plan.readySteps(Set.of())));

        // a done: b and c ready in parallel, d blocked
        assertEquals(List.of("b", "c"), ids(plan.readySteps(Set.of("a"))));

        // a+b done: only c remains ready, d still blocked on c
        assertEquals(List.of("c"), ids(plan.readySteps(Set.of("a", "b"))));

        // all done: empty frontier
        assertTrue(plan.readySteps(plan.stepIds()).isEmpty());
    }

    private static List<String> ids(List<Plan.Step> steps) {
        return steps.stream().map(Plan.Step::id).toList();
    }
}
