package io.github.qwzhang01.agent.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.mcp.a2a.A2AClient;
import io.github.qwzhang01.agent.mcp.a2a.A2ATask;
import io.github.qwzhang01.agent.mcp.a2a.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * External worker: bridges the unified {@link AgentWorker} abstraction to an
 * A2A remote agent (Stage 11 M11.4, D1 + D5).
 * <p>
 * The "external" half of the unified abstraction: {@code execute()} translates
 * a {@link WorkerTask} into an {@link A2ATask} and delegates via
 * {@link A2AClient#sendTask} -- protocol-shaped delegation instead of a method
 * call. The supervisor, aggregation and failure policies treat it EXACTLY like
 * an internal worker.
 * <p>
 * Trust downgrade (D5): an external agent's output is untrusted external input.
 * Wire a sanitizer via the constructor -- the framework deliberately does NOT
 * depend on agent-security from this module (same boundary discipline as
 * agent-mcp), so the sanitizer is a plain {@code UnaryOperator<String>} and the
 * assembly layer plugs in Stage 9's implementation:
 * <pre>
 * // wiring example (in the assembly / examples layer):
 * ResultSanitizer stage9 = new DefaultResultSanitizer();
 * new ExternalAgentWorker("reviewer", a2aClient, card,
 *         output -&gt; stage9.sanitize(output).sanitized());
 * </pre>
 * A sanitizer that wants to BLOCK the result entirely simply throws -- the
 * worker converts that into failure data, which is the correct semantic.
 * <p>
 * Cost attribution v1: durationMs is recorded per result; token accounting
 * waits for Stage 18 (the A2A result carries no token fields in v1).
 */
public class ExternalAgentWorker implements AgentWorker {

    private static final Logger log = LoggerFactory.getLogger(ExternalAgentWorker.class);

    private final String name;
    private final A2AClient a2aClient;
    private final AgentCard card;
    private final UnaryOperator<String> outputSanitizer;  // nullable = raw passthrough
    private final String senderName;

    /**
     * Create a worker with an auto-built card and NO sanitizer.
     * Warning: production setups should use the full constructor with a
     * sanitizer wired (D5) -- raw passthrough trusts the remote agent.
     */
    public static ExternalAgentWorker of(String name, A2AClient client, String... skills) {
        AgentCard card = new AgentCard(name, "External agent worker '" + name + "'",
                List.of(skills), "external:" + name, "1.0");
        return new ExternalAgentWorker(name, client, card, null, "supervisor");
    }

    /**
     * Create a worker with a custom card and NO sanitizer (see warning above).
     */
    public static ExternalAgentWorker of(String name, A2AClient client, AgentCard card) {
        return new ExternalAgentWorker(name, client, card, null, "supervisor");
    }

    public ExternalAgentWorker(String name, A2AClient client, AgentCard card,
                               UnaryOperator<String> outputSanitizer) {
        this(name, client, card, outputSanitizer, "supervisor");
    }

    /**
     * @param senderName identity this worker presents to the remote agent
     *                   (who the remote side thinks is asking)
     */
    public ExternalAgentWorker(String name, A2AClient client, AgentCard card,
                               UnaryOperator<String> outputSanitizer, String senderName) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.a2aClient = Objects.requireNonNull(client, "client must not be null");
        this.card = Objects.requireNonNull(card, "card must not be null");
        this.outputSanitizer = outputSanitizer;
        this.senderName = Objects.requireNonNull(senderName, "senderName must not be null");
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
     * Delegate via A2A, sanitize the remote output, report as data.
     * Never throws (AgentWorker contract).
     */
    @Override
    public WorkerResult execute(WorkerTask task) {
        Objects.requireNonNull(task, "task must not be null");
        long start = System.currentTimeMillis();
        try {
            A2ATask a2aTask = toA2ATask(task);
            JsonNode result = a2aClient.sendTask(a2aTask);
            String output = outputFrom(result);

            // D5: external output is untrusted input -- sanitize when wired.
            // A throwing sanitizer means BLOCK -> becomes failure data below.
            if (outputSanitizer != null) {
                output = outputSanitizer.apply(output);
            }
            long elapsed = System.currentTimeMillis() - start;
            return WorkerResult.success(task, output, elapsed, 1, 0);
        } catch (RuntimeException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("External worker '{}' failed task {}: {}",
                    name, task.taskId(), e.getMessage());
            return WorkerResult.failure(task,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), elapsed, 1);
        }
    }

    /** Map a framework task onto the A2A protocol task (deadline from timeout). */
    private A2ATask toA2ATask(WorkerTask task) {
        String deadline = task.timeoutMs() > 0
                ? LocalDateTime.now().plusNanos(task.timeoutMs() * 1_000_000)
                        .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                : null;
        return new A2ATask(task.taskId(), task.workerName(), task.taskType(),
                task.payload(), senderName, deadline);
    }

    /** Convention: {"output": "text"} is unwrapped; anything else is stringified. */
    private static String outputFrom(JsonNode result) {
        return result.path("output").isTextual()
                ? result.get("output").asText() : result.toString();
    }
}
