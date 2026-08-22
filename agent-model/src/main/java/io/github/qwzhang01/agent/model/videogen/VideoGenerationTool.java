package io.github.qwzhang01.agent.model.videogen;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.core.client.GenerationTaskListener;
import io.github.qwzhang01.agent.core.client.VideoGenerationClient;
import io.github.qwzhang01.agent.core.tool.GenerationTools;
import io.github.qwzhang01.agent.core.tool.Tool;

import java.time.Duration;

/**
 * Tool wrapper around {@link VideoGenerationClient}.
 * <p>
 * Default is <strong>non-blocking</strong>: submit the task, notify
 * {@link GenerationTaskListener} (Stage 7 poller), return the task id.
 * Set {@code wait=true} only when a caller explicitly wants to block the
 * ReAct loop (bounded by {@code waitTimeout}).
 */
public class VideoGenerationTool implements Tool {

    private static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(5);

    private final VideoGenerationClient client;
    private final Duration waitTimeout;
    private final Duration pollInterval;
    private final GenerationTaskListener listener;

    public VideoGenerationTool(VideoGenerationClient client) {
        this(client, DEFAULT_WAIT_TIMEOUT, DEFAULT_POLL_INTERVAL, null);
    }

    public VideoGenerationTool(VideoGenerationClient client, GenerationTaskListener listener) {
        this(client, DEFAULT_WAIT_TIMEOUT, DEFAULT_POLL_INTERVAL, listener);
    }

    public VideoGenerationTool(VideoGenerationClient client, Duration waitTimeout) {
        this(client, waitTimeout, DEFAULT_POLL_INTERVAL, null);
    }

    public VideoGenerationTool(VideoGenerationClient client, Duration waitTimeout, Duration pollInterval) {
        this(client, waitTimeout, pollInterval, null);
    }

    public VideoGenerationTool(VideoGenerationClient client, Duration waitTimeout, Duration pollInterval,
                               GenerationTaskListener listener) {
        this.client = client;
        this.waitTimeout = waitTimeout;
        this.pollInterval = pollInterval;
        this.listener = listener;
    }

    @Override
    public String getName() {
        return GenerationTools.GENERATE_VIDEO;
    }

    @Override
    public String getDescription() {
        return "Submits a short video generation task and returns the task id immediately. "
                + "Video generation takes 30s–several minutes; do not set wait=true unless "
                + "the user explicitly wants to block. Prefer wait=false (default) and let "
                + "the scheduler poll until video-done:{taskId}.";
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
                      "description": "Block until the video is ready. Default false."
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

            VideoGenerationClient.VideoTask task = client.submit(builder.build());
            if (listener != null) {
                listener.onSubmitted(GenerationTaskListener.KIND_VIDEO, task.id(), task);
            }

            boolean wait = arguments.has("wait") && arguments.path("wait").asBoolean(false);
            if (!wait) {
                return "Video generation task submitted. Task id: " + task.id()
                        + " (status: " + task.status() + "). "
                        + "Event key when done: video-done:" + task.id();
            }

            task = client.awaitCompletion(task.id(), waitTimeout, pollInterval);
            return formatTerminal(task);
        } catch (Exception e) {
            return "Video generation failed: " + e.getMessage();
        }
    }

    private static String formatTerminal(VideoGenerationClient.VideoTask task) {
        if (task.isSucceeded()) {
            if (task.videoUrl() != null) {
                String result = "Video generated successfully: " + task.videoUrl();
                if (task.coverImageUrl() != null) {
                    result += "\nCover image: " + task.coverImageUrl();
                }
                return result;
            }
            return "Video generated successfully. Task id: " + task.id()
                    + " - the content must be downloaded via downloadContent(taskId).";
        }
        return "Video generation failed: "
                + (task.error() != null ? task.error() : "unknown error (task " + task.id() + ")");
    }
}
