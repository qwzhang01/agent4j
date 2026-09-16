package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 9 Reflection/Critique contract tests: bounded cycles, output
 * separation, degradation semantics.
 */
class ReflectiveAgentTest {

    // ============ Stubs ============

    /** Model client whose chat() returns scripted verdicts. */
    static final class VerdictScript implements ModelClient {
        final Queue<String> verdicts = new LinkedBlockingQueue<>();
        int calls;

        VerdictScript verdicts(String... v) {
            for (String s : v) {
                verdicts.add(s);
            }
            return this;
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            calls++;
            return ModelResponse.text(verdicts.isEmpty() ? "PASS" : verdicts.poll());
        }

        @Override
        public java.util.stream.Stream<io.github.qwzhang01.agent.core.model.StreamEvent> stream(
                ModelRequest request) {
            throw new UnsupportedOperationException("not needed in this test");
        }
    }

    /** Agent stub returning fixed answers, counting runs. */
    static final class CountingAgent implements Agent {
        int runs;
        final String answer;
        final AgentConfig config = new AgentConfig("stub", "p", null, null, 5);

        CountingAgent(String answer) {
            this.answer = answer;
        }

        @Override
        public String run(String userInput) {
            return run(userInput, new AgentState());
        }

        @Override
        public String run(String userInput, AgentState state) {
            runs++;
            state.setStatus(AgentState.Status.DONE);
            return answer;
        }

        @Override
        public AgentConfig getConfig() {
            return config;
        }
    }

    // ============ Bounded reflection ============

    @Test
    @DisplayName("PASS on first critique: answer returned unchanged, zero regeneration")
    void passOnFirstCritique() {
        CountingAgent delegate = new CountingAgent("good answer");
        VerdictScript critique = new VerdictScript().verdicts("PASS looks fine");

        ReflectiveAgent agent = new ReflectiveAgent(delegate, critique).maxCycles(2);
        String answer = agent.run("q", new AgentState(), null);

        assertEquals("good answer", answer);
        assertEquals(1, delegate.runs, "no regeneration on PASS");
        assertEquals(1, critique.calls);
    }

