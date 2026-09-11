package io.github.qwzhang01.agent.mcp.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process implementation of {@link A2AClient} (Stage 11 M11.4, D6).
 * <p>
 * Routes A2A tasks to agents living in the SAME JVM. The protocol data model
 * (AgentCard / A2ATask / A2AMessage) is used 100% faithfully -- what is faked
 * is only the transport. {@link HttpA2AClient} is the real-transport sibling;
 * callers cannot tell them apart beyond latency.
 * <p>
 * Failure semantics: {@link #sendTask} throws unchecked exceptions when the
 * recipient is unknown or the agent ends in an error state -- A2A delegation
 * is remote-call-shaped, and the caller (e.g. ExternalAgentWorker) converts
 * those into its own failure data. Task status is tracked per taskId in the
 * spec dialect: working -> completed | failed (null for tasks never seen).
 * <p>
 * v1 limitations (honest): {@link #sendMessage} is logged, not delivered
 * (a real message queue is a v2 concern); tasks are executed synchronously
 * on the caller's thread.
 */
public class InProcessA2AClient implements A2AClient {

    private static final Logger log = LoggerFactory.getLogger(InProcessA2AClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Agent> agents = new ConcurrentHashMap<>();
    private final Map<String, AgentCard> cards = new ConcurrentHashMap<>();
    private final Map<String, A2ATaskStatus> taskStatus = new ConcurrentHashMap<>();

    /**
     * Register a local agent with an auto-built AgentCard.
     */
    public InProcessA2AClient registerAgent(String name, Agent agent, String... skills) {
        AgentCard card = new AgentCard(name, "In-process agent '" + name + "'",
                List.of(skills), "in-process:" + name, "1.0");
        return registerAgent(name, agent, card);
    }

    /**
     * Register a local agent with a custom AgentCard.
     */
    public InProcessA2AClient registerAgent(String name, Agent agent, AgentCard card) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(agent, "agent must not be null");
        agents.put(name, agent);
        cards.put(name, card);
        log.info("Registered in-process A2A agent '{}' (skills={})", name, card.skills());
        return this;
    }

    @Override
    public List<AgentCard> discoverAgents() {
        return List.copyOf(cards.values());
    }

    /**
     * Delegate a task to the registered agent. Synchronous v1: runs the agent
     * loop on the calling thread and returns its output wrapped as
     * {@code {"output": "..."}}.
     *
     * @throws IllegalArgumentException unknown recipient
     * @throws IllegalStateException    the agent ended in an error state
     */
    @Override
    public JsonNode sendTask(A2ATask task) {
        Objects.requireNonNull(task, "task must not be null");
        Agent agent = agents.get(task.recipient());
        if (agent == null) {
            taskStatus.put(task.taskId(), A2ATaskStatus.FAILED);
            throw new IllegalArgumentException(
                    "No agent registered for recipient '" + task.recipient() + "'");
        }

        taskStatus.put(task.taskId(), A2ATaskStatus.WORKING);
        try {
            String prompt = A2AJson.promptOf(task);
            AgentState state = new AgentState();
            String output = agent.run(prompt, state);

            // The core Agent contract encodes errors in state, not exceptions.
            if (state.getStatus() == AgentState.Status.ERROR
                    || state.getStatus() == AgentState.Status.MAX_STEPS_EXCEEDED) {
                throw new IllegalStateException("A2A task '" + task.taskId()
                        + "' failed on '" + task.recipient() + "': " + state.getLastError());
            }

            taskStatus.put(task.taskId(), A2ATaskStatus.COMPLETED);
            return MAPPER.createObjectNode().put("output", output);
        } catch (RuntimeException e) {
            taskStatus.put(task.taskId(), A2ATaskStatus.FAILED);
            throw e;
        }
    }

    @Override
    public A2ATaskStatus getTaskStatus(String taskId) {
        return taskStatus.get(taskId);
    }

    @Override
    public void sendMessage(A2AMessage message) {
        // v1: fire-and-forget only. Real in-process delivery (mailbox/queue) is v2.
        log.debug("A2A message {} from '{}' to '{}' (v1: logged, not delivered)",
                message.messageId(), message.from(), message.to());
    }
}
