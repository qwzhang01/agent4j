package io.github.qwzhang01.agent.workflow.plan;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.run.RunContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 9 Multi-Agent Plan contract tests: state isolation, budget
 * propagation via deriveChild, result dedup, failure classification reuse.
 */
class MultiAgentPlannerTest {

    // Stubs

    /**
     * Agent stub that records the (instruction, state, ctx) it was run
     * with, so the test can assert isolation and propagation directly.
     */
    static final class RecordingAgent implements Agent {
        final String name;
        final List<String> seenInstructions = new ArrayList<>();
        final List<AgentState> seenStates = new ArrayList<>();
        final List<RunContext> seenContexts = new ArrayList<>();
        final AgentConfig config = new AgentConfig(name(), "p", null, null, 5);

        RecordingAgent(String name) {
            this.name = name;
        }

        // name() must be usable in the field initializer above
        String name() {
            return name;
        }

        @Override
        public String run(String userInput) {
            return run(userInput, new AgentState());
        }

        @Override
        public String run(String userInput, AgentState state) {
            seenInstructions.add(userInput);
            seenStates.add(state);
            seenContexts.add(null);
            state.setStatus(AgentState.Status.DONE);
            return name + ":" + userInput;
        }

        @Override
        public String run(String userInput, AgentState state, RunContext ctx) {
            seenInstructions.add(userInput);
            seenStates.add(state);
            seenContexts.add(ctx);
            state.setStatus(AgentState.Status.DONE);
            return name + ":" + userInput;
        }

        @Override
        public AgentConfig getConfig() {
            return config;
        }
    }

    /** Agent that always fails through the runtime's own status taxonomy. */
    static final class FailingAgent implements Agent {
        final AgentConfig config = new AgentConfig("fail", "p", null, null, 5);

        @Override
        public String run(String userInput) {
            return run(userInput, new AgentState());
        }

        @Override
        public String run(String userInput, AgentState state) {
            state.setStatus(AgentState.Status.ERROR);
            state.setLastError("subtask blew up");
            return "garbage";
        }

        @Override
        public AgentConfig getConfig() {
            return config;
        }
    }

    // Isolation

    @Test
    @DisplayName("each subtask runs on its own fresh state; siblings invisible by construction")
    void contextIsolation() {
        RecordingAgent a = new RecordingAgent("alpha");
        RecordingAgent b = new RecordingAgent("beta");
        MultiAgentPlanner planner = new MultiAgentPlanner(Map.of("alpha", a, "beta", b));

        planner.run(List.of(
                new MultiAgentPlanner.Subtask("t1", "alpha", "do A"),
                new MultiAgentPlanner.Subtask("t2", "beta", "do B")), null);

        assertEquals(1, a.seenStates.size());
        assertEquals(1, b.seenStates.size());
        assertNotSame(a.seenStates.get(0), b.seenStates.get(0));
        // each scratch state starts from its own instruction as history
        assertEquals(1, a.seenStates.get(0).getMessages().size());
        assertEquals(1, b.seenStates.get(0).getMessages().size());
        // alpha's history must not contain beta's prompt
        assertFalse(a.seenStates.get(0).getMessages().toString().contains("do B"));
        assertFalse(b.seenStates.get(0).getMessages().toString().contains("do A"));
    }

    // Budget propagation

    @Test
    @DisplayName("parent ctx propagates: same traceId, distinct runIds, parentRunId back-link")
    void budgetPropagationViaDeriveChild() {
        RecordingAgent a = new RecordingAgent("alpha");
        MultiAgentPlanner planner = new MultiAgentPlanner(Map.of("alpha", a));

        RunContext parent = RunContext.builder()
                .tenantId("t-tenant")
                .agentId("parent")
                .build();

        planner.run(List.of(new MultiAgentPlanner.Subtask("t1", "alpha", "work")), parent);

        RunContext child = a.seenContexts.get(0);
        assertNotNull(child, "ctx-aware run must receive the derived child context");
        assertEquals(parent.runId(), child.parentRunId());
        assertNotEquals(parent.runId(), child.runId());
        assertEquals(parent.traceId(), child.traceId(), "trace continuity");
        assertEquals("t-tenant", child.tenantId(), "tenant propagates");
        assertEquals("t1", child.agentId(), "child agentId is the subtask id");
    }

