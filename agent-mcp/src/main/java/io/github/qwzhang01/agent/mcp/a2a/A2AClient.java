package io.github.qwzhang01.agent.mcp.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Agent-to-Agent client interface.
 * <p>
 * Two implementations, one contract:
 * <ul>
 *   <li>{@link InProcessA2AClient} -- same-JVM agents, registered by name.</li>
 *   <li>{@link HttpA2AClient} -- any A2A endpoint over HTTP: spec dialect
 *       ({@code message/send}, {@code tasks/get}, {@code /.well-known/agent.json}).</li>
 * </ul>
 * Swapping the transport changes no caller -- the split Stage 10 promised
 * and Stage 11's D6 kept honest.
 * <p>
 * Failure semantics shared by both implementations:
 * <ul>
 *   <li>A task that RAN and ended badly (failed / canceled / rejected)
 *       throws {@link IllegalStateException} -- the failure IS the answer.</li>
 *   <li>A task paused awaiting more input returns
 *       {@code {"status": "input-required", ...}} as data.</li>
 *   <li>The HTTP implementation adds {@link A2AHttpException} for failures
 *       where no task answer exists at all (transport / protocol level).</li>
 * </ul>
 */
public interface A2AClient {

    /**
     * Discover agents and their capabilities. In-process: all registered
     * cards. HTTP: the peer's single card from
     * {@code /.well-known/agent.json} (the spec's rule: one endpoint, one
     * agent).
     */
    List<AgentCard> discoverAgents();

    /**
     * Send a task to another Agent. Returns the result (synchronous v1):
     * conventionally {@code {"output": text}} for a completed task, or
     * {@code {"status": "input-required", ...}} when the peer paused.
     *
     * @param task the task to delegate
     * @return the result from the recipient Agent
     * @throws IllegalStateException the task ran but failed / was rejected /
     *                               canceled (both implementations)
     * @throws A2AHttpException      HTTP transport: the call itself broke
     */
    JsonNode sendTask(A2ATask task);

    /**
     * Get the status of a previously sent task, in the spec's dialect.
     *
     * @param taskId the task identifier (the LOCAL id you sent, for the HTTP
     *               implementation)
     * @return the task status, or null when this client never sent the task
     *         (or no longer knows it remotely)
     */
    A2ATaskStatus getTaskStatus(String taskId);

    /**
     * Send a message to another Agent (fire-and-forget, no response expected).
     * <p>
     * The in-process implementation logs it (v1); the HTTP implementation
     * refuses loudly -- the spec's subset has no fire-and-forget method, and
     * faking it with message/send would silently CREATE a task.
     *
     * @param message the message to send
     */
    void sendMessage(A2AMessage message);
}
