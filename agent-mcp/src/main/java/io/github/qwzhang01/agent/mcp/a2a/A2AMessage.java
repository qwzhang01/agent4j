package io.github.qwzhang01.agent.mcp.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * A message between Agents (A2A protocol, Stage 10 v1).
 * <p>
 * Unlike {@link A2ATask} (which is a one-shot delegation), A2AMessage is for
 * ongoing conversation -- clarifications, progress updates, results.
 *
 * @param messageId  unique message identifier
 * @param from        sender Agent name
 * @param to          recipient Agent name
 * @param content     message content (free-form JSON)
 * @param taskId      the task this message relates to (nullable for ad-hoc)
 * @param timestamp   when the message was sent
 */
public record A2AMessage(
        String messageId,
        String from,
        String to,
        JsonNode content,
        String taskId,
        String timestamp
) {
    public A2AMessage {
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
    }
}
