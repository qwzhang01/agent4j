package io.github.qwzhang01.agent.chat;

import io.github.qwzhang01.agent.chat.context.ExtraTextSource;
import io.github.qwzhang01.agent.chat.context.HistorySource;
import io.github.qwzhang01.agent.chat.context.MemorySource;
import io.github.qwzhang01.agent.chat.context.PersonaSource;
import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.model.PersonaSpec;
import io.github.qwzhang01.agent.core.agent.AgentEvent;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryProvenance;
import io.github.qwzhang01.agent.memory.MemoryRetriever;
import io.github.qwzhang01.agent.memory.MemoryStatus;
import io.github.qwzhang01.agent.memory.MemoryType;
import io.github.qwzhang01.agent.memory.store.InMemoryMemoryStore;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that ChatRoom / ChatEngine emits a {@link AgentEvent.TurnTrace}
 * immediately before every {@link AgentEvent.Done} event.
 */
class TurnTraceTest {

    private static final ChatPersona LUNA = ChatPersona.of("luna", "You are Luna.");
    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

    // Ordering

    @Test
    void turnTrace_isEmittedBeforeDone() {
        ChatRoom room = ChatRoom.builder()
                .roomId("r")
                .persona(LUNA)
                .modelClient(MockModelClient.scripted().respondText("reply"))
                .build();

        List<AgentEvent> events = new ArrayList<>();
        room.stream("hi", events::add);

        int traceIdx = indexOfFirst(events, AgentEvent.TurnTrace.class);
        int doneIdx = indexOfFirst(events, AgentEvent.Done.class);

        assertTrue(traceIdx >= 0, "TurnTrace must be emitted");
        assertTrue(doneIdx >= 0, "Done must be emitted");
        assertTrue(traceIdx < doneIdx, "TurnTrace must precede Done");
    }

    @Test
    void turnTrace_isEmittedAfterContentDeltas() {
        ChatRoom room = ChatRoom.builder()
                .roomId("r")
                .persona(LUNA)
                .modelClient(MockModelClient.scripted().respondText("hello"))
                .build();

        List<AgentEvent> events = new ArrayList<>();
        room.stream("hi", events::add);

        int deltaIdx = indexOfFirst(events, AgentEvent.ContentDelta.class);
        int traceIdx = indexOfFirst(events, AgentEvent.TurnTrace.class);

        assertTrue(deltaIdx >= 0, "ContentDelta must be emitted");
        assertTrue(deltaIdx < traceIdx, "ContentDelta must precede TurnTrace");
    }

    // recalledSubjects

    @Test
    void withMemorySource_recalledSubjectsMatchInjected() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        write(store, "user:u1", "food", "likes sushi", 0.9);
        write(store, "user:u1", "drink", "prefers tea", 0.8);

        ChatRoom room = ChatRoom.builder()
                .roomId("r")
                .persona(LUNA)
                .source(new PersonaSource())
                .source(new MemorySource(new MemoryRetriever(store), List.of("user:u1"), 5))
                .source(new HistorySource())
                .modelClient(MockModelClient.scripted().respondText("noted"))
                .build();

        AgentEvent.TurnTrace trace = captureTrace(room, "what do I like?");

