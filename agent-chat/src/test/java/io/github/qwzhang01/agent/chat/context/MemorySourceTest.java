package io.github.qwzhang01.agent.chat.context;

import io.github.qwzhang01.agent.chat.ChatPersona;
import io.github.qwzhang01.agent.chat.ChatRoom;
import io.github.qwzhang01.agent.chat.RecordingModelClient;
import io.github.qwzhang01.agent.chat.Room;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryProvenance;
import io.github.qwzhang01.agent.memory.MemoryRetriever;
import io.github.qwzhang01.agent.memory.MemoryStatus;
import io.github.qwzhang01.agent.memory.MemoryType;
import io.github.qwzhang01.agent.memory.store.InMemoryMemoryStore;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemorySourceTest {

    private static final ChatPersona LUNA = ChatPersona.of("luna", "You are Luna.");
    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void emptyStoreContributesNothing() {
        MemorySource source = new MemorySource(
                new MemoryRetriever(new InMemoryMemoryStore()), List.of("user:u1"));
        assertTrue(source.contribute(new Room("r", List.of(LUNA)), LUNA, "hi").isEmpty());
    }

    @Test
    void emptyScopesContributeNothing() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        write(store, "user:u1", "theme", "likes dark mode", 0.9);
        MemorySource source = new MemorySource(new MemoryRetriever(store), List.of());
        assertTrue(source.contribute(new Room("r", List.of(LUNA)), LUNA, "hi").isEmpty());
    }

    @Test
    void recallIsScopedAndRendered() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        write(store, "user:u1", "theme", "likes dark mode", 0.9);
        write(store, "user:u2", "other", "someone else", 1.0);

        MemorySource source = new MemorySource(
                new MemoryRetriever(store), List.of("user:u1"), 8);
        List<ChatMessage> chunk = source.contribute(new Room("r", List.of(LUNA)), LUNA, "hi");

        assertEquals(1, chunk.size());
        String text = chunk.get(0).content();
        assertTrue(text.startsWith("[Known memories]\n"));
        assertTrue(text.contains("theme: likes dark mode"));
        assertFalse(text.contains("someone else"));
    }

    @Test
    void limitKeepsHigherImportance() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        write(store, "user:u1", "low", "low importance", 0.2);
        write(store, "user:u1", "high", "high importance", 0.95);

        MemorySource source = new MemorySource(
                new MemoryRetriever(store), List.of("user:u1"), 1);
        String text = source.contribute(new Room("r", List.of(LUNA)), LUNA, "hi").get(0).content();
        assertTrue(text.contains("high importance"));
        assertFalse(text.contains("low importance"));
    }

    @Test
    void secondTurnRequestContainsRecalledText() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        MemoryRetriever retriever = new MemoryRetriever(store);
        RecordingModelClient model = new RecordingModelClient(
                MockModelClient.scripted().respondText("first").respondText("second"));

        ChatRoom room = ChatRoom.builder()
                .roomId("solo")
                .persona(LUNA)
                .source(new PersonaSource())
                .source(new MemorySource(retriever, List.of("user:u1"), 8))
                .source(new HistorySource())
                .modelClient(model)
                .build();

        room.say("hello");
        assertFalse(joined(model.requests.get(0).messages()).contains("[Known memories]"));

        write(store, "user:u1", "theme", "likes dark mode", 0.9);
        room.say("what do I like?");

        String second = joined(model.requests.get(1).messages());
        assertTrue(second.contains("[Known memories]"));
        assertTrue(second.contains("likes dark mode"));
        assertTrue(second.contains("hello"));
        assertTrue(second.contains("what do I like?"));
    }

    @Test
    void chatRoomDoesNotMountMemorySourceByDefault() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        write(store, "user:u1", "theme", "likes dark mode", 0.9);
        RecordingModelClient model = new RecordingModelClient(
                MockModelClient.scripted().respondText("a").respondText("b"));

        ChatRoom room = ChatRoom.builder()
                .roomId("solo")
                .persona(LUNA)
                .modelClient(model)
                .build();
        room.say("hello");
        room.say("again");

        assertFalse(joined(model.requests.get(0).messages()).contains("likes dark mode"));
        assertFalse(joined(model.requests.get(1).messages()).contains("likes dark mode"));
    }

    private static void write(InMemoryMemoryStore store, String scope, String subject,
                              String content, double importance) {
        store.write(new MemoryEntry(
                null, scope, MemoryType.PREFERENCE, subject, content, importance,
                MemoryProvenance.userSaid("u1", "r1", T0),
                MemoryStatus.ACTIVE, T0, null));
    }

    private static String joined(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : messages) {
            sb.append(message.content()).append('\n');
        }
        return sb.toString();
    }
}
