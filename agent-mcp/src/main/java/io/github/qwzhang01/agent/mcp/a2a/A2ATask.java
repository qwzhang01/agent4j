package io.github.qwzhang01.agent.mcp.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/**
 * A task delegated from one Agent to another (A2A protocol).
 * <p>
 * Two shapes, one record:
 * <ul>
 *   <li><b>Delegation shape</b> (the legacy 6-arg constructor, /11
 *       callers): a task you are about to SEND. No contextId / status /
 *       artifacts -- those do not exist until the peer answers.</li>
 *   <li><b>Observed shape</b> (the canonical 9-arg constructor): a task as
 *       the wire reports it -- spec {@code contextId}, spec-dialect
 *       {@link A2ATaskStatus}, and {@link A2AArtifact} outputs.
 *       {@link A2AJson#taskFrom} builds this; hosts that want raw task data
 *       read it instead of the JsonNode convention.</li>
 * </ul>
 * The recipient field is transport-relative: in-process it selects a
 * registered agent by name; over HTTP it is informational (the URL you
 * constructed the client with IS the recipient).
 *
 * @param taskId unique task identifier (assigned by the sender pre-wire;
 *                   the SERVER assigns its own id on the wire and the HTTP
 *                   client maps between the two)
 * @param recipient the recipient Agent's name
 * @param taskType what kind of task (e.g. "code-review", "summarize"
 * @param payload the task data (free-form JSON; "prompt" is the convention)
 * @param sender who sent this task
 * @param deadline expected completion time (ISO 8601, nullable = no deadline)
 * @param contextId spec context id: groups messages belonging to one
 *                   conversation across tasks (nullable)
 * @param status spec-dialect lifecycle state (nullable = not yet observed)
 * @param artifacts outputs as reported by the peer (empty when none)
 */
public record A2ATask(
        String taskId,
        String recipient,
        String taskType,
        JsonNode payload,
        String sender,
        String deadline,
        String contextId,
        A2ATaskStatus status,
        List<A2AArtifact> artifacts
) {
    public A2ATask {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(recipient, "recipient must not be null");
        if (artifacts == null) {
            artifacts = List.of();
        }
    }

    /**
     * Legacy 6-arg delegation shape (/11 callers compile unchanged):
     * a task about to be sent, no wire observations attached.
     */
    public A2ATask(String taskId, String recipient, String taskType,
                   JsonNode payload, String sender, String deadline) {
        this(taskId, recipient, taskType, payload, sender, deadline, null, null, null);
    }
}
