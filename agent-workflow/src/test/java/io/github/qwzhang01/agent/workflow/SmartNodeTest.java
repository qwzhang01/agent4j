package io.github.qwzhang01.agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.workflow.nodes.AgentNode;
import io.github.qwzhang01.agent.workflow.nodes.ToolNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M5.3 - smart nodes: AgentNode (LLM decision point) and ToolNode
 * (deterministic tool step), both reusing agent-core unchanged.
 */
class SmartNodeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Tool fixedTool(String name, String result) {
        return new Tool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return name;
            }

            @Override
            public String getParametersSchema() {
                return null;
            }

            @Override
            public String execute(JsonNode arguments) {
                return result;
            }
        };
    }

    private static Agent newEchoAgent() {
        return new SimpleAgent(new AgentConfig(
                "echo-agent", "sys",
                MockModelClient.scripted().respondText("transferred to human"),
                null, 5));
    }

    @Test
    void agentNodeOutputDrivesConditionalRouting() {
        // Scripted model: the "intent agent" classifies as QUERY
        MockModelClient client = MockModelClient.scripted().respondText("QUERY");
        Agent intentAgent = new SimpleAgent(new AgentConfig(
                "intent-agent", "Classify the user request into one intent.", client, null, 5));

        Workflow wf = Workflow.builder("smart-route")
                .node(AgentNode.of("intent", intentAgent))
                .node(ToolNode.of("lookup", fixedTool("lookup", "ticket#42: status=OPEN")))
                .node(AgentNode.of("fallback", newEchoAgent()))
                .edge(Workflow.START, "intent")
                .edge("intent", "lookup").when(s -> "QUERY".equals(s.get("intent")))
                .edge("intent", "fallback").otherwise()
                .edge("lookup", Workflow.END)
                .edge("fallback", Workflow.END)
                .build();

        ExecutionResult result = new GraphRuntime().run(wf, "where is my ticket?");

        assertTrue(result.isSucceeded());
        assertEquals("ticket#42: status=OPEN", result.output());
    }

    // ============ Helpers ============

    @Test
    void toolNodeExecutesDeterministicToolWithArgs() {
        Tool greet = new Tool() {
            @Override
            public String getName() {
                return "greet";
            }

            @Override
            public String getDescription() {
                return "greets a person";
            }

            @Override
            public String getParametersSchema() {
                return null;
            }

            @Override
            public String execute(JsonNode arguments) {
                return "hello, " + arguments.get("name").asText();
            }
        };

        Workflow wf = Workflow.builder("tool-flow")
                .node(ToolNode.of("greet", greet,
                        ctx -> MAPPER.createObjectNode().put("name", "seven")))
                .edge(Workflow.START, "greet")
                .edge("greet", Workflow.END)
                .build();

        ExecutionResult result = new GraphRuntime().run(wf, "ignored");

        assertTrue(result.isSucceeded());
        assertEquals("hello, seven", result.output());
    }

    @Test
    void agentNodeKeepsConversationAcrossExecutions() {
        MockModelClient client = MockModelClient.scripted()
                .respondText("first")
                .respondText("second");
        Agent agent = new SimpleAgent(new AgentConfig(
                "mem-agent", "sys", client, null, 5));
        AgentNode node = AgentNode.of("mem", agent);

        Workflow wf = Workflow.builder("multi-turn")
                .node(node)
                .edge(Workflow.START, "mem")
                .edge("mem", Workflow.END)
                .build();

        // Same Workflow + same AgentNode instance: agent state persists
        assertEquals("first", new GraphRuntime().run(wf, "q1").output());
        assertEquals("second", new GraphRuntime().run(wf, "q2").output());
    }

    @Test
    void agentStateSurvivesJsonBoardRestoreOnNewNode() throws Exception {
        var client = new CountingClient("first", "second");
        AgentNode firstNode = AgentNode.of("mem", new SimpleAgent(new AgentConfig(
                "mem-agent", "sys", client, null, 5)));
        WorkflowState live = WorkflowState.of("q1");
        firstNode.execute(NodeContext.of(live, "q1"));

        Object parked = live.get(AgentNode.stateKey("mem"));
        assertTrue(parked != null, "AgentNode must park AgentState on the blackboard");

        Object raw = MAPPER.readValue(MAPPER.writeValueAsString(parked), Object.class);
        WorkflowState restored = WorkflowState.of("q2");
        restored.put(AgentNode.stateKey("mem"), raw);

        AgentNode freshNode = AgentNode.of("mem", new SimpleAgent(new AgentConfig(
                "mem-agent", "sys", client, null, 5)));
        NodeResult second = freshNode.execute(NodeContext.of(restored, "q2"));

        assertEquals(2, client.seenSizes.size());
        assertTrue(client.seenSizes.get(1) > client.seenSizes.get(0),
                "restored node must send prior turns to the model, got " + client.seenSizes);
        assertEquals("second", second.output());
    }

    /**
     * Records how many messages the model saw on each chat call.
     */
    private static final class CountingClient implements io.github.qwzhang01.agent.core.client.ModelClient {
        private final java.util.Queue<String> replies = new java.util.ArrayDeque<>();
        private final java.util.List<Integer> seenSizes = new java.util.ArrayList<>();

        CountingClient(String... replies) {
            java.util.Collections.addAll(this.replies, replies);
        }

        @Override
        public io.github.qwzhang01.agent.core.model.ModelResponse chat(
                io.github.qwzhang01.agent.core.model.ModelRequest request) {
            seenSizes.add(request.messages().size());
            return io.github.qwzhang01.agent.core.model.ModelResponse.text(replies.remove());
        }

        @Override
        public java.util.stream.Stream<io.github.qwzhang01.agent.core.model.StreamEvent> stream(
                io.github.qwzhang01.agent.core.model.ModelRequest request) {
            var r = chat(request);
            return java.util.stream.Stream.of(new io.github.qwzhang01.agent.core.model.StreamEvent.Done(r));
        }
    }
}
