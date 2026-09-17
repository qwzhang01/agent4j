package io.github.qwzhang01.agent.mcp.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.event.BoundaryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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
    private final Consumer<BoundaryEvent> eventSink;

    /** Legacy wiring: no boundary telemetry (harness batch 7 opt-in). */
    public InProcessA2AClient() {
        this(null);
    }

    /**
     * Full wiring with the boundary telemetry sink (harness batch 7): one
     * {@link BoundaryEvent.A2ABoundaryEvent} per sendTask — success or
     * failure — emitted at the protocol boundary. The sink is a side
     * channel: a throwing sink is swallowed with a counted warn, never
     * breaks the delegation.
     *
     * @param eventSink consumer of boundary telemetry (null = no events)
     */
    public InProcessA2AClient(Consumer<BoundaryEvent> eventSink) {
        this.eventSink = eventSink != null ? eventSink : e -> { };
    }

    /** Side-channel emission: telemetry must never break the delegation. */
    private void emit(BoundaryEvent event) {
        try {
            eventSink.accept(event);
        } catch (RuntimeException e) {
            log.warn("[InProcessA2AClient] event sink failed (side channel, swallowed): {}",
                    e);
        }
    }

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
        long start = System.currentTimeMillis();
        Agent agent = agents.get(task.recipient());
        if (agent == null) {
            taskStatus.put(task.taskId(), A2ATaskStatus.FAILED);
            emit(new BoundaryEvent.A2ATaskFailed(
                    task.taskId(), task.recipient(), "UNKNOWN_RECIPIENT", Instant.now()));
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
                emit(new BoundaryEvent.A2ATaskFailed(
                        task.taskId(), task.recipient(), "AGENT_FAILED", Instant.now()));
                throw new IllegalStateException("A2A task '" + task.taskId()
                        + "' failed on '" + task.recipient() + "': " + state.getLastError());
            }

            taskStatus.put(task.taskId(), A2ATaskStatus.COMPLETED);
            emit(new BoundaryEvent.A2ATaskSent(
                    task.taskId(), task.recipient(),
                    System.currentTimeMillis() - start, Instant.now()));
            return MAPPER.createObjectNode().put("output", output);
        } catch (RuntimeException e) {
            if (!(e instanceof IllegalStateException)
                    || !e.getMessage().startsWith("A2A task '")) {
                // The peer agent threw an unexpected runtime failure (not the
                // encoded-error path above): the delegation still failed.
                emit(new BoundaryEvent.A2ATaskFailed(
                        task.taskId(), task.recipient(), "AGENT_FAILED", Instant.now()));
            }
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
