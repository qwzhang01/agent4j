package io.github.qwzhang01.agent.model.imagegen;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.core.client.ImageGenerationClient;
import io.github.qwzhang01.agent.core.tool.GenerationTools;
import io.github.qwzhang01.agent.core.tool.Tool;

/**
 * Tool wrapper around {@link ImageGenerationClient} so an Agent can generate
 * images mid-conversation via the normal tool-calling loop.
 * <p>
 * Example:
 * <pre>{@code
 * var client = new OpenAiImageClient(arkBaseUrl, arkApiKey, "doubao-seedream-4-0-250828");
 * registry.register(new ImageGenerationTool(client));
 * }</pre>
 * The tool returns image URLs as text - the model relays them to the user.
 */
public class ImageGenerationTool implements Tool {

    private final ImageGenerationClient client;

    public ImageGenerationTool(ImageGenerationClient client) {
        this.client = client;
    }

    // ============ Tool ============

    @Override
    public String getName() {
        return GenerationTools.GENERATE_IMAGE;
    }

    @Override
    public String getDescription() {
        return "Generates an image from a text prompt and returns the image URL(s). "
                + "Use this whenever the user asks to create, draw, or paint an image.";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "prompt": {
                      "type": "string",
                      "description": "Detailed description of the image to generate"
                    },
                    "size": {
                      "type": "string",
                      "description": "Image size, e.g. '1024x1024', '2048x2048'. Optional."
                    },
                    "n": {
                      "type": "integer",
                      "description": "Number of images to generate (1-4). Optional, default 1."
                    }
                  },
                  "required": ["prompt"]
                }""";
    }

    @Override
    public String execute(JsonNode arguments) {
        if (arguments == null || !arguments.has("prompt") || arguments.path("prompt").asText().isBlank()) {
            return "Error: 'prompt' is required for image generation.";
        }

        try {
            var builder = ImageGenerationClient.ImageGenRequest.builder()
                    .prompt(arguments.path("prompt").asText());

            if (arguments.has("size") && !arguments.path("size").asText().isBlank()) {
                builder.size(arguments.path("size").asText());
            }
            if (arguments.has("n") && arguments.path("n").isInt()) {
                builder.n(arguments.path("n").asInt());
            }

            ImageGenerationClient.ImageResult result = client.generate(builder.build());

            StringBuilder sb = new StringBuilder("Image generated successfully:");
            for (ImageGenerationClient.GeneratedImage image : result.images()) {
                if (image.url() != null) {
                    sb.append("\n- ").append(image.url());
                } else if (image.base64Data() != null) {
                    sb.append("\n- (base64 image data, ")
                      .append(image.base64Data().length())
                      .append(" chars, mime: image/png)");
                }
                if (image.revisedPrompt() != null) {
                    sb.append("\n  (revised prompt: ").append(image.revisedPrompt()).append(")");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "Image generation failed: " + e.getMessage();
        }
    }
}
