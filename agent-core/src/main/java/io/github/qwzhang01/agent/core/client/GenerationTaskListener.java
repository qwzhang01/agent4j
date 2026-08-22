package io.github.qwzhang01.agent.core.client;

/**
 * Hook fired when a generation tool submits a long-running task.
 * <p>
 * {@code agent-model} tools stay independent of {@code agent-scheduler}.
 * The scheduler module implements this listener to enqueue / poll / fire
 * {@code video-done:{taskId}} events so workflows can pause and auto-resume.
 */
@FunctionalInterface
public interface GenerationTaskListener {

    String KIND_IMAGE = "image";
    String KIND_VIDEO = "video";

    /**
     * @param kind   {@link #KIND_IMAGE} or {@link #KIND_VIDEO}
     * @param taskId provider task id (or a synthetic id for sync image gen)
     * @param task   provider task handle (e.g. {@link VideoGenerationClient.VideoTask})
     */
    void onSubmitted(String kind, String taskId, Object task);
}
