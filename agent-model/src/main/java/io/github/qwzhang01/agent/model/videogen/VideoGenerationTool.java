package io.github.qwzhang01.agent.model.videogen;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.core.client.VideoGenerationClient;
import io.github.qwzhang01.agent.core.tool.Tool;

import java.time.Duration;

/**
 * Tool wrapper around {@link VideoGenerationClient} so an Agent can generate
 * videos mid-conversation via the normal tool-calling loop.
 * <p>
 * Video generation is asynchronous (typically 30s - several minutes). By
 * default the tool blocks until completion (bounded by {@code waitTimeout})
 * and returns the video URL. Set wait=false to return the task id immediately.
 * <p>
 * Example:
 * <pre>{@code
 * var client = new ArkVideoClient(arkApiKey);
 * registry.register(new VideoGenerationTool(client));
 * }</pre>
 */
public class VideoGenerationTool implements Tool {

    private static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(5);

    private final VideoGenerationClient client;
    private final Duration waitTimeout;
    private final Duration pollInterval;

    public VideoGenerationTool(VideoGenerationClient client) {
        this(client, DEFAULT_WAIT_TIMEOUT, DEFAULT_POLL_INTERVAL);
    }

    public VideoGenerationTool(VideoGenerationClient client, Duration waitTimeout) {
        this(client, waitTimeout, DEFAULT_POLL_INTERVAL);
    }

    public VideoGenerationTool(VideoGenerationClient client, Duration waitTimeout, Duration pollInterval) {
        this.client = client;
        this.waitTimeout = waitTimeout;
        this.pollInterval = pollInterval;
    }

    // ============ Tool ============

    @Override
    public String getName() {
        return "generate_video";
    }

    @Override
    public String getDescription() {
        return "Generates a short video from a text prompt and returns the video URL. "
                + "Video generation takes a while; prefer it only when the user explicitly "
                + "asks for a video. Set wait=false to submit without waiting for completion.";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "prompt": {
                      "type": "string",
                      "description": "Detailed description of the video to generate"
                    },
                    "seconds": {
                      "type": "integer",
                      "description": "Video duration in seconds (e.g. 5-10). Optional."
                    },
                    "ratio": {
                      "type": "string",
                      "description": "Aspect ratio, e.g. '16:9' or '9:16'. Optional."
                    },
                    "wait": {
                      "type": "boolean",
                      "description": "Whether to block until the video is ready. Default true."
                    }
                  },
                  "required": ["prompt"]
                }""";
    }

    @Override
    public String execute(JsonNode arguments) {
        if (arguments == null || !arguments.has("prompt") || arguments.path("prompt").asText().isBlank()) {
            return "Error: 'prompt' is required for video generation.";
        }

        try {
            var builder = VideoGenerationClient.VideoGenRequest.builder()
                    .prompt(arguments.path("prompt").asText());

            if (arguments.has("seconds") && arguments.path("seconds").isInt()) {
                builder.seconds(arguments.path("seconds").asInt());
            }
            if (arguments.has("ratio") && !arguments.path("ratio").asText().isBlank()) {
                builder.ratio(arguments.path("ratio").asText());
            }

            // Submit the task
            VideoGenerationClient.VideoTask task = client.submit(builder.build());

            // Fire-and-forget mode: return the task id immediately
            boolean wait = !arguments.has("wait") || arguments.path("wait").asBoolean(true);
            if (!wait) {
                return "Video generation task submitted. Task id: " + task.id()
                        + " (status: " + task.status() + ")";
            }

            // Blocking mode: poll until done
            task = client.awaitCompletion(task.id(), waitTimeout, pollInterval);

            if (task.isSucceeded()) {
                if (task.videoUrl() != null) {
                    String result = "Video generated successfully: " + task.videoUrl();
                    if (task.coverImageUrl() != null) {
                        result += "\nCover image: " + task.coverImageUrl();
                    }
                    return result;
                }
                // Providers without a public URL (Sora): instruct the caller
                return "Video generated successfully. Task id: " + task.id()
                        + " - the content must be downloaded via downloadContent(taskId).";
            }

            return "Video generation failed: "
                    + (task.error() != null ? task.error() : "unknown error (task " + task.id() + ")");
        } catch (Exception e) {
            return "Video generation failed: " + e.getMessage();
        }
    }
}
