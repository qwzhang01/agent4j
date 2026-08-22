package io.github.qwzhang01.agent.core.client;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * Unified interface for image generation providers.
 * <p>
 * Unlike {@link ModelClient} (conversational understanding), image generation
 * is a one-shot creative task: prompt in, images out. Implementations live in
 * agent-model (OpenAI Images API, Volcengine Ark Seedream - both share the
 * /images/generations wire format).
 * <p>
 * Wrap it with an ImageGenerationTool to let Agents generate images
 * mid-conversation via the normal tool-calling loop.
 */
public interface ImageGenerationClient {

    /**
     * Generates images for the given request (synchronous).
     *
     * @param request generation request
     * @return generated images (URLs or base64 data)
     * @throws ModelException if generation fails
     */
    ImageResult generate(ImageGenRequest request);

    // ============ Request / Response ============

    /**
     * Image generation request.
     *
     * @param model              model id, e.g. "gpt-image-1", "doubao-seedream-4-0-250828"
     * @param prompt             text description of the desired image
     * @param size               e.g. "1024x1024", "2048x2048"; null for provider default
     * @param n                  number of images to generate; null for provider default
     * @param quality            e.g. "standard", "hd" (OpenAI); null for provider default
     * @param responseFormat     "url" or "b64_json" (DALL-E only; gpt-image always returns b64); null for default
     * @param referenceImageUrls optional reference images for image-to-image generation
     *                           (supported by Ark Seedream via the "image" field)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ImageGenRequest(
            String model,
            String prompt,
            String size,
            Integer n,
            String quality,
            String responseFormat,
            List<String> referenceImageUrls
    ) {
        public static Builder builder() {
            return new Builder();
        }

        // ============ Builder ============

        public static class Builder {
            private String model;
            private String prompt;
            private String size;
            private Integer n;
            private String quality;
            private String responseFormat;
            private List<String> referenceImageUrls;

            public Builder model(String model) {
                this.model = model;
                return this;
            }

            public Builder prompt(String prompt) {
                this.prompt = prompt;
                return this;
            }

            public Builder size(String size) {
                this.size = size;
                return this;
            }

            public Builder n(Integer n) {
                this.n = n;
                return this;
            }

            public Builder quality(String quality) {
                this.quality = quality;
                return this;
            }

            public Builder responseFormat(String responseFormat) {
                this.responseFormat = responseFormat;
                return this;
            }

            public Builder referenceImageUrls(List<String> referenceImageUrls) {
                this.referenceImageUrls = referenceImageUrls;
                return this;
            }

            public Builder addReferenceImageUrl(String url) {
                if (this.referenceImageUrls == null) {
                    this.referenceImageUrls = new ArrayList<>();
                }
                this.referenceImageUrls.add(url);
                return this;
            }

            public ImageGenRequest build() {
                if (prompt == null || prompt.isBlank()) {
                    throw new IllegalArgumentException("prompt must not be blank");
                }
                return new ImageGenRequest(model, prompt, size, n, quality,
                        responseFormat, referenceImageUrls);
            }
        }
    }

    /**
     * A single generated image.
     *
     * @param url           download URL of the image (null if base64 returned)
     * @param base64Data    base64-encoded image bytes (null if url returned)
     * @param revisedPrompt prompt revised by the model (DALL-E 3), may be null
     * @param size          actual image size, e.g. "1024x1024"
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GeneratedImage(String url, String base64Data, String revisedPrompt, String size) {
    }

    /**
     * Image generation result.
     *
     * @param images generated images, never null
     * @param model  model that produced the images
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ImageResult(List<GeneratedImage> images, String model) {
    }
}
