package io.github.qwzhang01.agent.trace.export;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.ReActAgentLoop;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.trace.record.RecordingAgent;
import io.github.qwzhang01.agent.trace.record.TrajectoryRecorder;
import io.github.qwzhang01.agent.trace.reward.RuleReward;
import io.github.qwzhang01.agent.trace.sample.SamplingPolicy;
import io.github.qwzhang01.agent.trace.testsupport.RecordingTestSupport;

import java.nio.file.Path;
import java.util.List;

/**
 * NOT a unit test - a dump tool with a main(): runs three scripted agents
 * (two successes, one model failure), records and exports trajectories, then
 * prints the file path plus the Java-side statistics. Run python3
 * examples/scripts/consume_trajectory.py against the same file and the
 * numbers must match (the M14.2 cross-language consumption proof).
 * <p>
 * Run: mvn -pl agent-trace-export test-compile exec:java
 *      -Dexec.mainClass=io.github.qwzhang01.agent.trace.export.TrajectoryDemoDump
 *      -Dexec.classpathScope=test -Dexec.args=/tmp/m142-demo
 */
public final class TrajectoryDemoDump {

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args.length > 0 ? args[0] : "/tmp/m142-demo");

        var recorder = new TrajectoryRecorder();
        var exporter = new TrajectoryExporter(directory, RuleReward.defaults(), SamplingPolicy.all());
        var registry = new InMemoryToolRegistry();
        registry.register(new RecordingTestSupport.FakeTool("echo"));

        // run 1: two tool rounds then final answer (tokens 100/40 + 150/50)
        runOnce("run-001", recorder, exporter, registry, MockModelClient.scripted()
                .respond(new ModelResponse(null, List.of(ToolCall.of("c1", "echo", "{\"input\":\"a\"}")),
                        "tool_calls", new ModelResponse.TokenUsage(100, 40, 140)))
                .respond(new ModelResponse(null, List.of(ToolCall.of("c2", "echo", "{\"input\":\"b\"}")),
                        "tool_calls", new ModelResponse.TokenUsage(150, 50, 200)))
                .respondText("order shipped"));
        // run 2: one tool round then final answer (tokens 80/20)
        runOnce("run-002", recorder, exporter, registry, MockModelClient.scripted()
                .respond(new ModelResponse(null, List.of(ToolCall.of("c1", "echo", "{\"input\":\"x\"}")),
                        "tool_calls", new ModelResponse.TokenUsage(80, 20, 100)))
                .respondText("done"));
        // run 3: model failure (empty script -> ModelException on first call)
        runOnce("run-003", recorder, exporter, registry, MockModelClient.scripted());

        var loaded = exporter.load();
        double avgReward = loaded.stream().mapToDouble(t -> t.reward()).average().orElseThrow();
        System.out.println("==============================================================");
        System.out.println("exported file     : " + exporter.file());
        System.out.println("trajectories      : " + loaded.size());
        System.out.println("avg reward        : " + String.format("%.4f", avgReward));
        System.out.println("model calls total : " + loaded.stream().mapToInt(t -> t.steps().size()).sum());
        System.out.println("echo calls total  : " + loaded.stream()
                .flatMap(t -> t.steps().stream())
                .mapToInt(s -> s.observations().size()).sum());
        System.out.println("prompt tokens     : " + loaded.stream()
                .mapToInt(t -> t.metadata().tokenUsage().promptTokens()).sum());
        System.out.println("completion tokens : " + loaded.stream()
                .mapToInt(t -> t.metadata().tokenUsage().completionTokens()).sum());
        System.out.println("total tokens      : " + loaded.stream()
                .mapToInt(t -> t.metadata().tokenUsage().totalTokens()).sum());
        System.out.println("==============================================================");
    }

    private static void runOnce(String runId, TrajectoryRecorder recorder,
                                TrajectoryExporter exporter,
                                InMemoryToolRegistry registry, MockModelClient mock) {
        var model = io.github.qwzhang01.agent.trace.record.RecordingModelClient.wrap(mock, recorder);
        var executor = io.github.qwzhang01.agent.trace.record.RecordingToolExecutor.wrap(
                new DefaultToolExecutor(registry), recorder);
        Agent agent = RecordingAgent.wrap(new SimpleAgent(
                new AgentConfig("demo-agent", "You are a demo agent.", model, registry, 10, null),
                new ReActAgentLoop(executor)), recorder);
        try {
            agent.run("please check order 8842");
        } catch (RuntimeException expected) {
            // run 3 fails inside the loop (loop catches ModelException itself,
            // so this is just belt-and-braces for the demo)
        }
        try {
            exporter.record(recorder.last().orElseThrow());
        } catch (Exception e) {
            throw new IllegalStateException("export failed for " + runId, e);
        }
    }
}
