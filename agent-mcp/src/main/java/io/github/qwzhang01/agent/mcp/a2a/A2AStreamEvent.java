package io.github.qwzhang01.agent.mcp.a2a;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One SSE event from {@code message/stream}.
 *
 * @param type {@code status} or {@code artifact}
 * @param data event JSON (status object or artifact object)
 */
public record A2AStreamEvent(String type, JsonNode data) {
}
