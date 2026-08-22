package io.github.qwzhang01.agent.model.imagegen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.client.ImageGenerationClient;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.model.testsupport.MockApiServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the OpenAI-compatible image generation protocol and the
 * ImageGenerationTool wrapper.
 */
class ImageGenerationTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String IMAGE_RESPONSE = """
            {"created":1719999999,
             "data":[{"url":"https://cdn.example.com/img.png",
                      "revised_prompt":"a cute orange cat",
                      "size":"1024x1024"}]}
            """;

    private MockApiServer api;

    @BeforeEach
    void setUp() throws Exception {
        api = new MockApiServer();
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    // ============ OpenAiImageClient protocol ============

    @Test
    void sendsModelPromptAndParams() throws Exception {
        api.enqueue("/images/generations", IMAGE_RESPONSE);
        var client = new OpenAiImageClient(api.baseUrl(), "test-key", "gpt-image-1");

        var result = client.generate(ImageGenerationClient.ImageGenRequest.builder()
                .prompt("a cat")
                .size("1024x1024")
                .n(1)
                .build());

        JsonNode body = mapper.readTree(api.capturedBody("/images/generations"));
        assertEquals("gpt-image-1", body.path("model").asText());
        assertEquals("a cat", body.path("prompt").asText());
        assertEquals("1024x1024", body.path("size").asText());
        assertEquals(1, body.path("n").asInt());
        assertFalse(body.has("quality"), "quality must be omitted when null");
        assertFalse(body.has("image"), "image array must be omitted without references");

        // Response parsing
        assertEquals(1, result.images().size());
        var image = result.images().get(0);
        assertEquals("https://cdn.example.com/img.png", image.url());
        assertEquals("a cute orange cat", image.revisedPrompt());
        assertEquals("1024x1024", image.size());
    }

    @Test
    void omitsResponseFormatForGptImageModels() throws Exception {
        api.enqueue("/images/generations", IMAGE_RESPONSE);
        var client = new OpenAiImageClient(api.baseUrl(), "test-key", "gpt-image-1");

        client.generate(ImageGenerationClient.ImageGenRequest.builder()
                .prompt("a cat")
                .responseFormat("url") // should be ignored for gpt-image models
                .build());

        assertFalse(mapper.readTree(api.capturedBody("/images/generations")).has("response_format"),
                "gpt-image models reject response_format; it must be omitted");
    }

    @Test
    void emitsImageArrayForReferenceImages() throws Exception {
        api.enqueue("/images/generations", IMAGE_RESPONSE);
        var client = new OpenAiImageClient(api.baseUrl(), "test-key",
                "doubao-seedream-4-0-250828");

        client.generate(ImageGenerationClient.ImageGenRequest.builder()
                .prompt("make it snow")
                .addReferenceImageUrl("https://example.com/scene.png")
                .build());

        JsonNode image = mapper.readTree(api.capturedBody("/images/generations")).path("image");
        assertTrue(image.isArray(), "Ark Seedream image-to-image uses the image array");
        assertEquals("https://example.com/scene.png", image.get(0).asText());
    }

    // ============ ImageGenerationTool ============

    @Test
    void toolReturnsImageUrl() throws Exception {
        ImageGenerationClient mockClient = request -> new ImageGenerationClient.ImageResult(
                List.of(new ImageGenerationClient.GeneratedImage(
                        "https://cdn.example.com/cat.png", null, null, "1024x1024")),
                "mock-model");

        Tool tool = new ImageGenerationTool(mockClient);

        var args = mapper.createObjectNode();
        args.put("prompt", "a cat in space");
        String result = tool.execute(args);

        assertTrue(result.contains("https://cdn.example.com/cat.png"),
                "tool result must expose the image URL, got: " + result);
    }

    @Test
    void toolHandlesMissingPrompt() throws Exception {
        ImageGenerationClient mockClient = request -> {
            throw new IllegalStateException("should not be called");
        };

        Tool tool = new ImageGenerationTool(mockClient);
        String result = tool.execute(mapper.createObjectNode());

        assertTrue(result.startsWith("Error:"), "missing prompt must yield a friendly error");
    }

    @Test
    void toolReportsGenerationFailure() throws Exception {
        ImageGenerationClient mockClient = request -> {
            throw new RuntimeException("quota exceeded");
        };

        Tool tool = new ImageGenerationTool(mockClient);
        var args = mapper.createObjectNode();
        args.put("prompt", "a cat");
        String result = tool.execute(args);

        assertTrue(result.contains("failed") && result.contains("quota exceeded"),
                "generation errors must be reported as text, got: " + result);
    }
}
