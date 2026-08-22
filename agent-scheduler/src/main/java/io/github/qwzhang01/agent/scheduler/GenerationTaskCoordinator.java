package io.github.qwzhang01.agent.scheduler;

import io.github.qwzhang01.agent.core.client.GenerationTaskListener;
import io.github.qwzhang01.agent.core.client.VideoGenerationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges video generation (agent-model tools) onto Stage 7 long-task machinery.
 * <p>
 * On submit: enqueue an {@link AsyncTask} and poll {@link VideoGenerationClient#status}
 * on the scheduler thread. On terminal state: fire {@code video-done:{taskId}}
 * so a {@code WaitEventNode} can auto-resume the workflow.
 */
public class GenerationTaskCoordinator {

    private static final Logger log = LoggerFactory.getLogger(GenerationTaskCoordinator.class);

    public static final String VIDEO_DONE_PREFIX = "video-done:";

    private final TaskScheduler scheduler;
    private final VideoGenerationClient client;
    private final Duration pollInterval;
    private final Duration timeout;
    private final Map<String, VideoGenerationClient.VideoTask> completed = new ConcurrentHashMap<>();
    /** taskId -> tracking flag; putIfAbsent so one task starts at most one poll chain. */
    private final Map<String, Boolean> tracking = new ConcurrentHashMap<>();

    public GenerationTaskCoordinator(TaskScheduler scheduler, VideoGenerationClient client) {
        this(scheduler, client, Duration.ofSeconds(5), Duration.ofMinutes(10));
    }

    public GenerationTaskCoordinator(TaskScheduler scheduler, VideoGenerationClient client,
                                     Duration pollInterval, Duration timeout) {
        this.scheduler = scheduler;
        this.client = client;
        this.pollInterval = pollInterval;
        this.timeout = timeout;
    }

    public static String videoDoneEvent(String taskId) {
        return VIDEO_DONE_PREFIX + taskId;
    }

    /**
     * Listener for {@code VideoGenerationTool}: start polling as soon as a task is submitted.
     */
    public GenerationTaskListener asListener() {
        return (kind, taskId, task) -> {
            if (GenerationTaskListener.KIND_VIDEO.equals(kind)) {
                trackVideo(taskId);
            }
        };
    }

    public void trackVideo(String taskId) {
        if (tracking.putIfAbsent(taskId, Boolean.TRUE) != null) {
            log.debug("[generation] Already tracking '{}', skip duplicate poll", taskId);
            return;
        }
        AsyncTask queued = AsyncTask.of("generation", taskId, TaskPriority.NORMAL, "video-generation");
        scheduler.enqueueTask(queued);
        Instant deadline = Instant.now().plus(timeout);
        log.info("[generation] Tracking video task '{}' until {}", taskId, deadline);
        scheduler.schedule(() -> poll(taskId, deadline), pollInterval);
    }

    public VideoGenerationClient.VideoTask getCompleted(String taskId) {
        return completed.get(taskId);
    }

    private void poll(String taskId, Instant deadline) {
        try {
            VideoGenerationClient.VideoTask task = client.status(taskId);
            if (task.isDone()) {
                completed.put(taskId, task);
                log.info("[generation] Video task '{}' {}", taskId, task.status());
                scheduler.fireEvent(videoDoneEvent(taskId), task);
                return;
            }
            if (Instant.now().isAfter(deadline)) {
                VideoGenerationClient.VideoTask timedOut = new VideoGenerationClient.VideoTask(
                        taskId, VideoGenerationClient.VideoTask.STATUS_FAILED,
                        null, null, null, "poll timeout after " + timeout);
                completed.put(taskId, timedOut);
                scheduler.fireEvent(videoDoneEvent(taskId), timedOut);
                return;
            }
            scheduler.schedule(() -> poll(taskId, deadline), pollInterval);
        } catch (Exception e) {
            log.error("[generation] Poll failed for '{}': {}", taskId, e.getMessage());
            VideoGenerationClient.VideoTask failed = new VideoGenerationClient.VideoTask(
                    taskId, VideoGenerationClient.VideoTask.STATUS_FAILED,
                    null, null, null, e.getMessage());
            completed.put(taskId, failed);
            scheduler.fireEvent(videoDoneEvent(taskId), failed);
        }
    }
}
