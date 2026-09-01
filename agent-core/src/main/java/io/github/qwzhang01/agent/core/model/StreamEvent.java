package io.github.qwzhang01.agent.core.model;

/**
 * Event emitted during streaming model responses.
 * <p>
 * Sealed interface: only the defined event types are valid.
 */
public sealed interface StreamEvent {

    /**
     * A chunk of text content from the model.
     * <p>
     * Reasoning models stream their chain-of-thought in a separate channel
     * ({@code reasoning_content}, {@code reasoning}, {@code thinking}, ...).
     * Clients parse that channel and discard it — it must never reach this
     * event, which carries only the answer.
     */
    record ContentDelta(String delta) implements StreamEvent {
    }

    /**
     * A tool call (may arrive incrementally or complete).
     */
    record ToolCallEvent(ToolCall toolCall) implements StreamEvent {
    }

    /**
     * Stream completed.
     */
    record Done(ModelResponse finalResponse) implements StreamEvent {
    }

    /**
     * Error during streaming.
     */
    record Error(String message, Throwable cause) implements StreamEvent {
    }
}
