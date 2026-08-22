package io.github.qwzhang01.agent.model.videogen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.client.VideoGenerationClient;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.model.testsupport.MockApiServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the task-based video generation protocol for both Ark Seedance
 * and OpenAI Sora, plus the VideoGenerationTool wrapper.
 */
class VideoGenerationTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private MockApiServer api;

    @BeforeEach
    void setUp() throws Exception {
        api = new MockApiServer();
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    // ============ ArkVideoClient protocol ============

    @Test
    void arkSubmitBuildsTaskRequestBody() throws Exception {
        api.enqueue("/contents/generations/tasks",
                "{\"id\":\"cgt-123\",\"status\":\"queued\"}");
        var client = new ArkVideoClient(api.baseUrl(), "test-key", "doubao-seedance-1-0-pro-250528");

        VideoGenerationClient.VideoTask task = client.submit(
                VideoGenerationClient.VideoGenRequest.builder()
                        .prompt("a cat surfing")
                        .seconds(5)
                        .ratio("16:9")
                        .addReferenceImageUrl("https://example.com/first-frame.png")
                        .build());

        assertEquals("cgt-123", task.id());
        assertEquals(VideoGenerationClient.VideoTask.STATUS_QUEUED, task.status());

        JsonNode body = mapper.readTree(api.capturedBody("/contents/generations/tasks"));
        assertEquals("doubao-seedance-1-0-pro-250528", body.path("model").asText());
        assertEquals("a cat surfing", body.path("prompt").asText());
        assertEquals(5, body.path("duration").asInt());
        assertEquals("16:9", body.path("ratio").asText());
        assertEquals("https://example.com/first-frame.png",
                body.path("image").get(0).asText());
    }

    @Test
    void arkStatusParsesVideoUrl() throws Exception {
        api.enqueue("/contents/generations/tasks/cgt-123",
                "{\"id\":\"cgt-123\",\"status\":\"succeeded\"," +
                "\"content\":{\"video_url\":\"https://cdn.example.com/v.mp4\"}}");
        var client = new ArkVideoClient(api.baseUrl(), "test-key", "doubao-seedance-1-0-pro-250528");

        VideoGenerationClient.VideoTask task = client.status("cgt-123");

        assertTrue(task.isSucceeded());
        assertEquals("https://cdn.example.com/v.mp4", task.videoUrl());
    }

    @Test
    void arkAwaitCompletionPollsUntilDone() throws Exception {
        api.enqueue("/contents/generations/tasks/cgt-456",
                "{\"id\":\"cgt-456\",\"status\":\"running\"}");
        api.enqueue("/contents/generations/tasks/cgt-456",
                "{\"id\":\"cgt-456\",\"status\":\"succeeded\"," +
                "\"content\":{\"video_url\":\"https://cdn.example.com/done.mp4\"}}");
        var client = new ArkVideoClient(api.baseUrl(), "test-key", "doubao-seedance-1-0-pro-250528");

        VideoGenerationClient.VideoTask task = client.awaitCompletion(
                "cgt-456", Duration.ofSeconds(5), Duration.ofMillis(10));

        assertTrue(task.isSucceeded());
        assertEquals("https://cdn.example.com/done.mp4", task.videoUrl());
        assertEquals(2, api.requestCount("/contents/generations/tasks/cgt-456"),
                "awaitCompletion must poll until terminal state");
    }

    // ============ OpenAiVideoClient protocol ============

    @Test
    void openAiSubmitMapsToUnifiedStatus() throws Exception {
        api.enqueue("/videos", "{\"id\":\"video_1\",\"status\":\"queued\",\"progress\":0}");
        var client = new OpenAiVideoClient(api.baseUrl(), "test-key", "sora-2");

        VideoGenerationClient.VideoTask task = client.submit(
                VideoGenerationClient.VideoGenRequest.builder()
                        .prompt("a dog flying")
                        .seconds(10)
                        .size("1280x720")
                        .build());

        assertEquals("video_1", task.id());
        assertEquals(VideoGenerationClient.VideoTask.STATUS_QUEUED, task.status());

        JsonNode body = mapper.readTree(api.capturedBody("/videos"));
        assertEquals("sora-2", body.path("model").asText());
        assertEquals("a dog flying", body.path("prompt").asText());
        assertEquals(10, body.path("seconds").asInt());
        assertEquals("1280x720", body.path("size").asText());
    }

    @Test
    void openAiStatusMapsInProgressToRunning() throws Exception {
        api.enqueue("/videos/video_1", "{\"id\":\"video_1\",\"status\":\"in_progress\",\"progress\":42}");
        var client = new OpenAiVideoClient(api.baseUrl(), "test-key", "sora-2");

        VideoGenerationClient.VideoTask task = client.status("video_1");

        assertEquals(VideoGenerationClient.VideoTask.STATUS_RUNNING, task.status());
        assertEquals(42, task.progress());
        assertFalse(task.isDone());
    }

    @Test
    void openAiDownloadContentReturnsBytes() throws Exception {
        api.enqueue("/videos/video_1/content", "FAKE-MP4-BYTES");
        var client = new OpenAiVideoClient(api.baseUrl(), "test-key", "sora-2");

        byte[] content = client.downloadContent("video_1");

        assertArrayEquals("FAKE-MP4-BYTES".getBytes(StandardCharsets.UTF_8), content);
    }

    // ============ VideoGenerationTool ============

    @Test
    void toolWaitsAndReturnsVideoUrl() {
        VideoGenerationClient mockClient = new VideoGenerationClient() {
            @Override
            public VideoTask submit(VideoGenRequest request) {
                assertEquals("a sunset timelapse", request.prompt());
                return new VideoTask("t-1", VideoTask.STATUS_QUEUED, null, null, null, null);
            }

            @Override
            public VideoTask status(String taskId) {
                return new VideoTask("t-1", VideoTask.STATUS_SUCCEEDED,
                        "https://cdn.example.com/sunset.mp4", "https://cdn.example.com/cover.jpg",
                        100, null);
            }
        };

        Tool tool = new VideoGenerationTool(mockClient, Duration.ofSeconds(5), Duration.ofMillis(10));
        var args = mapper.createObjectNode();
        args.put("prompt", "a sunset timelapse");
        String result = tool.execute(args);

        assertTrue(result.contains("https://cdn.example.com/sunset.mp4"),
                "tool result must expose the video URL, got: " + result);
        assertTrue(result.contains("https://cdn.example.com/cover.jpg"),
                "cover image URL should be included when present");
    }

    @Test
    void toolSubmitWithoutWaitReturnsTaskId() {
        VideoGenerationClient mockClient = new VideoGenerationClient() {
            @Override
            public VideoTask submit(VideoGenRequest request) {
                return new VideoTask("t-9", VideoTask.STATUS_QUEUED, null, null, null, null);
            }

            @Override
            public VideoTask status(String taskId) {
                throw new IllegalStateException("status should not be called when wait=false");
            }
        };

        Tool tool = new VideoGenerationTool(mockClient);
        var args = mapper.createObjectNode();
        args.put("prompt", "a sunrise");
        args.put("wait", false);
        String result = tool.execute(args);

        assertTrue(result.contains("t-9"), "fire-and-forget mode must return the task id, got: " + result);
    }

    @Test
    void toolReportsFailedTask() {
        VideoGenerationClient mockClient = new VideoGenerationClient() {
            @Override
            public VideoTask submit(VideoGenRequest request) {
                return new VideoTask("t-2", VideoTask.STATUS_QUEUED, null, null, null, null);
            }

            @Override
            public VideoTask status(String taskId) {
                return new VideoTask("t-2", VideoTask.STATUS_FAILED, null, null, null, "content policy violation");
            }
        };

        Tool tool = new VideoGenerationTool(mockClient, Duration.ofSeconds(5), Duration.ofMillis(10));
        var args = mapper.createObjectNode();
        args.put("prompt", "something invalid");
        String result = tool.execute(args);

        assertTrue(result.contains("failed") && result.contains("content policy violation"),
                "failed tasks must be reported as text, got: " + result);
    }
}
