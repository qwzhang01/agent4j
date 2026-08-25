package io.github.qwzhang01.agent.workflow.nodes;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.workflow.NodeContext;
import io.github.qwzhang01.agent.workflow.NodeResult;
import io.github.qwzhang01.agent.workflow.WorkflowNode;
import io.github.qwzhang01.agent.workflow.WorkflowState;

import java.util.function.Function;

/**
 * Wraps an agent-core {@link Agent} as a workflow node (design decision D2).
 * <p>
 * The Agent runs its own internal ReAct loop (possibly many steps), but to
 * the graph it is just one possibly-slow synchronous step. Uncertainty is
 * contained inside the node; the graph stays deterministic.
 * <p>
 * Conversation state lives in two places:
 * <ul>
 *   <li>Instance field — same {@code AgentNode} across in-process runs
 *       (Stage 5 behaviour, kept).</li>
 *   <li>Blackboard key {@code agentState:{nodeId}} — Stage 6 D5. Written
 *       after every execute so a FileCheckpointStore snapshot can restore
 *       a <em>new</em> AgentNode after process restart.</li>
 * </ul>
 * <p>
 * Not thread-safe: do not share one AgentNode instance between parallel
 * branches; give each branch its own instance.
 */
public final class AgentNode implements WorkflowNode {

    static final String STATE_KEY_PREFIX = "agentState:";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final String id;
    private final Agent agent;
    private final Function<NodeContext, String> inputFn;
    private AgentState agentState;

    private AgentNode(String id, Agent agent, Function<NodeContext, String> inputFn) {
        this.id = id;
        this.agent = agent;
        this.inputFn = inputFn;
    }

    /**
     * Input defaults to String.valueOf(ctx.input()).
     */
    public static AgentNode of(String id, Agent agent) {
        return new AgentNode(id, agent, null);
    }

    /**
     * Custom input extraction, e.g. from blackboard variables.
     */
    public static AgentNode of(String id, Agent agent, Function<NodeContext, String> inputFn) {
        return new AgentNode(id, agent, inputFn);
    }

    /** Blackboard key for this node's {@link AgentState} snapshot. */
    public static String stateKey(String nodeId) {
        return STATE_KEY_PREFIX + nodeId;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        String input = inputFn != null
                ? inputFn.apply(ctx)
                : String.valueOf(ctx.input());
        if (agentState == null) {
            agentState = readFromBoard(ctx.state());
            if (agentState == null) {
                agentState = new AgentState();
            }
        }
        String output = agent.run(input, agentState);
        ctx.state().put(stateKey(id), agentState.snapshot());
        return NodeResult.of(output);
    }

    private AgentState readFromBoard(WorkflowState state) {
        Object raw = state.get(stateKey(id));
        if (raw == null) {
            return null;
        }
        if (raw instanceof AgentState existing) {
            return existing;
        }
        return MAPPER.convertValue(raw, AgentState.class);
    }
}
