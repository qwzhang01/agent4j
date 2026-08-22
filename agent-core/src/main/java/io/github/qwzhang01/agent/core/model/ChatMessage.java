package io.github.qwzhang01.agent.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A single message in a conversation.
 * <p>
 * Uses factory methods to support flexible construction:
 * - SYSTEM/USER messages: role + content
 * - USER messages (multimodal): role + parts (text + images), see {@link ContentPart}
 * - ASSISTANT message: role + content + optional toolCalls
 * - TOOL message: role + content + toolCallId (result of a tool execution)
 * <p>
 * Multimodal messages carry images via {@code parts}; pure-text messages use
 * {@code content}. Provider clients convert whichever is present into their
 * wire format. An empty {@code parts} list is normalized to null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(
        ChatRole role,
        String content,
        List<ContentPart> parts,
        List<ToolCall> toolCalls,
        String toolCallId,
        String name
) {
    // ============ Compact Constructor ============

    public ChatMessage {
        // Normalize empty parts to null so providers can rely on a simple null check
        if (parts != null && parts.isEmpty()) {
            parts = null;
        }
    }

    // ============ Backward-compatible Constructor ============

    /**
     * Five-arg constructor kept for source compatibility with existing callers
     * (the pre-multimodal signature).
     */
    public ChatMessage(ChatRole role, String content, List<ToolCall> toolCalls,
                       String toolCallId, String name) {
        this(role, content, null, toolCalls, toolCallId, name);
    }

    // ============ Factory Methods ============

    public static ChatMessage system(String content) {
        return new ChatMessage(ChatRole.SYSTEM, content, null, null, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(ChatRole.USER, content, null, null, null, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ChatRole.ASSISTANT, content, null, null, null, null);
    }

    public static ChatMessage assistantWithTools(String content, List<ToolCall> toolCalls) {
        return new ChatMessage(ChatRole.ASSISTANT, content, null, toolCalls, null, null);
    }

    public static ChatMessage tool(String toolCallId, String result) {
        return new ChatMessage(ChatRole.TOOL, result, null, null, toolCallId, null);
    }

    public static ChatMessage tool(String toolCallId, String name, String result) {
        return new ChatMessage(ChatRole.TOOL, result, null, null, toolCallId, name);
    }

    // ============ Multimodal Factory Methods ============

    /**
     * Creates a user message from multimodal parts (text and/or images).
     * <p>
     * Example:
     * <pre>{@code
     * ChatMessage.user(List.of(
     *     ContentPart.text("Describe this image"),
     *     ContentPart.imageByUrl("https://example.com/photo.jpg")));
     * }</pre>
     *
     * @param parts content parts, must not be empty
     */
    public static ChatMessage user(List<ContentPart> parts) {
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("parts must not be empty");
        }
        return new ChatMessage(ChatRole.USER, null, parts, null, null, null);
    }

    /**
     * Creates a user message with text and one image referenced by URL.
     */
    public static ChatMessage userWithImage(String text, String imageUrl) {
        return user(List.of(
                ContentPart.text(text),
                ContentPart.imageByUrl(imageUrl)));
    }

    /**
     * Creates a user message with text and one image from raw bytes.
     */
    public static ChatMessage userWithImage(String text, byte[] imageData, String mimeType) {
        return user(List.of(
                ContentPart.text(text),
                ContentPart.imageByBytes(imageData, mimeType)));
    }

    /**
     * Creates a user message with text and one image from base64-encoded data.
     */
    public static ChatMessage userWithImageBase64(String text, String base64Data, String mimeType) {
        return user(List.of(
                ContentPart.text(text),
                ContentPart.imageByBase64(base64Data, mimeType)));
    }
}