    @Test
    @DisplayName("REVISE then PASS: one regeneration, cycle events recorded")
    void reviseThenPass() {
        CountingAgent delegate = new CountingAgent("draft");
        VerdictScript critique = new VerdictScript().verdicts("REVISE too short", "PASS");

        List<AgentEvent> events = new ArrayList<>();
        ReflectiveAgent agent = new ReflectiveAgent(delegate, critique).maxCycles(2);
        streamCollect(agent, "q", new AgentState(), events);

        assertEquals(2, delegate.runs, "one regeneration");
        assertEquals(2, critique.calls);
        // Event contract: Started/Finished pairs with verdicts, final Done
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ReflectionStarted rs
                && rs.cycle() == 1));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ReflectionFinished rf
                && rf.cycle() == 1 && "REVISE".equals(rf.verdict())));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ReflectionFinished rf
                && rf.cycle() == 2 && "PASS".equals(rf.verdict())));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.Done));
    }

    @Test
    @DisplayName("max cycles exhausted: last candidate stands, GIVE_UP recorded, never runs away")
    void maxCyclesExhausted() {
        CountingAgent delegate = new CountingAgent("stubborn answer");
        VerdictScript critique = new VerdictScript();
        critique.verdicts("REVISE", "REVISE", "REVISE", "REVISE", "REVISE");

        List<AgentEvent> events = new ArrayList<>();
        ReflectiveAgent agent = new ReflectiveAgent(delegate, critique).maxCycles(2);
        String answer = streamCollect(agent, "q", new AgentState(), events);

        assertEquals("stubborn answer", answer, "last candidate returned as-is");
        assertEquals(3, delegate.runs, "initial + 2 regenerations = 3 runs, NOT unbounded");
        assertEquals(2, critique.calls, "critique cycles capped at maxCycles");
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ReflectionFinished rf
                && rf.cycle() == 2 && "GIVE_UP".equals(rf.verdict())));
    }

    // ============ Output separation ============

    @Test
    @DisplayName("critique text never lands in user-visible output or delegate history")
    void critiqueTextNeverVisible() {
        CountingAgent delegate = new CountingAgent("answer");
        VerdictScript critique = new VerdictScript().verdicts("REVISE this is internal critique text", "PASS");

        AgentState state = new AgentState();
        List<AgentEvent> events = new ArrayList<>();
        ReflectiveAgent agent = new ReflectiveAgent(delegate, critique);
        String answer = streamCollect(agent, "q", state, events);

        assertEquals("answer", answer);
        // Critique text must appear nowhere: not in the answer, not in the
        // conversation history, not in any event payload.
        assertFalse(answer.contains("internal critique"));
        state.getMessages().forEach(m -> assertFalse(
                m.content() != null && m.content().contains("internal critique"),
                "critique leaked into history: " + m.content()));
        for (AgentEvent e : events) {
            if (e instanceof AgentEvent.ReflectionFinished rf) {
                assertTrue(
                        "PASS".equals(rf.verdict()) || "REVISE".equals(rf.verdict())
                                || "GIVE_UP".equals(rf.verdict()),
                        "verdict must be a bare protocol word, got: " + rf.verdict());
                assertFalse(rf.verdict().contains("internal critique"),
                        "critique text leaked into event verdict");
            }
        }
    }

    // ============ Degradation ============

    @Test
    @DisplayName("critique infrastructure failure: pass-through, candidate stands")
    void critiqueFailureDegradesToPassThrough() {
        CountingAgent delegate = new CountingAgent("candidate");

        ModelClient exploding = new ModelClient() {
            @Override
            public ModelResponse chat(ModelRequest request) {
                throw new RuntimeException("critique endpoint down");
            }

            @Override
            public java.util.stream.Stream<io.github.qwzhang01.agent.core.model.StreamEvent> stream(
                    ModelRequest request) {
                throw new RuntimeException("critique endpoint down");
            }
        };

        List<AgentEvent> events = new ArrayList<>();
        ReflectiveAgent agent = new ReflectiveAgent(delegate, exploding).maxCycles(3);
String answer = streamCollect(agent, "q", new AgentState(), events);

        assertEquals("candidate", answer);
        assertEquals(1, delegate.runs, "no regeneration when critique cannot run");
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ReflectionFinished rf
                && "GIVE_UP".equals(rf.verdict())));
    }

    @Test
    @DisplayName("unparseable verdict: pass-through, budget not burned on garbage")
    void unparseableVerdictPassesThrough() {
        CountingAgent delegate = new CountingAgent("candidate");
        VerdictScript critique = new VerdictScript().verdicts("maybe? I am not sure");

        List<AgentEvent> events = new ArrayList<>();
        ReflectiveAgent agent = new ReflectiveAgent(delegate, critique).maxCycles(3);
String answer = streamCollect(agent, "q", new AgentState(), events);

        assertEquals("candidate", answer);
        assertEquals(1, delegate.runs);
    }

    @Test
    @DisplayName("maxCycles < 1 rejected at configuration time")
    void maxCyclesValidation() {
        CountingAgent delegate = new CountingAgent("x");
        assertThrows(IllegalArgumentException.class,
                () -> new ReflectiveAgent(delegate, new VerdictScript()).maxCycles(0));
    }

    // ============ Delegate failure ============

    @Test
    @DisplayName("delegate failed: nothing to critique, failure state stands")
    void delegateFailureSkipsReflection() {
        Agent failing = new Agent() {
            final AgentConfig config = new AgentConfig("failing", "p", null, null, 5);

            public String run(String userInput) {
                return run(userInput, new AgentState());
            }

            public String run(String userInput, AgentState state) {
                state.setStatus(AgentState.Status.ERROR);
                state.setLastError("boom");
                return "boom";
            }

            public AgentConfig getConfig() {
                return config;
            }
        };
        VerdictScript critique = new VerdictScript().verdicts("PASS");

        List<AgentEvent> events = new ArrayList<>();
        ReflectiveAgent agent = new ReflectiveAgent(failing, critique);
String answer = streamCollect(agent, "q", new AgentState(), events);

        assertEquals("boom", answer);
        assertEquals(0, critique.calls, "no critique on a failed delegate");
    }

    // ============ helper ============

    // small local shim: run via stream() and collect the final answer
    // from the Done event (the interface's stream contract).
    private String streamCollect(ReflectiveAgent agent, String input, AgentState state,
                                 List<AgentEvent> sink) {
        final String[] out = {null};
        agent.stream(input, state, e -> {
            sink.add(e);
            if (e instanceof AgentEvent.Done d) {
                out[0] = d.finalAnswer();
            }
        });
        return out[0];
    }
}
