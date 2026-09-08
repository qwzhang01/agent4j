package io.github.qwzhang01.agent.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Instructions belong to active config; history and checkpoints never own them. */
class SystemPromptIsolationTest {

    @Test
    void syncAndStreamInjectOnceWithoutPersistingPersona() {
        for (boolean streaming : List.of(false, true)) {
            var model = new CapturingModel(false);
            var state = new AgentState();
            var agent = new SimpleAgent(config("persona", model, null));
            if (streaming) {
                agent.stream("hello", state, event -> {});
            } else {
                agent.run("hello", state);
            }
            assertEquals(List.of(ChatMessage.system("persona"), ChatMessage.user("hello")),
                    model.requests.get(0));
            assertHistoryOnly(state);
            assertEquals(2, state.getMessages().size());
            assertEquals(AgentState.Status.DONE, state.getStatus());
        }
    }

    @Test
    void sharedHistoryUsesNewConfigWithoutOldPersonaInEitherMode() {
        for (boolean streaming : List.of(false, true)) {
            var model = new CapturingModel(false);
            var state = new AgentState();
            var first = new SimpleAgent(config("persona A", model, null));
            var second = new SimpleAgent(config("persona B", model, null));
            if (streaming) {
                first.stream("one", state, event -> {});
                second.stream("two", state, event -> {});
            } else {
                first.run("one", state);
                second.run("two", state);
            }
            assertEquals("persona A", model.requests.get(0).get(0).content());
            assertEquals(List.of(ChatMessage.system("persona B"), ChatMessage.user("one"),
                    ChatMessage.assistant("ok"), ChatMessage.user("two")), model.requests.get(1));
            assertHistoryOnly(state);
            assertEquals(4, state.getMessages().size());
            assertEquals(2, state.getCurrentStep(), "switching entry config must not reset consumed steps");
        }
    }

    @Test
    void nullEmptyAndWhitespacePromptsAreNotInjected() {
        for (String prompt : new String[]{null, "", " \n\t"}) {
            var model = new CapturingModel(false);
            var state = new AgentState("old question");
            new SimpleAgent(config(prompt, model, null)).run("hello", state);
            assertEquals(List.of(ChatMessage.user("old question"), ChatMessage.user("hello")),
                    model.requests.get(0));
            assertHistoryOnly(state);
        }
    }

    @Test
    void immutableAndLiveBuilderResultsAreNeverPrependedInPlace() {
        List<ContextBuilder> builders = List.of(
                (config, state) -> List.copyOf(state.getMessages()),
                (config, state) -> state.getMessages());
        for (var builder : builders) {
            var model = new CapturingModel(false);
            var state = new AgentState();
            new SimpleAgent(config("persona", model, builder)).run("hello", state);
            assertEquals(List.of(ChatMessage.system("persona"), ChatMessage.user("hello")),
                    model.requests.get(0));
            assertHistoryOnly(state);
            assertEquals(2, state.getMessages().size());
        }
    }

    @Test
    void everyToolRoundHasExactlyOnePersonaInBothModes() {
        for (boolean streaming : List.of(false, true)) {
            var model = new CapturingModel(true);
            var registry = new InMemoryToolRegistry();
            registry.register(new SimpleAgentTest.EchoToolInline());
            var state = new AgentState();
            var agent = new SimpleAgent(new AgentConfig("test", "persona", model, registry, 5));
            if (streaming) {
                agent.stream("echo", state, event -> {});
            } else {
                agent.run("echo", state);
            }
            assertEquals(2, model.requests.size());
            for (var request : model.requests) {
                assertEquals(ChatMessage.system("persona"), request.get(0));
                assertEquals(1, request.stream().filter(m -> m.role() == ChatRole.SYSTEM).count());
            }
            assertEquals(List.of(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.ASSISTANT),
                    state.getMessages().stream().map(ChatMessage::role).toList());
        }
    }

    @Test
    void checkpointAndSnapshotContainHistoryButNoPersona() throws Exception {
        var state = new AgentState();
        new SimpleAgent(config("private persona", new CapturingModel(false), null)).run("hello", state);
        var mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(state);
        assertFalse(json.contains("private persona"));
        var restored = mapper.readValue(json, AgentState.class);
        assertEquals(state.getMessages(), restored.getMessages());
        assertHistoryOnly(restored);
        assertHistoryOnly(state.snapshot());
    }

