package io.github.qwzhang01.agent.spring;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.core.tool.contract.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * profile ladder: three named modes, each with the assembly
 * it promises — no silent governance anywhere in the ladder.
 * <p>
 * The model stub plays a two-turn script: first turn emits a tool call,
 * second turn answers with what the tool produced. That makes the
 * governed-vs-raw difference observable in the final text.
 */
class AgentProfileTest {

    /** A side-effect tool: under SECURE without approval it must be denied. */
    private static Tool sideEffectTool() {
        return new Tool() {
            @Override
            public String getName() {
                return "fs-write";
            }

            @Override
            public String getDescription() {
                return "writes a file - SIDE_EFFECT shaped";
            }

            @Override
            public String getParametersSchema() {
                return "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}";
            }

            @Override
            public String execute(JsonNode arguments) {
                return "wrote";
            }

            @Override
            public ToolDefinition definition() {
                return ToolDefinition.builder("fs-write")
                        .description("writes a file - SIDE_EFFECT shaped")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}")
                        .sideEffectLevel(
                                io.github.qwzhang01.agent.core.tool.contract.SideEffectLevel.SIDE_EFFECT)
                        .build();
            }
        };
    }

    @Test
    void secureFactoryAssemblesGovernedAgentsAndDeniesSideEffects() {
        ModelClientStub client = new ModelClientStub();
        // SECURE + null approval = deny-on-absence
        AgentFactory factory = new AgentFactory(client, AgentProfile.SECURE, null);
        assertThat(factory.profile()).isEqualTo(AgentProfile.SECURE);

        InMemoryToolRegistry tools = new InMemoryToolRegistry();
        tools.register(sideEffectTool());
        Agent agent = factory.create("secure-agent", "you are a test", tools, 5);
        String out = agent.run("please use the fs-write tool");
        // The governed executor denies the side-effect tool (no approval
        // service wired); the model still answers — denial is per-call,
        // not a crash.
        assertThat(out).isNotBlank();
        assertThat(out).contains("denied");
    }

    @Test
    void testFactoryAutoApprovesSideEffects() {
        ModelClientStub client = new ModelClientStub();
        AgentFactory factory = new AgentFactory(client, AgentProfile.TEST, null);
        InMemoryToolRegistry tools = new InMemoryToolRegistry();
        tools.register(sideEffectTool());
        Agent agent = factory.create("test-agent", "you are a test", tools, 5);
        String out = agent.run("please use the fs-write tool");
        assertThat(out).isNotBlank();
        assertThat(out).contains("wrote");
    }

    @Test
    void unsafeFactoryAssemblesRawAgent() {
        ModelClientStub client = new ModelClientStub();
        AgentFactory factory = new AgentFactory(client, AgentProfile.UNSAFE, null);
        InMemoryToolRegistry tools = new InMemoryToolRegistry();
        tools.register(sideEffectTool());
        Agent agent = factory.create("unsafe-agent", "you are a test", tools, 5);
        String out = agent.run("please use the fs-write tool");
        // Raw path: no governance, tool runs straight through.
        assertThat(out).contains("wrote");
    }

    @Test
    void profileDefaultsToSecure() {
        AgentProperties properties = new AgentProperties();
        assertThat(properties.getProfile()).isEqualTo(AgentProfile.SECURE);
    }

    /** Minimal local stub — no agent-model dependency needed for semantics. */
    private static final class ModelClientStub implements ModelClient {

        private final java.util.concurrent.atomic.AtomicInteger turns =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public ModelResponse chat(ModelRequest request) {
            int turn = turns.incrementAndGet();
            if (turn == 1) {
                return ModelResponse.toolCalls(
                        List.of(ToolCall.of("call-1", "fs-write", "{\"path\":\"/tmp/x\"}")));
            }
            // second turn: the tool result is in the message history —
            // reflect whether the governed stack denied it
            boolean denied = request.messages().stream()
                    .anyMatch(m -> m.content() != null && m.content().contains("[DENIED]"));
            return ModelResponse.text(
                    denied ? "the tool was denied" : "the tool wrote");
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            return Stream.empty();
        }
    }
}
