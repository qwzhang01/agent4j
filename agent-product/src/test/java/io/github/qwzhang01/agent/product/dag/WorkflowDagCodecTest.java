package io.github.qwzhang01.agent.product.dag;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.workflow.Workflow;
import io.github.qwzhang01.agent.workflow.WorkflowState;
import io.github.qwzhang01.agent.workflow.nodes.ActionNode;
import io.github.qwzhang01.agent.workflow.nodes.AgentNode;
import io.github.qwzhang01.agent.workflow.nodes.ToolNode;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M13.5 codec tests (D5): structure round-trip, condition-name resolution,
 * fail-fast on unregistered predicates.
 */
class WorkflowDagCodecTest {

    private final WorkflowDagCodec codec = new WorkflowDagCodec();
    private final ConditionRegistry conditions = new ConditionRegistry();

    // Predicates registered by name (assembly-time).
    private final Predicate<WorkflowState> intentIsQuery = s -> "QUERY".equals(s.get("intent"));
    private final Predicate<WorkflowState> always = s -> true;

    private Workflow sampleWorkflow() {
        conditions.register("intent-is-query", intentIsQuery);

        Agent agent = new SimpleAgent(new AgentConfig("wf-agent", "sys",
                MockModelClient.scripted().respondText("intent"), null, 5, null));
        Tool tool = new FakeTool("ticket-query");

        return Workflow.builder("support-flow")
                .node(AgentNode.of("intent", agent))
                .node(ToolNode.of("query", tool))
                .node(ActionNode.of("fallback", ctx -> "fallback done"))
                .edge(Workflow.START, "intent")
                .edge("intent", "query").when(intentIsQuery)
                .edge("intent", Workflow.END).otherwise()
                .onError("query", "fallback")
                .edge("query", Workflow.END)
                .edge("fallback", Workflow.END)
                .build();
    }

    // ============ Export ============

    @Test
    void exportsNodesEdgesAndConditionNames() {
        DagSpec dag = codec.toDag(sampleWorkflow(), conditions);

        assertEquals("support-flow", dag.name());
        assertEquals(3, dag.nodes().size());   // START/END are sentinels, not nodes
        assertTrue(dag.nodes().stream().anyMatch(n -> n.id().equals("intent")
                && "AgentNode".equals(n.type())));
        assertTrue(dag.nodes().stream().anyMatch(n -> "ToolNode".equals(n.type())));

        assertEquals(5, dag.edges().size());   // START->intent, intent->query(cond), intent->END, query->END, fallback->END
        DagSpec.EdgeSpec conditional = dag.edges().stream()
                .filter(e -> e.when() != null).findFirst().orElseThrow();
        assertEquals("intent-is-query", conditional.when());
        assertEquals("intent", conditional.from());
        assertEquals("query", conditional.to());

        assertEquals(1, dag.errorEdges().size());
        assertEquals("query", dag.errorEdges().get(0).from());
        assertEquals("fallback", dag.errorEdges().get(0).to());
    }

