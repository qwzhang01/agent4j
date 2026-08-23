package io.github.qwzhang01.agent.examples;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.ReActAgentLoop;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.trace.feedback.ConsoleAnnotator;
import io.github.qwzhang01.agent.trace.feedback.DpoExporter;
import io.github.qwzhang01.agent.trace.feedback.PreferencePair;
import io.github.qwzhang01.agent.trace.feedback.TrajectoryPairBuilder;
import io.github.qwzhang01.agent.trace.record.RecordingAgent;
import io.github.qwzhang01.agent.trace.record.TrajectoryRecorder;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Stage 14 acceptance demo #2: same-prompt double rollout -> console
 * preference annotation -> DPO-format export (the rejection-sampling
 * RLHF/DPO data route).
 * <p>
 * Demo mode pre-selects "a" via injected input; point the ConsoleAnnotator at
 * System.in (no-arg constructor) for real interactive labeling.
 * <p>
 * Run:
 * <pre>
 * mvn -pl examples exec:java -Dexec.mainClass=io.github.qwzhang01.agent.examples.PreferenceAnnotationExample
 * </pre>
 */
public final class PreferenceAnnotationExample {

    public static void main(String[] args) throws Exception {
        var directory = Path.of(args.length > 0 ? args[0] : "/tmp/m14-preference-demo");
        java.nio.file.Files.createDirectories(directory);
        String prompt = "帮我查一下订单 8842 到哪了";

        // ---- rollout A (good): uses the tool, answers helpfully ----
        Trajectory good = runRollout(prompt, MockModelClient.scripted()
                .respond(new ModelResponse(null,
                        List.of(ToolCall.of("c1", "order-query", "{\"orderId\":\"8842\"}")),
                        "tool_calls", new ModelResponse.TokenUsage(812, 45, 857)))
                .respondText("订单 8842 已发货，预计明天送达。"));

        // ---- rollout B (bad): refuses without checking ----
        Trajectory bad = runRollout(prompt, MockModelClient.scripted()
                .respondText("我不知道，你自己看吧。"));

        System.out.println("[rollouts] A=" + good.trajectoryId() + " (" + good.steps().size()
                + " steps)  B=" + bad.trajectoryId() + " (" + bad.steps().size() + " steps)");
        TrajectoryPairBuilder.requireSharedPrompt(good, bad);
        System.out.println("[pairing] shared prompt prefix verified (2 messages)");

        // ---- annotate (demo: pre-selected 'a'; use new ConsoleAnnotator(sidecar) for interactive) ----
        var sidecar = directory.resolve("annotations.jsonl");
        var annotator = new ConsoleAnnotator(sidecar,
                new BufferedReader(new StringReader("a\n")), System.out);
        System.out.println("[annotating] (demo mode: input pre-set to 'a')");
        Optional<PreferencePair> pair = annotator.annotate(good, bad);
        if (pair.isEmpty()) {
            System.out.println("[annotating] skipped - nothing exported");
            return;
        }
        System.out.println("[annotated] preferred=" + pair.orElseThrow().preferred()
                + "  sidecar=" + sidecar);

        // ---- materialize DPO pairs ----
        var dpoFile = directory.resolve("preferences.jsonl");
        new DpoExporter(dpoFile).export(List.of(pair.orElseThrow()), List.of(good, bad));
        System.out.println("[dpo] exported " + dpoFile);
        System.out.println("---- preferences.jsonl ----");
        System.out.print(java.nio.file.Files.readString(dpoFile));
        System.out.println("--------------------------");
        System.out.println("DPO row shape: prompt (shared, 2 msgs) | chosen (good rollout's 3-msg response)"
                + " | rejected (bad rollout's 1-msg response)");
    }

    private static Trajectory runRollout(String prompt, MockModelClient mock) {
        var recorder = new TrajectoryRecorder();
        var registry = new InMemoryToolRegistry();
        registry.register(new TrajectoryExample.OrderQueryTool());
        var model = io.github.qwzhang01.agent.trace.record.RecordingModelClient.wrap(mock, recorder);
        var executor = io.github.qwzhang01.agent.trace.record.RecordingToolExecutor.wrap(
                new DefaultToolExecutor(registry), recorder);
        Agent agent = RecordingAgent.wrap(new SimpleAgent(
                new AgentConfig("support-bot", "你是电商客服助手，需要时调用工具查订单。", model, registry, 10, null),
                new ReActAgentLoop(executor)), recorder);
        agent.run(prompt);
        return recorder.last().orElseThrow();
    }

    private PreferenceAnnotationExample() {
    }
}