        assertNotNull(trace);
        assertTrue(trace.recalledSubjects().contains("food"),
                "recalled subjects should contain 'food'; got: " + trace.recalledSubjects());
        assertTrue(trace.recalledSubjects().contains("drink"),
                "recalled subjects should contain 'drink'; got: " + trace.recalledSubjects());
    }

    @Test
    void withoutMemorySource_recalledSubjectsIsEmpty() {
        ChatRoom room = ChatRoom.builder()
                .roomId("r")
                .persona(LUNA)
                .modelClient(MockModelClient.scripted().respondText("ok"))
                .build();

        AgentEvent.TurnTrace trace = captureTrace(room, "hi");

        assertNotNull(trace);
        assertTrue(trace.recalledSubjects().isEmpty(),
                "no MemorySource → recalledSubjects must be empty");
    }

    // extraTextBytes

    @Test
    void withExtraTextSource_extraBytesReflectsActualOutput() {
        String extra = "rainy café";  // multi-byte CJK test with accent
        ChatRoom room = ChatRoom.builder()
                .roomId("r")
                .persona(LUNA)
                .source(new PersonaSource())
                .source(new ExtraTextSource(extra))
                .source(new HistorySource())
                .modelClient(MockModelClient.scripted().respondText("ok"))
                .build();

        AgentEvent.TurnTrace trace = captureTrace(room, "hi");

        assertNotNull(trace);
        int expected = extra.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertEquals(expected, trace.extraTextBytes(),
                "extraTextBytes must equal UTF-8 byte count of the ExtraText contribution");
    }

    @Test
    void withoutExtraTextSource_extraBytesIsZero() {
        ChatRoom room = ChatRoom.builder()
                .roomId("r")
                .persona(LUNA)
                .modelClient(MockModelClient.scripted().respondText("ok"))
                .build();

        AgentEvent.TurnTrace trace = captureTrace(room, "hi");

        assertNotNull(trace);
        assertEquals(0, trace.extraTextBytes(), "no ExtraTextSource → extraTextBytes must be 0");
    }

    // personaVersion

    @Test
    void withoutVersion_personaVersionIsNull() {
        ChatRoom room = ChatRoom.builder()
                .roomId("r")
                .persona(LUNA)  // created via ChatPersona.of() -> version = null
                .modelClient(MockModelClient.scripted().respondText("ok"))
                .build();

        AgentEvent.TurnTrace trace = captureTrace(room, "hi");
        assertNotNull(trace);
        assertNull(trace.personaVersion(), "personaVersion must be null when spec has no version");
    }

    @Test
    void withVersion_personaVersionPropagatesToTrace() {
        PersonaSpec spec = PersonaSpec.of("luna", "v2.1.0", "You are Luna.");
        ChatPersona versioned = ChatPersona.render(spec, null);

        ChatRoom room = ChatRoom.builder()
                .roomId("r")
                .persona(versioned)
                .modelClient(MockModelClient.scripted().respondText("ok"))
                .build();

        AgentEvent.TurnTrace trace = captureTrace(room, "hi");
        assertNotNull(trace);
        assertEquals("v2.1.0", trace.personaVersion(),
                "personaVersion must carry through spec → ChatPersona → TurnTrace");
    }

    // timing & token approximation

    @Test
    void latencyMs_isNonNegative() {
        ChatRoom room = ChatRoom.builder()
                .roomId("r")
                .persona(LUNA)
                .modelClient(MockModelClient.scripted().respondText("ok"))
                .build();

        AgentEvent.TurnTrace trace = captureTrace(room, "hi");
        assertNotNull(trace);
        assertTrue(trace.latencyMs() >= 0, "latencyMs must be >= 0");
    }

    @Test
    void promptAndCompletionTokens_arePositive_whenContentExists() {
        ChatRoom room = ChatRoom.builder()
                .roomId("r")
                .persona(LUNA)
                .modelClient(MockModelClient.scripted().respondText("a long reply text"))
                .build();

        AgentEvent.TurnTrace trace = captureTrace(room, "hello");
        assertNotNull(trace);
        assertTrue(trace.promptTokens() > 0,
                "promptTokens (char count) must be > 0 when a persona system prompt is set");
        assertTrue(trace.completionTokens() > 0,
                "completionTokens (char count) must be > 0 when reply is non-empty");
    }

    private static AgentEvent.TurnTrace captureTrace(ChatRoom room, String userText) {
        List<AgentEvent> events = new ArrayList<>();
        room.stream(userText, events::add);
        return (AgentEvent.TurnTrace) events.stream()
                .filter(e -> e instanceof AgentEvent.TurnTrace)
                .findFirst()
                .orElse(null);
    }

    private static <T extends AgentEvent> int indexOfFirst(List<AgentEvent> events,
                                                            Class<T> type) {
        for (int i = 0; i < events.size(); i++) {
            if (type.isInstance(events.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static void write(InMemoryMemoryStore store, String scope, String subject,
                               String content, double importance) {
        store.write(new MemoryEntry(
                null, scope, MemoryType.PREFERENCE, subject, content, importance,
                MemoryProvenance.userSaid("u1", "r1", T0),
                MemoryStatus.ACTIVE, T0, null));
    }
}