    @Test
    void unregisteredConditionRefusesExport() {
        // A workflow with a lambda that was never registered must NOT silently
        // lose its conditional branch in the export.
        Workflow unregistered = Workflow.builder("wild")
                .node(ActionNode.of("a", ctx -> "x"))
                .edge(Workflow.START, "a")
                .edge("a", Workflow.END).when(s -> true)   // stranger predicate
                .build();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> codec.toDag(unregistered, conditions));
        assertTrue(e.getMessage().contains("unregistered"), e.getMessage());
    }

    @Test
    void dagSerializesToJsonAndBack() {
        DagSpec dag = codec.toDag(sampleWorkflow(), conditions);

        String json = dag.toJson();
        DagSpec parsed = DagSpec.fromJson(json);

        assertEquals(dag, parsed);   // record equality through JSON
    }

    // ============ Round-trip ============

    @Test
    void roundTripRebuildsEquivalentWorkflow() {
        Workflow original = sampleWorkflow();
        DagSpec dag = codec.toDag(original, conditions);

        // Resolver supplies node instances by id (D1: behavior from the registry side).
        Workflow rebuilt = codec.fromDag(dag, original::node, conditions);

        assertEquals(original.name(), rebuilt.name());
        assertEquals(original.nodes().keySet(), rebuilt.nodes().keySet());
        assertEquals(3, rebuilt.nodes().size());

        // Normal edges: same from/to pairs, same condition identity.
        for (String from : original.nodes().keySet()) {
            assertEquals(original.outgoingEdges(from).size(),
                    rebuilt.outgoingEdges(from).size());
        }
        assertEquals(original.outgoingEdges(Workflow.START).size(),
                    rebuilt.outgoingEdges(Workflow.START).size());

        // The conditional edge survived with THE SAME predicate instance.
        var rebuiltConditional = rebuilt.outgoingEdges("intent").stream()
                .filter(edge -> edge.condition() != null).findFirst().orElseThrow();
        assertInstanceOf(Predicate.class, rebuiltConditional.condition());
        assertTrue(rebuiltConditional.condition() == intentIsQuery
                || conditions.nameOf(rebuiltConditional.condition()) != null);

        // Error edges survived.
        assertEquals(1, rebuilt.errorEdges("query").size());
        assertEquals("fallback", rebuilt.errorEdges("query").get(0).to());
    }

    @Test
    void rebuiltConditionalEdgeBehavesLikeTheOriginal() {
        Workflow original = sampleWorkflow();
        DagSpec dag = codec.toDag(original, conditions);
        Workflow rebuilt = codec.fromDag(dag, original::node, conditions);

        // The rebuilt conditional edge routes by the SAME predicate semantics:
        // intent == QUERY takes the tool branch, anything else falls through.
        WorkflowState queryState = new WorkflowState("input");
        queryState.put("intent", "QUERY");
        WorkflowState otherState = new WorkflowState("input");
        otherState.put("intent", "CHITCHAT");

        var toQuery = rebuilt.outgoingEdges("intent").stream()
                .filter(e -> "query".equals(e.to())).findFirst().orElseThrow();
        var toEnd = rebuilt.outgoingEdges("intent").stream()
                .filter(e -> Workflow.END.equals(e.to())).findFirst().orElseThrow();

        assertTrue(toQuery.matches(queryState), "QUERY intent should take the tool branch");
        assertTrue(!toQuery.matches(otherState), "other intents should not take the tool branch");
        assertTrue(toEnd.matches(otherState), "the otherwise edge is unconditional");
    }

    @Test
    void fromDagRejectsUnknownConditionName() {
        DagSpec dag = new DagSpec("v1", "x",
                java.util.List.of(new DagSpec.NodeSpec("a", "ActionNode")),
                java.util.List.of(new DagSpec.EdgeSpec(Workflow.START, "a", "ghost-condition"),
                        new DagSpec.EdgeSpec("a", Workflow.END, null)),
                java.util.List.of());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> codec.fromDag(dag, id -> ActionNode.of(id, ctx -> "x"), conditions));
        assertTrue(e.getMessage().contains("ghost-condition"), e.getMessage());
    }

    @Test
    void fromDagRejectsUnknownNode() {
        DagSpec dag = new DagSpec("v1", "x",
                java.util.List.of(new DagSpec.NodeSpec("ghost", "ActionNode")),
                java.util.List.of(new DagSpec.EdgeSpec(Workflow.START, "ghost"),
                        new DagSpec.EdgeSpec("ghost", Workflow.END, null)),
                java.util.List.of());

        assertThrows(IllegalArgumentException.class,
                () -> codec.fromDag(dag, id -> null, conditions));
    }

    // ============ Registry discipline ============

    @Test
    void conditionRegistryRejectsDuplicates() {
        Predicate<WorkflowState> p = s -> true;
        conditions.register("same-name", p);
        assertThrows(IllegalArgumentException.class,
                () -> conditions.register("same-name", s -> false));
        assertThrows(IllegalArgumentException.class,
                () -> conditions.register("other-name", p));
        assertNull(conditions.predicateOf("ghost"));
    }

    // ============ Test doubles ============

    private record FakeTool(String name) implements Tool {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "fake";
        }

        @Override
        public String getParametersSchema() {
            return null;
        }

        @Override
        public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
            return "ok";
        }
    }
}
