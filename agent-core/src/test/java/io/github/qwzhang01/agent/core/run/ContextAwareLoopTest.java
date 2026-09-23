package io.github.qwzhang01.agent.core.run;

import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentEvent;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * /1.4 acceptance: the ctx-aware loop checks cancellation at step
 * boundaries and propagates the context to model + tool boundaries.
 */
class ContextAwareLoopTest {

    /** Records the last context each boundary saw. */
    static final class RecordingClient implements ModelClient {
        final AtomicReference<RunContext> seenAtChat = new AtomicReference<>();
        final AtomicReference<RunContext> seenAtStream = new AtomicReference<>();
        final ModelResponse answer = ModelResponse.text("final");

        /** Optional per-turn answer override (used by legacy-path test). */
        java.util.function.Supplier<ModelResponse> turnOverride;

        @Override
        public ModelResponse chat(ModelRequest request) {
            if (turnOverride != null) {
                return turnOverride.get();
            }
            throw new IllegalStateException("legacy chat must not be called on the ctx path");
        }

        @Override
        public ModelResponse chat(ModelRequest request, RunContext ctx) {
            seenAtChat.set(ctx);
            return turnOverride != null ? turnOverride.get() : answer;
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            throw new IllegalStateException("legacy stream must not be called on the ctx path");
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request, RunContext ctx) {
            seenAtStream.set(ctx);
            return Stream.of(new StreamEvent.Done(
                    turnOverride != null ? turnOverride.get() : answer));
        }
    }

    static final class RecordingTool implements Tool {
        static final AtomicReference<RunContext> seen = new AtomicReference<>();

        @Override public String getName() { return "probe"; }
        @Override public String getDescription() { return "records ctx"; }
        @Override public String getParametersSchema() { return null; }

        @Override
        public String execute(JsonNode arguments) {
            throw new IllegalStateException("legacy execute must not be called on the ctx path");
        }

        @Override
        public String execute(JsonNode arguments, RunContext ctx) {
            seen.set(ctx);
            return "tool-ok";
        }
    }

    private static AgentConfig configWith(ModelClient client, Tool tool) {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(tool);
        return new AgentConfig("probe-agent", null, client, registry, 10, null,
                List.of(), null, new DefaultToolExecutor(registry));
    }

    @Test
    void chatPathPropagatesContextToModelBoundary() {
        RecordingClient client = new RecordingClient();
        AgentConfig config = configWith(client, new RecordingTool());
        SimpleAgent agent = new SimpleAgent(config);

        RunContext ctx = RunContext.builder().tenantId("t1").build();
        String answer = agent.run("hello", new AgentState(), ctx);

        assertEquals("final", answer);
        assertSame(ctx, client.seenAtChat.get(), "model boundary must see the same ctx");
    }

    @Test
    void toolBoundaryReceivesContext() {
        // Model asks for the probe tool on turn 1, answers on turn 2.
        final int[] turn = {0};
        RecordingClient client = new RecordingClient();
        client.turnOverride = () -> {
            if (turn[0]++ == 0) {
                return ModelResponse.toolCalls(List.of(io.github.qwzhang01.agent.core.model.ToolCall.of(
                        "call-1", "probe", (JsonNode) null)));
            }
            return client.answer;
        };
        AgentConfig config = configWith(client, new RecordingTool());
        SimpleAgent agent = new SimpleAgent(config);

        RunContext ctx = RunContext.builder().tenantId("tenant-a").userId("u1").build();
        String out = agent.run("use the tool", new AgentState(), ctx);

        assertEquals("final", out);
        assertSame(ctx, RecordingTool.seen.get(), "tool boundary must see the same ctx");
        assertEquals("tenant-a", RecordingTool.seen.get().tenantId());
    }

    @Test
    void cancelledBeforeStartStopsLoopWithCancelledStatus() {
        RecordingClient client = new RecordingClient();
        AgentConfig config = configWith(client, new RecordingTool());
        SimpleAgent agent = new SimpleAgent(config);

        CancellationSource source = new CancellationSource();
        source.cancel();
        RunContext ctx = RunContext.builder().cancellationToken(source.token()).build();

        String out = agent.run("hello", new AgentState(), ctx);

        assertEquals("[Agent run cancelled]", out);
        assertNull(client.seenAtChat.get(), "model must never be called after cancel");
    }

    @Test
    void deadlineExceededStopsLoopBeforeFirstModelCall() {
        RecordingClient client = new RecordingClient();
        AgentConfig config = configWith(client, new RecordingTool());
        SimpleAgent agent = new SimpleAgent(config);

        RunContext ctx = RunContext.builder()
                .deadline(java.time.Instant.now().minusSeconds(1))
                .build();

        agent.run("hello", new AgentState(), ctx);
        assertNull(client.seenAtChat.get(), "model must never be called past deadline");
    }

    @Test
    void legacyPathIsUnchanged() {
        // No-ctx run must hit the legacy chat and legacy tool.execute
        RecordingClient client = new RecordingClient();
        client.turnOverride = () -> client.answer; // plain answer, no tool calls
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(new RecordingTool());
        AgentConfig plainConfig = new AgentConfig("probe-agent", null, client, registry, 10);
        SimpleAgent agent = new SimpleAgent(plainConfig);

        String out = agent.run("hello", new AgentState());
        assertEquals("final", out);
    }
}
