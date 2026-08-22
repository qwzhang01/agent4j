package io.github.qwzhang01.agent.core.client;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Unified interface for video generation providers.
 * <p>
 * Video generation is an asynchronous, task-based workflow:
 * <ol>
 *   <li>{@link #submit} - submit a generation task, get a task id back</li>
 *   <li>{@link #status} - poll task status (queued -&gt; running -&gt; succeeded/failed)</li>
 *   <li>fetch the result: either a public video URL (Ark Seedance) or
 *       downloadable content via {@link #downloadContent} (OpenAI Sora)</li>
 * </ol>
 * {@link #awaitCompletion} provides a blocking convenience wrapper for
 * callers that prefer to wait. Wrap the client with a VideoGenerationTool
 * to let Agents generate videos mid-conversation.
 */
public interface VideoGenerationClient {

    /**
     * Submits a video generation task.
     *
     * @param request generation request
     * @return initial task handle (id + queued status)
     * @throws ModelException if submission fails
     */
    VideoTask submit(VideoGenRequest request);

    /**
     * Queries the current status of a task.
     *
     * @param taskId task id returned by {@link #submit}
     * @return current task state
     * @throws ModelException if the query fails
     */
    VideoTask status(String taskId);

    /**
     * Blocks until the task reaches a terminal state (succeeded/failed)
     * or the timeout hits. Convenience wrapper around {@link #status} polling.
     *
     * @param taskId       task to wait for
     * @param timeout      overall timeout
     * @param pollInterval sleep between status checks
     * @return the terminal VideoTask (succeeded or failed)
     * @throws ModelException on timeout or interruption
     */
    default VideoTask awaitCompletion(String taskId, Duration timeout, Duration pollInterval) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            VideoTask task = status(taskId);
            if (task.isDone()) {
                return task;
            }
            if (System.nanoTime() >= deadline) {
                throw new ModelException(ModelException.ErrorCode.TIMEOUT,
                        "Video task " + taskId + " did not complete within " + timeout);
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ModelException(ModelException.ErrorCode.MODEL_ERROR,
                        "Interrupted while waiting for video task " + taskId, e);
            }
        }
    }

    /**
     * Downloads the generated video content.
     * <p>
     * Only needed for providers that do not expose a public URL (e.g. OpenAI
     * Sora). Providers returning public URLs keep the default implementation.
     *
     * @param taskId task id of a succeeded task
     * @return raw video bytes (mp4)
     * @throws ModelException if the download fails
     */
    default byte[] downloadContent(String taskId) {
        throw new UnsupportedOperationException(
                "This provider returns public video URLs; use VideoTask.videoUrl() instead");
    }

    // ============ Request / Task ============

    /**
     * Video generation request.
     *
     * @param model              model id, e.g. "doubao-seedance-1-0-pro-250528", "sora-2"
     * @param prompt             text description of the desired video
     * @param seconds            desired duration in seconds; null for provider default
     * @param size               resolution, e.g. "1280x720" (OpenAI); null for provider default
     * @param ratio              aspect ratio, e.g. "16:9", "9:16" (Ark Seedance); null for default
     * @param referenceImageUrls optional first-frame/reference images (image-to-video)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record VideoGenRequest(
            String model,
            String prompt,
            Integer seconds,
            String size,
            String ratio,
            List<String> referenceImageUrls
    ) {
        public static Builder builder() {
            return new Builder();
        }

        // ============ Builder ============

        public static class Builder {
            private String model;
            private String prompt;
            private Integer seconds;
            private String size;
            private String ratio;
            private List<String> referenceImageUrls;

            public Builder model(String model) {
                this.model = model;
                return this;
            }

            public Builder prompt(String prompt) {
                this.prompt = prompt;
                return this;
            }

            public Builder seconds(Integer seconds) {
                this.seconds = seconds;
                return this;
            }

            public Builder size(String size) {
                this.size = size;
                return this;
            }

            public Builder ratio(String ratio) {
                this.ratio = ratio;
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

            public VideoGenRequest build() {
                if (prompt == null || prompt.isBlank()) {
                    throw new IllegalArgumentException("prompt must not be blank");
                }
                return new VideoGenRequest(model, prompt, seconds, size, ratio, referenceImageUrls);
            }
        }
    }

    /**
     * A video generation task and its current state.
     *
     * @param id           task id
     * @param status       one of: queued / running / succeeded / failed
     * @param videoUrl     public video URL when succeeded (may be null for Sora)
     * @param coverImageUrl optional cover/thumbnail URL
     * @param progress     0-100 when the provider reports it, may be null
     * @param error        error message when failed
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record VideoTask(
            String id,
            String status,
            String videoUrl,
            String coverImageUrl,
            Integer progress,
            String error
    ) {
        public static final String STATUS_QUEUED = "queued";
        public static final String STATUS_RUNNING = "running";
        public static final String STATUS_SUCCEEDED = "succeeded";
        public static final String STATUS_FAILED = "failed";

        public boolean isDone() {
            return STATUS_SUCCEEDED.equals(status) || STATUS_FAILED.equals(status);
        }

        public boolean isSucceeded() {
            return STATUS_SUCCEEDED.equals(status);
        }

        public boolean isFailed() {
            return STATUS_FAILED.equals(status);
        }
    }
}
