package io.github.qwzhang01.agent.examples;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.ReActAgentLoop;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.trace.export.TrajectoryExporter;
import io.github.qwzhang01.agent.trace.record.RecordingAgent;
import io.github.qwzhang01.agent.trace.record.TrajectoryRecorder;
import io.github.qwzhang01.agent.trace.replay.ReplayView;
import io.github.qwzhang01.agent.trace.replay.TrajectoryReplayer;
import io.github.qwzhang01.agent.trace.reward.RuleReward;
import io.github.qwzhang01.agent.trace.sample.SamplingPolicy;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.List;

/**
 * Stage 14 acceptance demo #1: record -> reward -> sample -> export -> replay,
 * the whole left half of the execution-to-training-data loop in one main().
 * <p>
 * Run:
 * <pre>
 * mvn -pl examples exec:java -Dexec.mainClass=io.github.qwzhang01.agent.examples.TrajectoryExample
 * # then consume cross-language:
 * python3 examples/scripts/consume_trajectory.py /tmp/m14-trajectory-demo/trajectories.jsonl
 * </pre>
 */
public final class TrajectoryExample {

    public static void main(String[] args) throws Exception {
        var directory = Path.of(args.length > 0 ? args[0] : "/tmp/m14-trajectory-demo");

        // ---- T0: assembly (recording decorators OUTERMOST, blueprint §2 red line) ----
        var recorder = new TrajectoryRecorder();
        var registry = new InMemoryToolRegistry();
        registry.register(new OrderQueryTool());
        var mock = MockModelClient.scripted()
                .respond(new ModelResponse(null,
                        List.of(ToolCall.of("c1", "order-query", "{\"orderId\":\"8842\"}")),
                        "tool_calls", new ModelResponse.TokenUsage(812, 45, 857)))
                .respondText("订单 8842 已发货，预计明天送达。");
        var model = io.github.qwzhang01.agent.trace.record.RecordingModelClient.wrap(mock, recorder);
        var executor = io.github.qwzhang01.agent.trace.record.RecordingToolExecutor.wrap(
                new DefaultToolExecutor(registry), recorder);
        Agent agent = RecordingAgent.wrap(new SimpleAgent(
                new AgentConfig("support-bot", "你是电商客服助手，需要时调用工具查订单。", model, registry, 10, null),
                new ReActAgentLoop(executor)), recorder);
        var exporter = new TrajectoryExporter(directory, RuleReward.defaults(), SamplingPolicy.all());

        // ---- T1: run - trajectory recorded automatically ----
        String answer = agent.run("帮我查一下订单 8842 到哪了");
        System.out.println("[answer] " + answer);

        Trajectory trajectory = recorder.last().orElseThrow();
        System.out.println("[recorded] steps=" + trajectory.steps().size()
                + "  status=" + trajectory.status()
                + "  messages=" + trajectory.messages().size());

        // ---- T2: score -> sample -> persist ----
        exporter.record(trajectory);
        System.out.println("[exported] " + exporter.file());
        System.out.println("    reward=" + exporter.load().get(0).reward()
                + " (source=" + exporter.load().get(0).rewardSource() + ")");

        // ---- T3: replay walkthrough (walk the recording, never re-run) ----
        ReplayView view = new TrajectoryReplayer().loadFirst(exporter.file());
        System.out.println("[replay] integrity verified, walking " + view.stepCount() + " step(s):");
        for (int i = 0; i < view.stepCount(); i++) {
            System.out.println("    " + view.describeStep(i));
        }

        // ---- peek at the JSONL contract ----
        JsonNode row = new io.github.qwzhang01.agent.trace.export.TrajectoryCodec()
                .toJsonNode(java.nio.file.Files.readString(exporter.file()));
        System.out.println("[contract] api_version=" + row.get("api_version").asText()
                + "  kind=" + row.get("kind").asText()
                + "  top-level fields=" + row.size());

        System.out.println();
        System.out.println("Next: cross-language consumption proof -");
        System.out.println("  python3 examples/scripts/consume_trajectory.py " + exporter.file());
    }

    /** Demo tool: pretends to look up an order. */
    static final class OrderQueryTool implements Tool {
        @Override
        public String getName() {
            return "order-query";
        }

        @Override
        public String getDescription() {
            return "query order status by orderId";
        }

        @Override
        public String getParametersSchema() {
            return "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}},\"required\":[\"orderId\"]}";
        }

        @Override
        public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
            return "{\"orderId\":\"8842\",\"status\":\"shipped\",\"eta\":\"tomorrow\"}";
        }
    }

    private TrajectoryExample() {
    }
}
