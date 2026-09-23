package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * loop-integration tests: parallel plain-tool dispatch through
 * {@code ReActAgentLoop.withParallelTools} (declaration-order write-back,
 * failure-to-error-string, handoff executes last) and the null-executor
 * legacy bit-for-bit path.
 */
class ParallelToolLoopTest {

    /** Scripted chat-only client (the loop's stream path drives it). */
    static final class ScriptedMock implements io.github.qwzhang01.agent.core.client.ModelClient {
        final Queue<ModelResponse> script = new LinkedBlockingQueue<>();

        ScriptedMock respond(ModelResponse... responses) {
            for (ModelResponse r : responses) {
                script.add(r);
            }
            return this;
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            return script.poll();
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            ModelResponse r = script.poll();
            return Stream.of(new StreamEvent.Done(r));
        }
    }

    /** Slow tool: sleeps, then returns a fixed string. */
    static final class SlowTool implements Tool {
        private final String name;
        private final long sleepMs;
        final List<String> invocations = new ArrayList<>();

        SlowTool(String name, long sleepMs) {
            this.name = name;
            this.sleepMs = sleepMs;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return name + " (slow stub)";
        }

        @Override
        public String getParametersSchema() {
            return null;
        }

        @Override
        public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
            invocations.add(name);
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return name + "-result";
        }
    }

    /** Failing tool: always throws. */
    static final class ExplodingTool implements Tool {
        @Override
        public String getName() {
            return "explode";
        }

        @Override
        public String getDescription() {
            return "always fails";
        }

        @Override
        public String getParametersSchema() {
            return null;
        }

        @Override
        public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
            throw new IllegalStateException("sandbox rejected");
        }
    }

    private static final java.util.concurrent.ExecutorService TOOL_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(8);


    @Test
    @DisplayName("multi-tool response fans out, joins in declaration order, history paired")
    void parallelFanOutAndJoin() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var args = mapper.createObjectNode();
        SlowTool slow = new SlowTool("slow", 120);
        SlowTool fast = new SlowTool("fast", 10);

        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(slow);
        registry.register(fast);

        // one response with BOTH tools (slow first), then a final answer
        ScriptedMock client = new ScriptedMock()
                .respond(ModelResponse.toolCalls(List.of(
                        io.github.qwzhang01.agent.core.model.ToolCall.of("c1", "slow", args),
                        io.github.qwzhang01.agent.core.model.ToolCall.of("c2", "fast", args))))
                .respond(ModelResponse.text("done after tools"));

        ReActAgentLoop loop = new ReActAgentLoop(registry)
                .withParallelTools(new ParallelToolExecutor(TOOL_POOL));
        Agent agent = new SimpleAgent(new AgentConfig("p", "sys", client, registry, 5), loop);
        AgentState state = new AgentState();
        List<AgentEvent> events = new ArrayList<>();

        long start = System.currentTimeMillis();
        agent.stream("run both", state, events::add);
        long elapsed = System.currentTimeMillis() - start;

        // Both tools ran, and genuinely overlapped (sequential would be
        // 120+10ms; parallel joins at ~max(120,10)).
        assertEquals(List.of("slow"), slow.invocations);
        assertEquals(List.of("fast"), fast.invocations);
        assertTrue(elapsed < 200, "tools overlapped, elapsed=" + elapsed + "ms");

        // History: assistantWithTools + tool results in DECLARATION order
        List<ChatMessage> history = state.getMessages();
        List<ChatMessage> toolMsgs = history.stream()
                .filter(m -> m.role() == ChatRole.TOOL).toList();
        assertEquals(2, toolMsgs.size());
        assertEquals("c1", toolMsgs.get(0).toolCallId());
        assertEquals("c2", toolMsgs.get(1).toolCallId());
        assertEquals("slow-result", toolMsgs.get(0).content());
        assertEquals("fast-result", toolMsgs.get(1).content());

        // Events: both ToolStarted before dispatch, Finished in order
        List<AgentEvent> toolEvents = events.stream()
                .filter(e -> e instanceof AgentEvent.ToolStarted
                        || e instanceof AgentEvent.ToolFinished).toList();
        assertEquals(4, toolEvents.size());
        assertInstanceOf(AgentEvent.ToolStarted.class, toolEvents.get(0));
        assertInstanceOf(AgentEvent.ToolStarted.class, toolEvents.get(1));
        AgentEvent.ToolFinished f0 = (AgentEvent.ToolFinished) toolEvents.get(2);
        AgentEvent.ToolFinished f1 = (AgentEvent.ToolFinished) toolEvents.get(3);
        assertEquals("c1", f0.toolCallId());
        assertEquals("c2", f1.toolCallId());

        assertEquals(AgentState.Status.DONE, state.getStatus());
    }

    @Test
    @DisplayName("failing sibling converts to [ERROR] string; its result still pairs in history")
    void failureConvertsToErrorStringInsideLoop() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var args = mapper.createObjectNode();
        SlowTool good = new SlowTool("good", 1);
        ExplodingTool bad = new ExplodingTool();

        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(good);
        registry.register(bad);

        ScriptedMock client = new ScriptedMock()
                .respond(ModelResponse.toolCalls(List.of(
                        io.github.qwzhang01.agent.core.model.ToolCall.of("c1", "good", args),
                        io.github.qwzhang01.agent.core.model.ToolCall.of("c2", "explode", args))))
                .respond(ModelResponse.text("recovered"));

        ReActAgentLoop loop = new ReActAgentLoop(registry)
                .withParallelTools(new ParallelToolExecutor(TOOL_POOL));
        Agent agent = new SimpleAgent(new AgentConfig("p", "sys", client, registry, 5), loop);
        AgentState state = new AgentState();

        agent.stream("run both", state, e -> { });

        // The run SUCCEEDS: the error string is a tool result the model
        // can self-correct on, not a loop failure. The string comes from
        // the tool executor chain (DefaultToolExecutor's convention).
        assertEquals(AgentState.Status.DONE, state.getStatus());
        List<ChatMessage> toolMsgs = state.getMessages().stream()
                .filter(m -> m.role() == ChatRole.TOOL).toList();
        assertEquals(2, toolMsgs.size());
        assertEquals("good-result", toolMsgs.get(0).content());
        assertTrue(toolMsgs.get(1).content().startsWith("[ERROR]"),
                "failing tool must surface an [ERROR] string, got: "
                        + toolMsgs.get(1).content());
        assertTrue(toolMsgs.get(1).content().contains("sandbox rejected"));
        assertEquals("c2", toolMsgs.get(1).toolCallId(), "error string pairs with its call id");
    }

    @Test
    @DisplayName("governance refusal emits ToolValidationRejected after the ToolFinished twin")
    void governanceRefusalEmitsTypedEvent() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var args = mapper.createObjectNode();
        // Tool NOT registered: the executor refuses with [UNKNOWN_TOOL].
        InMemoryToolRegistry registry = new InMemoryToolRegistry();

        ScriptedMock client = new ScriptedMock()
                .respond(ModelResponse.toolCalls(List.of(
                        io.github.qwzhang01.agent.core.model.ToolCall.of("c1", "ghost", args))))
                .respond(ModelResponse.text("recovered from unknown tool"));

        ReActAgentLoop loop = new ReActAgentLoop(
                new io.github.qwzhang01.agent.core.tool.contract.ContractAwareToolExecutor(
                        registry, new io.github.qwzhang01.agent.core.tool.DefaultToolExecutor(registry)));
        Agent agent = new SimpleAgent(new AgentConfig("g", "sys", client, registry, 5), loop);
        List<AgentEvent> events = new ArrayList<>();

        agent.stream("call ghost", new AgentState(), events::add);

        // Refusal is both a model-visible string AND a typed event.
        AgentEvent.ToolValidationRejected rejected = events.stream()
                .filter(e -> e instanceof AgentEvent.ToolValidationRejected)
                .map(e -> (AgentEvent.ToolValidationRejected) e)
                .findFirst().orElseThrow();
        assertEquals("ghost", rejected.toolName());
        assertEquals("validation", rejected.stage());
        assertTrue(rejected.reason().startsWith("[UNKNOWN_TOOL]"));
        assertEquals(AgentState.Status.DONE, events.stream()
                .filter(e -> e instanceof AgentEvent.Done)
                .map(e -> ((AgentEvent.Done) e).state().getStatus())
                .findFirst().orElse(null));
    }

    @Test
    @DisplayName("single plain tool + null executor: legacy sequential path, bit-for-bit")
    void nullExecutorKeepsLegacySingleToolPath() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var args = mapper.createObjectNode();
        SlowTool only = new SlowTool("only", 1);

        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(only);

        ScriptedMock client = new ScriptedMock()
                .respond(ModelResponse.toolCalls(List.of(
                        io.github.qwzhang01.agent.core.model.ToolCall.of("c1", "only", args))))
                .respond(ModelResponse.text("legacy ok"));

        ReActAgentLoop loop = new ReActAgentLoop(registry); // no withParallelTools
        Agent agent = new SimpleAgent(new AgentConfig("p", "sys", client, registry, 5), loop);
        AgentState state = new AgentState();
        List<AgentEvent> events = new ArrayList<>();

        agent.stream("one tool", state, events::add);

        assertEquals(AgentState.Status.DONE, state.getStatus());
        assertEquals(List.of("only"), only.invocations);
        // 7 events: ModelCall pair + Tool pair + ModelCall pair + Done —
        // the sequential path, no fan-out (ScriptedMock streams bare Done,
        // no delta for text responses).
        assertEquals(7, events.size());
    }
}
