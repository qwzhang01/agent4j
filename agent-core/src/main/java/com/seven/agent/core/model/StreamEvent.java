package com.seven.agent.core.model;

/**
 * Event emitted during streaming model responses.
 * <p>
 * Sealed interface: only the three defined event types are valid.
 */
public sealed interface StreamEvent {

    /**
     * A chunk of text content from the model.
     */
    record ContentDelta(String delta) implements StreamEvent {}

    /**
     * A tool call (may arrive incrementally or complete).
     */
    record ToolCallEvent(ToolCall toolCall) implements StreamEvent {}

    /**
     * Stream completed.
     */
    record Done(ModelResponse finalResponse) implements StreamEvent {}

    /**
     * Error during streaming.
     */
    record Error(String message, Throwable cause) implements StreamEvent {}
}
