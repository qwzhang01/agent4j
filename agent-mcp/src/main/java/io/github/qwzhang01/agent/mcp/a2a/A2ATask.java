package io.github.qwzhang01.agent.mcp.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * A task delegated from one Agent to another (A2A protocol, Stage 10 v1).
 * <p>
 * When Agent A wants Agent B to do something, A creates an A2ATask and sends it
 * via {@link A2AClient#sendTask}. B processes it and returns a result.
 * <p>
 * Full multi-agent orchestration (task graphs, delegation chains, cost attribution)
 * is Stage 11. Stage 10 only introduces the data structure.
 *
 * @param taskId     unique task identifier (assigned by the sender)
 * @param recipient  the recipient Agent's name
 * @param taskType   what kind of task (e.g. "code-review", "summarize")
 * @param payload    the task data (free-form JSON)
 * @param sender     who sent this task
 * @param deadline   expected completion time (ISO 8601, nullable = no deadline)
 */
public record A2ATask(
        String taskId,
        String recipient,
        String taskType,
        JsonNode payload,
        String sender,
        String deadline
) {
    public A2ATask {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(recipient, "recipient must not be null");
    }
}
