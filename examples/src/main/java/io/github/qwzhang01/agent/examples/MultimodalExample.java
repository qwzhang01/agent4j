package io.github.qwzhang01.agent.examples;

import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.ReActAgentLoop;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.client.ImageGenerationClient;
import io.github.qwzhang01.agent.core.client.RetryImageGenerationClient;
import io.github.qwzhang01.agent.core.client.TimeoutImageGenerationClient;
import io.github.qwzhang01.agent.core.client.VideoGenerationClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.model.imagegen.ImageGenerationTool;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.model.videogen.VideoGenerationTool;
import io.github.qwzhang01.agent.model.vision.VisionTool;
import io.github.qwzhang01.agent.scheduler.GenerationTaskCoordinator;
import io.github.qwzhang01.agent.scheduler.TaskScheduler;
import io.github.qwzhang01.agent.scheduler.nodes.WaitEventNode;
import io.github.qwzhang01.agent.security.AuditEvent;
import io.github.qwzhang01.agent.security.ConsoleApprovalService;
import io.github.qwzhang01.agent.security.GovernedToolExecutor;
import io.github.qwzhang01.agent.security.InMemoryAuditLogger;
import io.github.qwzhang01.agent.security.PermissionChecker;
import io.github.qwzhang01.agent.security.SimpleRateLimiter;
import io.github.qwzhang01.agent.security.ToolPermission;
import io.github.qwzhang01.agent.security.ToolPolicy;
import io.github.qwzhang01.agent.workflow.ExecutionResult;
import io.github.qwzhang01.agent.workflow.GraphRuntime;
import io.github.qwzhang01.agent.workflow.Workflow;
import io.github.qwzhang01.agent.workflow.nodes.ActionNode;
import io.github.qwzhang01.agent.workflow.runtime.RunManager;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Multimodal capabilities wired onto Stage 9 governance and Stage 7 long tasks.
 * <p>
 * Demo 1: vision + image gen go through GovernedToolExecutor (approval / rate-limit / audit).
 * Demo 2: video submit is non-blocking; GenerationTaskCoordinator polls and fires
 * {@code video-done:{id}} so the workflow auto-resumes.
 * <p>
 * Run: mvn compile exec:java -pl examples -Dexec.mainClass=io.github.qwzhang01.agent.examples.MultimodalExample
 */
public class MultimodalExample {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Multimodal + Governance + Long Task ===\n");
        demoGovernedVisionAndImage();
        demoVideoLongTask();
        System.out.println("=== Done ===");
    }

    private static void demoGovernedVisionAndImage() {
        System.out.println("─".repeat(60));
        System.out.println("Demo 1: 读图 / 生图 走 Stage 9 治理\n");

        var registry = new InMemoryToolRegistry();
        registry.register(new VisionTool(MockModelClient.scripted().respondText("a red bicycle")));
        ImageGenerationClient images = request -> new ImageGenerationClient.ImageResult(
                List.of(new ImageGenerationClient.GeneratedImage(
                        "https://cdn.example.com/bike.png", null, request.prompt(), "1024x1024")),
                "mock-image");
        registry.register(new ImageGenerationTool(
                new TimeoutImageGenerationClient(new RetryImageGenerationClient(images), Duration.ofSeconds(10))));

        InMemoryAuditLogger audit = new InMemoryAuditLogger();
        ToolPolicy policy = new ToolPolicy(ToolPermission.AUTO).applyGenerationDefaults();
        var executor = GovernedToolExecutor.builder(new DefaultToolExecutor(registry))
                .permissionChecker(new PermissionChecker(policy))
                .approvalService(ConsoleApprovalService.autoApprove())
                .rateLimiter(new SimpleRateLimiter(5))
                .auditLogger(audit)
                .build();

        var loop = new ReActAgentLoop(executor);
        var agent = new SimpleAgent(new AgentConfig("vision-agent",
                "You describe and generate images.",
                MockModelClient.scripted().respondText("Looks like a red bicycle."),
                registry), loop);

        String answer = agent.run(ChatMessage.userWithImage(
                "What is in this picture?", "https://example.com/bike.png"));
        System.out.println("User-initiated vision: " + answer);

        String described = executor.execute(io.github.qwzhang01.agent.core.model.ToolCall.of(
                "c1", "describe_image",
                "{\"question\":\"what is this?\",\"image_url\":\"https://example.com/bike.png\"}"));
        System.out.println("Governed describe_image: " + described);

        String drawn = executor.execute(io.github.qwzhang01.agent.core.model.ToolCall.of(
                "c2", "generate_image", "{\"prompt\":\"a red bicycle\"}"));
        System.out.println("Governed generate_image: " + drawn);

        System.out.println("\nAudit trail:");
        for (AuditEvent event : audit.getAll()) {
            System.out.printf("  [%s] %s%n", event.status(), event.toolName());
        }
        System.out.println();
    }

    private static void demoVideoLongTask() throws Exception {
        System.out.println("─".repeat(60));
        System.out.println("Demo 2: 生视频走 Stage 7 调度（submit → poll → video-done → resume）\n");

        AtomicInteger polls = new AtomicInteger();
        VideoGenerationClient videos = new VideoGenerationClient() {
            @Override
            public VideoTask submit(VideoGenRequest request) {
                return new VideoTask("vid-42", VideoTask.STATUS_QUEUED, null, null, 0, null);
            }

            @Override
            public VideoTask status(String taskId) {
                if (polls.getAndIncrement() == 0) {
                    return new VideoTask(taskId, VideoTask.STATUS_RUNNING, null, null, 50, null);
                }
                return new VideoTask(taskId, VideoTask.STATUS_SUCCEEDED,
                        "https://cdn.example.com/clip.mp4", null, 100, null);
            }
        };

        RunManager mgr = new RunManager();
        TaskScheduler scheduler = new TaskScheduler(mgr);
        mgr.setRuntime(new GraphRuntime().scheduler(scheduler));
        scheduler.start();

        var coordinator = new GenerationTaskCoordinator(
                scheduler, videos, Duration.ofMillis(80), Duration.ofSeconds(5));
        var videoTool = new VideoGenerationTool(videos, coordinator.asListener());

        Workflow wf = Workflow.builder("video-long-task")
                .node(ActionNode.of("submit", ctx -> {
                    var args = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                            .put("prompt", "a bicycle riding through rain");
                    String result = videoTool.execute(args);
                    ctx.state().put("eventKey", GenerationTaskCoordinator.videoDoneEvent("vid-42"));
                    return result;
                }))
                .node(WaitEventNode.fromState("wait-video", "eventKey"))
                .node(ActionNode.of("done", ctx -> {
                    if (ctx.input() instanceof VideoGenerationClient.VideoTask task) {
                        return "ready: " + task.videoUrl();
                    }
                    return String.valueOf(ctx.input());
                }))
                .edge(Workflow.START, "submit")
                .edge("submit", "wait-video")
                .edge("wait-video", "done")
                .edge("done", Workflow.END)
                .build();

        ExecutionResult paused = mgr.start(wf, "make a clip");
        System.out.println("After submit: " + paused.status() + " (waiting for video-done:vid-42)");

        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            var run = mgr.getRun(paused.resumeToken().runId());
            if (run != null && run.getStatus().isTerminal()) {
                System.out.println("Final: " + run.getStatus()
                        + " url=" + coordinator.getCompleted("vid-42").videoUrl());
                scheduler.shutdown();
                System.out.println();
                return;
            }
            Thread.sleep(40);
        }
        scheduler.shutdown();
        throw new IllegalStateException("video long-task demo did not finish");
    }
}
