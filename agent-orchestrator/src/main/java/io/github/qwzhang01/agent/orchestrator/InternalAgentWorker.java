package io.github.qwzhang01.agent.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.mcp.a2a.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * In-process worker: wraps a core {@link Agent} as an {@link AgentWorker}
 * (Stage 11 M11.1, D1).
 * <p>
 * The "internal" half of the unified worker abstraction. {@code execute()}
 * delegates to {@code agent.run(prompt)} -- same JVM, plain method call,
 * the agent runs its own ReAct loop with its own tools, permissions and
 * memory namespace. The trust level is high (we compiled it), so no extra
 * sanitization is applied here (contrast with {@code ExternalAgentWorker},
 * M11.4, where outbound results must pass Stage 9's ResultSanitizer).
 * <p>
 * Payload-to-prompt conversion: by default the task payload's "prompt" text
 * field becomes the user input; payloads without one are serialized to their
 * JSON string. Custom conversion via the full constructor.
 */
public class InternalAgentWorker implements AgentWorker {

    private static final Logger log = LoggerFactory.getLogger(InternalAgentWorker.class);

    private final String name;
    private final Agent agent;
    private final AgentCard card;
    private final Function<WorkerTask, String> promptExtractor;

    /**
     * Create a worker with an auto-built {@link AgentCard}.
     *
     * @param name   worker name (also the card name)
     * @param agent  the wrapped core Agent
     * @param skills capability tags for skill-based routing (M11.4)
     */
    public static InternalAgentWorker of(String name, Agent agent, String... skills) {
        AgentCard card = new AgentCard(
                name,
                "Internal agent worker '" + name + "'",
                List.of(skills),
                "internal:" + name,
                "1.0");
        return new InternalAgentWorker(name, agent, card, InternalAgentWorker::defaultPrompt);
    }

    /**
     * Create a worker with a fully custom {@link AgentCard}.
     */
    public static InternalAgentWorker of(String name, Agent agent, AgentCard card) {
        return new InternalAgentWorker(name, agent, card, InternalAgentWorker::defaultPrompt);
    }

    /**
     * Full constructor.
     *
     * @param promptExtractor maps a task to the agent's user input
     *                        (default: payload's "prompt" field, else JSON string)
     */
    public InternalAgentWorker(String name, Agent agent, AgentCard card,
                               Function<WorkerTask, String> promptExtractor) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.agent = Objects.requireNonNull(agent, "agent must not be null");
        this.card = Objects.requireNonNull(card, "card must not be null");
        this.promptExtractor = Objects.requireNonNull(promptExtractor, "promptExtractor must not be null");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public AgentCard card() {
        return card;
    }

    /**
     * Run the wrapped agent once with the extracted prompt. Never throws:
     * agent failures become {@code WorkerResult} failure data (D4 contract).
     * <p>
     * Failure detection uses the structured {@link AgentState} status, NOT the
     * placeholder text the agent returns: the core Agent contract encodes errors
     * in state (ReActAgentLoop catches model exceptions -> {@code state.ERROR}),
     * while {@code run} still returns a string like "[Agent error: ...]".
     * Reading state avoids depending on that string convention.
     */
    @Override
    public WorkerResult execute(WorkerTask task) {
        Objects.requireNonNull(task, "task must not be null");
        long start = System.currentTimeMillis();
        try {
            String prompt = promptExtractor.apply(task);
            AgentState state = new AgentState();
            String output = agent.run(prompt, state);
            long elapsed = System.currentTimeMillis() - start;

            // Black-list check: explicit failure statuses only. Agents that never
            // touch the state (custom/fake implementations) stay in IDLE -> success.
            AgentState.Status status = state.getStatus();
            if (status == AgentState.Status.ERROR) {
                return WorkerResult.failure(task,
                        "agent error state: " + state.getLastError(), elapsed, 1);
            }
            if (status == AgentState.Status.MAX_STEPS_EXCEEDED) {
                return WorkerResult.failure(task,
                        "agent exceeded max steps without a final answer", elapsed, 1);
            }
            // totalTokens = 0: the Agent interface does not expose token stats yet.
            // Stage 18 observability will wire real accounting here.
            return WorkerResult.success(task, output, elapsed, 1, 0);
        } catch (RuntimeException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("Internal worker '{}' failed task {}: {}",
                    name, task.taskId(), e.getMessage());
            return WorkerResult.failure(task,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), elapsed, 1);
        }
    }

    /**
     * Default payload-to-prompt conversion: the payload's "prompt" text field,
     * or the whole payload as a JSON string when there is no prompt field.
     */
    static String defaultPrompt(WorkerTask task) {
        JsonNode payload = task.payload();
        if (payload != null && payload.path("prompt").isTextual()) {
            return payload.get("prompt").asText();
        }
        return payload == null ? "" : payload.toString();
    }
}
