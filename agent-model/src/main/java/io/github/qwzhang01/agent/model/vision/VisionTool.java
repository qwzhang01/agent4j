package io.github.qwzhang01.agent.model.vision;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.tool.GenerationTools;
import io.github.qwzhang01.agent.core.tool.Tool;

import java.util.ArrayList;
import java.util.List;

/**
 * Tool wrapper so an Agent can <em>read</em> an image mid-conversation.
 * <p>
 * User-initiated vision still goes through {@code SimpleAgent.run(ChatMessage)}.
 * This tool is for model-initiated "look at this URL / base64" turns, so
 * Stage 9 {@code GovernedToolExecutor} can approve / rate-limit / audit it.
 */
public class VisionTool implements Tool {

    private final ModelClient client;
    private final String model;

    public VisionTool(ModelClient client) {
        this(client, null);
    }

    public VisionTool(ModelClient client, String model) {
        this.client = client;
        this.model = model;
    }

    @Override
    public String getName() {
        return GenerationTools.DESCRIBE_IMAGE;
    }

    @Override
    public String getDescription() {
        return "Describes or answers a question about an image. "
                + "Provide image_url or image_base64 (+ mime_type). "
                + "Use when the user asks what is in a picture, or after generate_image.";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "question": {
                      "type": "string",
                      "description": "What to ask about the image. Optional, default: describe the image."
                    },
                    "image_url": {
                      "type": "string",
                      "description": "Public URL of the image"
                    },
                    "image_base64": {
                      "type": "string",
                      "description": "Base64-encoded image bytes (requires mime_type)"
                    },
                    "mime_type": {
                      "type": "string",
                      "description": "MIME type for image_base64, e.g. image/png"
                    }
                  }
                }""";
    }

    @Override
    public String execute(JsonNode arguments) {
        if (arguments == null) {
            return "Error: image_url or image_base64 is required.";
        }
        String question = arguments.path("question").asText("Describe this image.");
        if (question.isBlank()) {
            question = "Describe this image.";
        }

        boolean hasUrl = arguments.hasNonNull("image_url") && !arguments.path("image_url").asText().isBlank();
        boolean hasB64 = arguments.hasNonNull("image_base64") && !arguments.path("image_base64").asText().isBlank();
        if (!hasUrl && !hasB64) {
            return "Error: image_url or image_base64 is required.";
        }

        ChatMessage user;
        try {
            if (hasUrl) {
                user = ChatMessage.userWithImage(question, arguments.path("image_url").asText());
            } else {
                String mime = arguments.path("mime_type").asText("image/png");
                user = ChatMessage.userWithImageBase64(question, arguments.path("image_base64").asText(), mime);
            }
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(user);
        var builder = ModelRequest.builder().messages(messages);
        if (model != null && !model.isBlank()) {
            builder.model(model);
        }

        try {
            ModelResponse response = client.chat(builder.build());
            if (response.content() == null || response.content().isBlank()) {
                return "The vision model returned an empty description.";
            }
            return response.content();
        } catch (Exception e) {
            return "Image understanding failed: " + e.getMessage();
        }
    }
}
