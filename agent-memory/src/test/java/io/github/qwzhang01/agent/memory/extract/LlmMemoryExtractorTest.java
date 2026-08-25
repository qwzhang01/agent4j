package io.github.qwzhang01.agent.memory.extract;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.client.ModelException;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryExtractor;
import io.github.qwzhang01.agent.memory.MemoryPolicy;
import io.github.qwzhang01.agent.memory.MemoryProvenance;
import io.github.qwzhang01.agent.memory.MemoryQuery;
import io.github.qwzhang01.agent.memory.MemoryType;
import io.github.qwzhang01.agent.memory.store.InMemoryMemoryStore;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmMemoryExtractorTest {

    private static final MemoryProvenance PROV =
            MemoryProvenance.modelDerived("mock", "run-1", Instant.parse("2026-08-25T10:00:00Z"));

    @Test
    void extractsArbitrarySubjectAndStoresIt() {
        MockModelClient model = MockModelClient.scripted().respondText("""
                {"memories":[{"type":"EVENT","subject":"xyz-widget-9","content":"user mentioned widget 9","importance":0.8}]}
                """);
        MemoryExtractor extractor = new LlmMemoryExtractor(model);
        InMemoryMemoryStore store = new InMemoryMemoryStore();

        int stored = extractor.extractAndStore(
                List.of(ChatMessage.user("random chatter about widget 9")),
                "user:u1", PROV, new MemoryPolicy(0.5), store);

        assertEquals(1, stored);
        List<MemoryEntry> hits = store.query(MemoryQuery.builder()
                .scopes(List.of("user:u1"))
                .subject("xyz-widget-9")
                .build());
        assertEquals(1, hits.size());
        assertEquals(MemoryType.EVENT, hits.get(0).type());
        assertEquals("user mentioned widget 9", hits.get(0).content());
        assertEquals(0.8, hits.get(0).importance());
    }

    @Test
    void hostInstructionsAreSentToTheModel() {
        RecordingClient model = new RecordingClient(
                ModelResponse.text("{\"memories\":[]}"));
        LlmMemoryExtractor extractor = new LlmMemoryExtractor(model, "Look for color preferences only.");

        extractor.extract(List.of(ChatMessage.user("I like teal")), "user:u1", PROV);

        String system = model.lastRequest.messages().get(0).content();
        assertTrue(system.contains("Look for color preferences only."));
        assertTrue(model.lastRequest.messages().get(1).content().contains("I like teal"));
    }

    @Test
    void blankInstructionsFallBackToDefault() {
        LlmMemoryExtractor extractor = new LlmMemoryExtractor(
                MockModelClient.scripted().respondText("{\"memories\":[]}"), "  ");
        assertEquals(LlmMemoryExtractor.DEFAULT_INSTRUCTIONS, extractor.instructions());
    }

    @Test
    void unknownTypeBecomesFact_subjectUntouched() {
        List<MemoryEntry> entries = LlmMemoryExtractor.parseMemories(
                """
                        {"memories":[{"type":"NOT_A_TYPE","subject":"fav-color","content":"teal"}]}
                        """,
                "user:u1", PROV);
        assertEquals(1, entries.size());
        assertEquals(MemoryType.FACT, entries.get(0).type());
        assertEquals("fav-color", entries.get(0).subject());
    }

    @Test
    void markdownFenceAndRootArrayAreAccepted() {
        List<MemoryEntry> entries = LlmMemoryExtractor.parseMemories(
                """
                        ```json
                        [{"type":"PREFERENCE","subject":"k","content":"v"}]
                        ```
                        """,
                "user:u1", PROV);
        assertEquals(1, entries.size());
        assertEquals("k", entries.get(0).subject());
    }

    @Test
    void invalidJsonOrModelFailureYieldsNothing() {
        MemoryExtractor badJson = new LlmMemoryExtractor(
                MockModelClient.scripted().respondText("not json"));
        assertTrue(badJson.extract(List.of(ChatMessage.user("hi")), "user:u1", PROV).isEmpty());

        MemoryExtractor boom = new LlmMemoryExtractor(new ModelClient() {
            @Override
            public ModelResponse chat(ModelRequest request) {
                throw new ModelException(ModelException.ErrorCode.MODEL_ERROR, "down");
            }

            @Override
            public Stream<StreamEvent> stream(ModelRequest request) {
                return Stream.empty();
            }
        });
        assertTrue(boom.extract(List.of(ChatMessage.user("hi")), "user:u1", PROV).isEmpty());
    }

    @Test
    void blankContentIsSkipped() {
        List<MemoryEntry> entries = LlmMemoryExtractor.parseMemories(
                "{\"memories\":[{\"type\":\"FACT\",\"subject\":\"x\",\"content\":\"  \"}]}",
                "user:u1", PROV);
        assertTrue(entries.isEmpty());
    }

    private static final class RecordingClient implements ModelClient {
        private final ModelResponse response;
        ModelRequest lastRequest;

        RecordingClient(ModelResponse response) {
            this.response = response;
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            lastRequest = request;
            return response;
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            return Stream.empty();
        }
    }
}