    @Test
    @DisplayName("no parent ctx: legacy run path, no ctx handed to sub-agents")
    void nullParentRunsLegacyPath() {
        RecordingAgent a = new RecordingAgent("alpha");
        MultiAgentPlanner planner = new MultiAgentPlanner(Map.of("alpha", a));

        planner.run(List.of(new MultiAgentPlanner.Subtask("t1", "alpha", "work")), null);

        assertNull(a.seenContexts.get(0));
    }

    // Result dedup

    @Test
    @DisplayName("identical outputs collapse to one canonical entry; first-seen order kept")
    void resultDedup() {
        // two agents, same fixed output
        Agent same1 = fixedAgent("same", "duplicate output");
        Agent same2 = fixedAgent("same2", "duplicate output");
        Agent unique = fixedAgent("unique", "unique output");
        MultiAgentPlanner planner = new MultiAgentPlanner(Map.of(
                "s1", same1, "s2", same2, "u", unique));

        MultiAgentPlanner.Result result = planner.run(List.of(
                new MultiAgentPlanner.Subtask("t1", "s1", "q"),
                new MultiAgentPlanner.Subtask("t2", "u", "q"),
                new MultiAgentPlanner.Subtask("t3", "s2", "q")), null);

        assertEquals(2, result.distinctOutputs().size());
        assertTrue(result.distinctOutputs().contains("duplicate output"));
        assertTrue(result.distinctOutputs().contains("unique output"));
        // both producers recorded under the canonical entry
        assertEquals(List.of("t1", "t3"), result.dedup().get("duplicate output"));
        assertEquals(List.of("t2"), result.dedup().get("unique output"));
        assertEquals(3, result.successCount());
    }

    // Failure classification reuse

    @Test
    @DisplayName("failed subtask classified from AgentState status/lastError, not a new taxonomy")
    void failureClassificationReused() {
        RecordingAgent ok = new RecordingAgent("ok");
        FailingAgent bad = new FailingAgent();
        MultiAgentPlanner planner = new MultiAgentPlanner(Map.of("ok", ok, "bad", bad));

        MultiAgentPlanner.Result result = planner.run(List.of(
                new MultiAgentPlanner.Subtask("t1", "ok", "fine"),
                new MultiAgentPlanner.Subtask("t2", "bad", "explode")), null);

        assertEquals(1, result.successCount());
        MultiAgentPlanner.SubtaskResult failed = result.subtasks().get(1);
        assertFalse(failed.success());
        assertEquals("subtask blew up", failed.error());
        // failed output excluded from dedup
        assertFalse(result.dedup().containsKey("garbage"));
    }

    @Test
    @DisplayName("unknown agent name is a per-subtask failure, siblings unaffected")
    void unknownAgentFailsItsSubtaskOnly() {
        RecordingAgent a = new RecordingAgent("alpha");
        MultiAgentPlanner planner = new MultiAgentPlanner(Map.of("alpha", a));

        MultiAgentPlanner.Result result = planner.run(List.of(
                new MultiAgentPlanner.Subtask("t1", "ghost", "anything"),
                new MultiAgentPlanner.Subtask("t2", "alpha", "real")), null);

        assertFalse(result.subtasks().get(0).success());
        assertTrue(result.subtasks().get(0).error().contains("no agent registered"));
        assertTrue(result.subtasks().get(1).success());
    }

    private static Agent fixedAgent(String name, String output) {
        return new Agent() {
            final AgentConfig config = new AgentConfig(name, "p", null, null, 5);

            @Override
            public String run(String userInput) {
                return run(userInput, new AgentState());
            }

            @Override
            public String run(String userInput, AgentState state) {
                state.setStatus(AgentState.Status.DONE);
                return output;
            }

            @Override
            public AgentConfig getConfig() {
                return config;
            }
        };
    }
}
