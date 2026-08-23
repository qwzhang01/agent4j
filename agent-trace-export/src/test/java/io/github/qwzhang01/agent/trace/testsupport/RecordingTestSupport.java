package io.github.qwzhang01.agent.trace.testsupport;

import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.agent.ContextBuilder;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Test fixtures for Stage 14 M14.1 (kept local so the module needs no extra
 * test dependencies beyond agent-model's MockModelClient).
 */
public final class RecordingTestSupport {

    private RecordingTestSupport() {
    }

    /** Deterministic tool: returns "<name>:<args.input>". */
    public static final class FakeTool implements Tool {
        private final String name;

        public FakeTool(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "test tool " + name;
        }

        @Override
        public String getParametersSchema() {
            return "{\"type\":\"object\"}";
        }

        @Override
        public String execute(JsonNode arguments) {
            return name + ":" + arguments.path("input").asText();
        }
    }

    /** Tool that always throws - for "[ERROR] ..." observation tests (Stage 2 error wrapping). */
    public static final class BombTool implements Tool {
        @Override
        public String getName() {
            return "bomb";
        }

        @Override
        public String getDescription() {
            return "always fails";
        }

        @Override
        public String getParametersSchema() {
            return "{\"type\":\"object\"}";
        }

        @Override
        public String execute(JsonNode arguments) {
            throw new IllegalArgumentException("kaboom");
        }
    }

    /**
     * Read-time trimming ContextBuilder (window semantics): keeps only the
     * LAST n messages of the state - model-seen diverges from state.messages
     * from the second call on. The divergence is what the fidelity test proves.
     */
    public static final class TrimmingContextBuilder implements ContextBuilder {
        private final int keepLast;

        public TrimmingContextBuilder(int keepLast) {
            this.keepLast = keepLast;
        }

        @Override
        public List<ChatMessage> build(AgentConfig config, AgentState state) {
            var all = state.getMessages();
            return new ArrayList<>(all.subList(Math.max(0, all.size() - keepLast), all.size()));
        }
    }

    /**
     * Independent request capturer sitting between the recording decorator and
     * the mock - proves "step.state == what the model actually saw" WITHOUT
     * trusting the recorder under test (non-circular evidence, same手法 as
     * Stage 12/13 RecordingModelClient tests).
     */
    public static final class CapturingModelClient implements ModelClient {
        public final List<List<ChatMessage>> requests = new ArrayList<>();
        private final ModelClient delegate;

        public CapturingModelClient(ModelClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            requests.add(new ArrayList<>(request.messages()));
            return delegate.chat(request);
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            return delegate.stream(request);
        }
    }
}
