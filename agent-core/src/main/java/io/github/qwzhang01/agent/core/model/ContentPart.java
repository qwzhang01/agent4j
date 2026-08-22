package io.github.qwzhang01.agent.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Base64;
import java.util.Objects;

/**
 * A single part of multimodal message content.
 * <p>
 * A multimodal message mixes text and image parts:
 * <pre>{@code
 * ChatMessage.user(List.of(
 *     ContentPart.text("What's in this image?"),
 *     ContentPart.imageByUrl("https://example.com/cat.png")));
 * }</pre>
 * <p>
 * Provider clients (OpenAI-compatible, Anthropic) convert parts into their
 * wire format. Pure-text messages keep using {@link ChatMessage#content()}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface ContentPart {

    // ============ Part Types ============

    /**
     * A chunk of text.
     *
     * @param text text content, never null
     */
    record TextPart(String text) implements ContentPart {
        public TextPart {
            Objects.requireNonNull(text, "text must not be null");
        }
    }

    /**
     * An image, referenced either by URL or by base64-encoded bytes.
     *
     * @param url        public URL of the image (mutually exclusive with base64Data)
     * @param base64Data base64-encoded image bytes (mutually exclusive with url)
     * @param mimeType   image MIME type, required for base64 data (e.g. "image/png")
     */
    record ImagePart(String url, String base64Data, String mimeType) implements ContentPart {
        public ImagePart {
            boolean hasUrl = url != null && !url.isBlank();
            boolean hasData = base64Data != null && !base64Data.isBlank();
            if (!hasUrl && !hasData) {
                throw new IllegalArgumentException("ImagePart requires either url or base64Data");
            }
            if (hasData && (mimeType == null || mimeType.isBlank())) {
                throw new IllegalArgumentException("mimeType is required for base64 image data");
            }
        }
    }

    // ============ Factory Methods ============

    /**
     * Creates a text part.
     */
    static ContentPart text(String text) {
        return new TextPart(text);
    }

    /**
     * Creates an image part referencing a public URL.
     */
    static ContentPart imageByUrl(String url) {
        return new ImagePart(url, null, null);
    }

    /**
     * Creates an image part from base64-encoded bytes.
     */
    static ContentPart imageByBase64(String base64Data, String mimeType) {
        return new ImagePart(null, base64Data, mimeType);
    }

    /**
     * Creates an image part from raw bytes (convenience base64 encoding).
     */
    static ContentPart imageByBytes(byte[] data, String mimeType) {
        Objects.requireNonNull(data, "data must not be null");
        return new ImagePart(null, Base64.getEncoder().encodeToString(data), mimeType);
    }
}
