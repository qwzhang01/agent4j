package io.github.qwzhang01.agent.model.openai;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Tolerant reader for an OpenAI-compatible chat-completion delta (streaming)
 * or message (non-streaming) node.
 * <p>
 * The "OpenAI-compatible" ecosystem is an open-ended long tail: Groq, Together,
 * Fireworks, Ollama, LM Studio, vLLM, Volcengine Ark, Kimi, GLM, MiniMax and a
 * new one every month. They agree on {@code content} and disagree on almost
 * everything else — in particular on which field carries reasoning.
 * <p>
 * Enumerating vendors would mean the framework needs a patch for every new
 * endpoint, so this reader takes the opposite approach: declare every field
 * name we have ever seen and read them with a fallback chain. An unknown
 * endpoint simply matches on whichever field it happens to send, and a field
 * that never arrives costs nothing.
 *
 * <table border="1">
 *   <caption>Known reasoning channels</caption>
 *   <tr><th>Field</th><th>Used by</th></tr>
 *   <tr><td>{@code reasoning_content}</td><td>Volcengine Ark, DeepSeek, Qwen/DashScope, Moonshot</td></tr>
 *   <tr><td>{@code reasoning}</td><td>OpenRouter, some vLLM builds</td></tr>
 *   <tr><td>{@code thinking}</td><td>assorted proxies mirroring Anthropic naming</td></tr>
 * </table>
 *
 * <p>
 * Reasoning is deliberately never merged into {@link #content()}: it is the
 * model's scratchpad, not its answer.
 */
final class ChatDelta {

    /**
     * Every reasoning field name observed in the wild, in priority order.
     */
    static final List<String> REASONING_FIELDS =
            List.of("reasoning_content", "reasoning", "thinking");

    private final JsonNode node;

    ChatDelta(JsonNode node) {
        this.node = node;
    }

    /**
     * Answer text in this chunk, or null when the chunk carries no answer text.
     * <p>
     * An empty string is treated as "no text": several vendors mark their final
     * chunk with {@code "content": ""} alongside {@code finish_reason}.
     */
    String content() {
        return text("content");
    }

    /**
     * Reasoning text in this chunk, or null when the chunk carries none.
     * <p>
     * Nested forms are unwrapped: Anthropic-style proxies send
     * {@code "thinking": {"text": "..."}} rather than a plain string.
     */
    String reasoning() {
        for (String field : REASONING_FIELDS) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isObject()) {
                // Nested form: pull the text child if there is one.
                JsonNode nested = value.get("text");
                if (nested != null && nested.isTextual() && !nested.asText().isEmpty()) {
                    return nested.asText();
                }
                continue;
            }
            if (value.isTextual() && !value.asText().isEmpty()) {
                return value.asText();
            }
        }
        return null;
    }

    /**
     * Tool-call fragments in this chunk, or null when there are none.
     */
    JsonNode toolCalls() {
        JsonNode array = node.get("tool_calls");
        return array != null && array.isArray() ? array : null;
    }

    private String text(String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isEmpty()) {
            return null;
        }
        return value.asText();
    }
}
