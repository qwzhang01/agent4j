package io.github.qwzhang01.agent.chat;

import io.github.qwzhang01.agent.chat.context.ContextSource;
import io.github.qwzhang01.agent.chat.context.HistorySource;
import io.github.qwzhang01.agent.chat.context.MemorySource;
import io.github.qwzhang01.agent.chat.context.PersonaSource;
import io.github.qwzhang01.agent.chat.speaker.MentionSpeaker;
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

/**
 * Character-engine eval scripts (T27). Mock only — inspects the request
 * the model received, not an LLM-as-judge.
 */
class CharacterEvalTest {

    private static final ChatPersona LUNA = ChatPersona.of("luna", "You are Luna.");
    private static final ChatPersona BOB = ChatPersona.of("bob", "You are Bob.");
    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void scriptRecallSubjectAcrossTurns() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        RecordingModelClient model = recording(
                MockModelClient.scripted().respondText("first").respondText("second"));

        ChatRoom room = ChatRoom.builder()
                .roomId("solo-eval")
                .persona(LUNA)
                .scopes("user:u1")
                .source(new PersonaSource())
                .source(new MemorySource(new MemoryRetriever(store), 8))
                .source(new HistorySource())
                .modelClient(model)
                .build();

        room.say("hello");
        assertFalse(joined(model.requests.get(0).messages()).contains("theme:"));

        write(store, "user:u1", "theme", "likes dark mode");
        room.say("what do I like?");

        String second = joined(model.requests.get(1).messages());
        assertTrue(second.contains("[Known memories]"));
        assertTrue(second.contains("theme: likes dark mode"));
        assertTrue(second.contains("what do I like?"));
    }

    @Test
    void scriptGroupDoesNotLeakPairScopes() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        write(store, "user:u1", "shared", "we met at the harbor");
        write(store, "agent:luna:u1", "drink", "luna oat latte");
        write(store, "agent:bob:u1", "drink", "bob black coffee");

        RecordingModelClient model = recording(
                MockModelClient.scripted().respondText("luna-ok").respondText("bob-ok"));

        ChatRoom room = ChatRoom.builder()
                .roomId("group-eval")
                .persona(LUNA)
                .persona(BOB)
                .identity(RoomIdentity.of("user:u1", "channel:g1"))
                .speakerPolicy(new MentionSpeaker())
                .source(new PersonaSource())
                .source(new SpeakerPairMemorySource(new MemoryRetriever(store), "user:u1", "channel:g1"))
                .source(new HistorySource())
                .modelClient(model)
                .build();

        assertEquals(List.of("user:u1", "channel:g1"), room.room().scopes());

        room.say("@luna harbor?");
        String lunaTurn = joined(model.requests.get(0).messages());
        assertTrue(lunaTurn.contains("we met at the harbor"));
        assertTrue(lunaTurn.contains("luna oat latte"));
        assertFalse(lunaTurn.contains("bob black coffee"));
        assertEquals("You are Luna.", model.requests.get(0).messages().get(0).content());

        room.say("@bob harbor?");
        String bobTurn = joined(model.requests.get(1).messages());
        assertTrue(bobTurn.contains("we met at the harbor"));
        assertTrue(bobTurn.contains("bob black coffee"));
        assertFalse(bobTurn.contains("luna oat latte"));
        assertEquals("You are Bob.", model.requests.get(1).messages().get(0).content());
    }

    @Test
    void scriptPersonaIsNotRewritten() {
        String anchor = "You are Luna.";
        ChatPersona persona = ChatPersona.of("luna", anchor);
        RecordingModelClient model = recording(
                MockModelClient.scripted().respondText("one").respondText("two"));

        ChatRoom room = ChatRoom.builder()
                .roomId("solo-eval")
                .persona(persona)
                .source(new PersonaSource())
                .source(new HistorySource())
                .modelClient(model)
                .build();

        room.say("hello");
        room.say("again");

        assertEquals(anchor, room.room().member("luna").orElseThrow().systemPrompt());
        assertEquals(anchor, persona.systemPrompt());
        assertEquals(anchor, model.requests.get(0).messages().get(0).content());
        assertEquals(anchor, model.requests.get(1).messages().get(0).content());
        assertEquals("hello", room.room().history().get(0).content());
        assertEquals("one", room.room().history().get(1).content());
    }

    /**
     * Host-style group recall: shared scopes plus the current speaker's pair.
     * Do not dump every member's pair onto the room identity.
     */
    private static final class SpeakerPairMemorySource implements ContextSource {

        private final MemoryRetriever retriever;
        private final String userScope;
        private final String channelScope;

        private SpeakerPairMemorySource(MemoryRetriever retriever, String userScope, String channelScope) {
            this.retriever = retriever;
            this.userScope = userScope;
            this.channelScope = channelScope;
        }

        @Override
        public List<ChatMessage> contribute(Room room, ChatPersona speaker, String userText) {
            List<String> scopes = List.of(
                    userScope,
                    channelScope,
                    "agent:" + speaker.personaId() + ":u1");
            return new MemorySource(retriever, scopes, 8).contribute(room, speaker, userText);
        }
    }

    private static void write(InMemoryMemoryStore store, String scope, String subject, String content) {
        store.write(new MemoryEntry(
                null, scope, MemoryType.PREFERENCE, subject, content, 0.9,
                MemoryProvenance.userSaid("u1", "r1", T0),
                MemoryStatus.ACTIVE, T0, null));
    }

    private static RecordingModelClient recording(MockModelClient mock) {
        return new RecordingModelClient(mock);
    }

    private static String joined(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : messages) {
            sb.append(message.content()).append('\n');
        }
        return sb.toString();
    }
}
