package io.github.qwzhang01.agent.scheduler;

import io.github.qwzhang01.agent.core.client.VideoGenerationClient;
import io.github.qwzhang01.agent.scheduler.nodes.WaitEventNode;
import io.github.qwzhang01.agent.workflow.ExecutionResult;
import io.github.qwzhang01.agent.workflow.GraphRuntime;
import io.github.qwzhang01.agent.workflow.Workflow;
import io.github.qwzhang01.agent.workflow.nodes.ActionNode;
import io.github.qwzhang01.agent.workflow.runtime.RunManager;
import io.github.qwzhang01.agent.workflow.runtime.RunState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class GenerationTaskCoordinatorTest {

    private RunManager runManager;
    private TaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        runManager = new RunManager();
        scheduler = new TaskScheduler(runManager);
        runManager.setRuntime(new GraphRuntime().scheduler(scheduler));
        scheduler.start();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    @Test
    void pollFiresVideoDoneAndResumesWorkflow() {
        AtomicInteger statusCalls = new AtomicInteger();
        VideoGenerationClient client = new VideoGenerationClient() {
            @Override
            public VideoTask submit(VideoGenRequest request) {
                return new VideoTask("vid-1", VideoTask.STATUS_QUEUED, null, null, null, null);
            }

            @Override
            public VideoTask status(String taskId) {
                if (statusCalls.getAndIncrement() == 0) {
                    return new VideoTask("vid-1", VideoTask.STATUS_RUNNING, null, null, 40, null);
                }
                return new VideoTask("vid-1", VideoTask.STATUS_SUCCEEDED,
                        "https://cdn.example.com/done.mp4", null, 100, null);
            }
        };

        var coordinator = new GenerationTaskCoordinator(
                scheduler, client, Duration.ofMillis(30), Duration.ofSeconds(5));

        Workflow wf = Workflow.builder("video-flow")
                .node(ActionNode.of("submit", ctx -> {
                    VideoGenerationClient.VideoTask task = client.submit(
                            VideoGenerationClient.VideoGenRequest.builder().prompt("a cat").build());
                    ctx.state().put("eventKey", GenerationTaskCoordinator.videoDoneEvent(task.id()));
                    coordinator.trackVideo(task.id());
                    return task.id();
                }))
                .node(WaitEventNode.fromState("wait-video", "eventKey"))
                .node(ActionNode.of("done", ctx -> {
                    Object payload = ctx.input();
                    if (payload instanceof VideoGenerationClient.VideoTask task) {
                        return task.videoUrl();
                    }
                    return String.valueOf(payload);
                }))
                .edge(Workflow.START, "submit")
                .edge("submit", "wait-video")
                .edge("wait-video", "done")
                .edge("done", Workflow.END)
                .build();

        ExecutionResult r1 = runManager.start(wf, "input");
        assertTrue(r1.isPaused(), "workflow must pause while the video is generating");

        awaitRunStatus(runManager, r1.resumeToken().runId(), RunState.SUCCEEDED, 3000);

        var completed = coordinator.getCompleted("vid-1");
        assertEquals("https://cdn.example.com/done.mp4", completed.videoUrl());
        assertTrue(statusCalls.get() >= 2);
        assertEquals(1, scheduler.getTaskQueue().totalEnqueued());
    }

    private static void awaitRunStatus(RunManager mgr, String runId, RunState expected, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            var run = mgr.getRun(runId);
            if (run != null && run.getStatus() == expected) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted waiting for " + expected);
            }
        }
        var run = mgr.getRun(runId);
        fail("timed out waiting for " + expected + ", last=" + (run == null ? "null" : run.getStatus()));
    }
}