    @Test
    void legacyCheckpointRequiresExactExplicitMigrationBeforeNewConfigRuns() throws Exception {
        var mapper = new ObjectMapper();
        var state = mapper.readValue("""
                {"messages":[{"role":"SYSTEM","content":"old persona"},
                  {"role":"USER","content":"original question"}],"currentStep":2,"maxSteps":8}
                """, AgentState.class);
        var original = List.copyOf(state.getMessages());
        assertThrows(IllegalArgumentException.class, () -> state.migrateLegacySystemPrompt("new persona"));
        assertEquals(original, state.getMessages(), "failed migration must not discard data");
        state.migrateLegacySystemPrompt("old persona");
        assertEquals(2, state.getCurrentStep());
        assertEquals(8, state.getMaxSteps());
        var model = new CapturingModel(false);
        new ReActAgentLoop(new InMemoryToolRegistry()).execute(config("new persona", model, null), state);
        assertEquals(List.of(ChatMessage.system("new persona"), ChatMessage.user("original question")),
                model.requests.get(0));
        assertHistoryOnly(state);
    }

    @Test
    void unmigratedSystemFailsBeforeTrimmingAndBeforeModelCall() {
        var state = new AgentState();
        state.addMessage(ChatMessage.system("unknown old instructions"));
        state.addMessage(ChatMessage.user("hello"));
        var before = List.copyOf(state.getMessages());
        var model = new CapturingModel(false);
        ContextBuilder trimming = (config, s) -> List.of(ChatMessage.user("trimmed"));
        var error = assertThrows(IllegalArgumentException.class,
                () -> new ReActAgentLoop(new InMemoryToolRegistry())
                        .execute(config("new persona", model, trimming), state));
        assertTrue(error.getMessage().contains("migrateLegacySystemPrompt"));
        assertTrue(model.requests.isEmpty());
        assertEquals(before, state.getMessages());
    }

    @Test
    void builderCannotPersistSystemAndMigrationDoesNotEraseOtherSystemEvents() {
        var state = new AgentState();
        state.addMessage(ChatMessage.system("legacy persona"));
        state.addMessage(ChatMessage.user("hello"));
        state.addMessage(ChatMessage.system("legacy handoff event"));
        state.migrateLegacySystemPrompt("legacy persona");
        assertEquals(ChatMessage.system("legacy handoff event"), state.getMessages().get(1));
        var model = new CapturingModel(false);
        var config = config("persona", model, (c, s) -> {
            s.addMessage(ChatMessage.system("rogue"));
            return List.of(ChatMessage.user("filtered"));
        });
        assertThrows(IllegalArgumentException.class,
                () -> new SimpleAgent(config).run("hello"));
        assertTrue(model.requests.isEmpty());
    }

    @Test
    void transientSystemContextIsAllowedButNeverPersisted() {
        var model = new CapturingModel(false);
        var state = new AgentState();
        ContextBuilder builder = (config, current) -> {
            var context = new ArrayList<ChatMessage>();
            context.add(ChatMessage.system("host guidance"));
            context.addAll(current.getMessages());
            return List.copyOf(context);
        };
        new SimpleAgent(config("persona", model, builder)).run("hello", state);
        assertEquals(List.of(ChatMessage.system("persona"), ChatMessage.system("host guidance"),
                ChatMessage.user("hello")), model.requests.get(0));
        assertHistoryOnly(state);
    }

    private static AgentConfig config(String prompt, ModelClient model, ContextBuilder builder) {
        return new AgentConfig("test", prompt, model, null, 5, builder);
    }

    private static void assertHistoryOnly(AgentState state) {
        assertTrue(state.getMessages().stream().noneMatch(m -> m.role() == ChatRole.SYSTEM));
    }

    private static class CapturingModel implements ModelClient {
        private final List<List<ChatMessage>> requests = new ArrayList<>();
        private final boolean useTool;

        private CapturingModel(boolean useTool) {
            this.useTool = useTool;
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            requests.add(List.copyOf(request.messages()));
            if (useTool && requests.size() == 1) {
                return ModelResponse.toolCalls(List.of(ToolCall.of("call1", "echo", "{}")));
            }
            return ModelResponse.text("ok");
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            return Stream.of(new StreamEvent.Done(chat(request)));
        }
    }
}
