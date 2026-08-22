package io.github.qwzhang01.agent.model.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ContentPart;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.model.anthropic.AnthropicModelClient;
import io.github.qwzhang01.agent.model.openai.OpenAiModelClient;
import io.github.qwzhang01.agent.model.testsupport.MockApiServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that multimodal (text + image) messages are serialized into the
 * correct wire format for both OpenAI-compatible and Anthropic APIs.
 */
class VisionRequestFormatTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String OPENAI_RESPONSE = """
            {"choices":[{"message":{"role":"assistant","content":"A cat."},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
            """;

    private static final String ANTHROPIC_RESPONSE = """
            {"content":[{"type":"text","text":"A cat."}],
             "stop_reason":"end_turn",
             "usage":{"input_tokens":10,"output_tokens":5}}
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

    // ============ OpenAI ============

    @Test
    void openAiSendsMultimodalContentArray() throws Exception {
        api.enqueue("/chat/completions", OPENAI_RESPONSE);
        var client = new OpenAiModelClient(api.baseUrl(), "test-key", "gpt-4o-mini");

        var response = client.chat(ModelRequest.builder()
                .addMessage(ChatMessage.userWithImage("What is this?", "https://example.com/cat.png"))
                .build());

        assertEquals("A cat.", response.content());

        JsonNode body = mapper.readTree(api.capturedBody("/chat/completions"));
        JsonNode content = body.path("messages").get(0).path("content");
        assertTrue(content.isArray(), "multimodal content must be an array");

        JsonNode textPart = content.get(0);
        assertEquals("text", textPart.path("type").asText());
        assertEquals("What is this?", textPart.path("text").asText());

        JsonNode imagePart = content.get(1);
        assertEquals("image_url", imagePart.path("type").asText());
        assertEquals("https://example.com/cat.png",
                imagePart.path("image_url").path("url").asText());
    }

    @Test
    void openAiInlinesBase64AsDataUrl() throws Exception {
        api.enqueue("/chat/completions", OPENAI_RESPONSE);
        var client = new OpenAiModelClient(api.baseUrl(), "test-key", "gpt-4o-mini");

        client.chat(ModelRequest.builder()
                .addMessage(ChatMessage.userWithImageBase64(
                        "Describe", "QUJDRA==", "image/jpeg"))
                .build());

        JsonNode content = mapper.readTree(api.capturedBody("/chat/completions"))
                .path("messages").get(0).path("content");
        JsonNode imagePart = content.get(1);
        assertEquals("data:image/jpeg;base64,QUJDRA==",
                imagePart.path("image_url").path("url").asText());
    }

    @Test
    void openAiPlainTextStaysString() throws Exception {
        api.enqueue("/chat/completions", OPENAI_RESPONSE);
        var client = new OpenAiModelClient(api.baseUrl(), "test-key", "gpt-4o-mini");

        client.chat(ModelRequest.builder()
                .addMessage(ChatMessage.user("Hello"))
                .build());

        JsonNode content = mapper.readTree(api.capturedBody("/chat/completions"))
                .path("messages").get(0).path("content");
        assertFalse(content.isArray(), "pure text messages must stay a string");
        assertEquals("Hello", content.asText());
    }

    // ============ Anthropic ============

    @Test
    void anthropicSendsUrlImageBlock() throws Exception {
        api.enqueue("/v1/messages", ANTHROPIC_RESPONSE);
        var client = new AnthropicModelClient(api.baseUrl(), "test-key", "claude-sonnet-4-20250514");

        var response = client.chat(ModelRequest.builder()
                .addMessage(ChatMessage.user(List.of(
                        ContentPart.text("What is this?"),
                        ContentPart.imageByUrl("https://example.com/cat.png"))))
                .build());

        assertEquals("A cat.", response.content());

        JsonNode body = mapper.readTree(api.capturedBody("/v1/messages"));
        JsonNode message = body.path("messages").get(0);
        assertEquals("user", message.path("role").asText());

        JsonNode content = message.path("content");
        assertTrue(content.isArray(), "multimodal content must be an array");

        JsonNode textBlock = content.get(0);
        assertEquals("text", textBlock.path("type").asText());
        assertEquals("What is this?", textBlock.path("text").asText());

        JsonNode imageBlock = content.get(1);
        assertEquals("image", imageBlock.path("type").asText());
        assertEquals("url", imageBlock.path("source").path("type").asText());
        assertEquals("https://example.com/cat.png",
                imageBlock.path("source").path("url").asText());
    }

    @Test
    void anthropicSendsBase64ImageBlock() throws Exception {
        api.enqueue("/v1/messages", ANTHROPIC_RESPONSE);
        var client = new AnthropicModelClient(api.baseUrl(), "test-key", "claude-sonnet-4-20250514");

        client.chat(ModelRequest.builder()
                .addMessage(ChatMessage.userWithImageBase64(
                        "Describe", "QUJDRA==", "image/png"))
                .build());

        JsonNode imageBlock = mapper.readTree(api.capturedBody("/v1/messages"))
                .path("messages").get(0).path("content").get(1);
        assertEquals("image", imageBlock.path("type").asText());

        JsonNode source = imageBlock.path("source");
        assertEquals("base64", source.path("type").asText());
        assertEquals("image/png", source.path("media_type").asText());
        assertEquals("QUJDRA==", source.path("data").asText());
    }

    @Test
    void chatMessagePartsNormalizeEmptyList() {
        ChatMessage msg = new ChatMessage(io.github.qwzhang01.agent.core.model.ChatRole.USER,
                "hello", null, null, null);
        // 5-arg compatibility constructor -> parts must be null
        assertNotNull(msg);
        assertEquals("hello", msg.content());
        assertTrue(msg.parts() == null, "5-arg constructor must leave parts null");
    }
}
