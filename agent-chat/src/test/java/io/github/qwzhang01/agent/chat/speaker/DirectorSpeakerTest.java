package io.github.qwzhang01.agent.chat.speaker;

import io.github.qwzhang01.agent.chat.ChatEngine;
import io.github.qwzhang01.agent.chat.ChatPersona;
import io.github.qwzhang01.agent.chat.ChatRoom;
import io.github.qwzhang01.agent.chat.RecordingModelClient;
import io.github.qwzhang01.agent.chat.Room;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectorSpeakerTest {

    private static final ChatPersona ALICE = ChatPersona.of("alice", "You are Alice.");
    private static final ChatPersona BOB = ChatPersona.of("bob", "You are Bob.");
    private static final String INSTRUCTIONS =
            "Pick exactly one member id who should answer. Reply with only the id.";

    @Test
    void soloRoomSkipsDirectorCall() {
        RecordingModelClient director = recording(MockModelClient.scripted().respondText("ghost"));
        Room room = new Room("solo", List.of(ALICE));
        DirectorSpeaker policy = new DirectorSpeaker(director, INSTRUCTIONS);

        assertEquals("alice", policy.pick(room, "hi").orElseThrow().personaId());
        assertTrue(director.requests.isEmpty());
    }

    @Test
    void directorPicksMatchingMemberId() {
        RecordingModelClient director = recording(MockModelClient.scripted().respondText("bob"));
        Room room = new Room("group", List.of(ALICE, BOB));
        DirectorSpeaker policy = new DirectorSpeaker(director, INSTRUCTIONS);

        assertEquals("bob", policy.pick(room, "who knows math?").orElseThrow().personaId());
        assertTrue(systemText(director).startsWith(INSTRUCTIONS));
        assertTrue(systemText(director).contains("- alice"));
        assertTrue(systemText(director).contains("- bob"));
        assertEquals("who knows math?", userText(director));
    }

    @Test
    void parserAcceptsEmbeddedId() {
        RecordingModelClient director = recording(
                MockModelClient.scripted().respondText("Speaker: alice"));
        Room room = new Room("group", List.of(ALICE, BOB));
        DirectorSpeaker policy = new DirectorSpeaker(director, INSTRUCTIONS);

        assertEquals("alice", policy.pick(room, "hi").orElseThrow().personaId());
    }

    @Test
    void unknownChoiceUsesFallback() {
        RecordingModelClient director = recording(MockModelClient.scripted().respondText("ghost"));
        Room room = new Room("group", List.of(ALICE, BOB));
        DirectorSpeaker policy = new DirectorSpeaker(
                director, INSTRUCTIONS, new RoundRobinSpeaker());

        assertEquals("alice", policy.pick(room, "hello").orElseThrow().personaId());
    }

    @Test
    void directorFailureUsesFallback() {
        RecordingModelClient director = recording(MockModelClient.scripted());
        Room room = new Room("group", List.of(ALICE, BOB));
        DirectorSpeaker policy = new DirectorSpeaker(
                director, INSTRUCTIONS, new RoundRobinSpeaker());

        assertEquals("alice", policy.pick(room, "hello").orElseThrow().personaId());
    }

    @Test
    void noFallbackWhenDirectorMisses() {
        RecordingModelClient director = recording(MockModelClient.scripted().respondText("ghost"));
        Room room = new Room("group", List.of(ALICE, BOB));
        DirectorSpeaker policy = new DirectorSpeaker(director, INSTRUCTIONS);

        assertTrue(policy.pick(room, "hello").isEmpty());
    }

    @Test
    void customParserWins() {
        RecordingModelClient director = recording(MockModelClient.scripted().respondText("pick-bob"));
        DirectorChoiceParser parser = (text, room) ->
                text.contains("bob") ? Optional.of("bob") : Optional.empty();
        Room room = new Room("group", List.of(ALICE, BOB));
        DirectorSpeaker policy = new DirectorSpeaker(director, INSTRUCTIONS, null, parser);

        assertEquals("bob", policy.pick(room, "hi").orElseThrow().personaId());
    }

    @Test
    void blankInstructionsRejected() {
        RecordingModelClient director = recording(MockModelClient.scripted().respondText("alice"));
        assertThrows(IllegalArgumentException.class,
                () -> new DirectorSpeaker(director, "  "));
    }

    @Test
    void mentionCanFallBackToDirector() {
        RecordingModelClient director = recording(MockModelClient.scripted().respondText("bob"));
        RecordingModelClient chat = recording(
                MockModelClient.scripted().respondText("bob here"));
        Room room = new Room("group", List.of(ALICE, BOB));
        SpeakerPolicy policy = new MentionSpeaker(new DirectorSpeaker(director, INSTRUCTIONS));

        ChatRoom chatRoom = ChatRoom.builder()
                .roomId("group")
                .personas(room.members())
                .speakerPolicy(policy)
                .modelClient(chat)
                .build();

        assertEquals("alice", policy.pick(room, "@alice ping").orElseThrow().personaId());
        assertEquals("bob here", chatRoom.say("who should answer?"));
        assertEquals("bob", chatRoom.room().history().get(1).speakerId());
        assertEquals(1, director.requests.size());
        assertEquals(1, chat.requests.size());
    }

    @Test
    void chatEngineUsesDirectorPickForReply() {
        RecordingModelClient director = recording(MockModelClient.scripted().respondText("bob"));
        RecordingModelClient chat = recording(
                MockModelClient.scripted().respondText("from bob"));
        ChatEngine engine = ChatEngine.builder()
                .room(new Room("group", List.of(ALICE, BOB)))
                .speakerPolicy(new DirectorSpeaker(director, INSTRUCTIONS))
                .modelClient(chat)
                .build();

        assertEquals("from bob", engine.say("question"));
        assertEquals("bob", engine.room().history().get(1).speakerId());
        assertEquals("You are Bob.", chat.requests.get(0).messages().get(0).content());
    }

    private static RecordingModelClient recording(MockModelClient mock) {
        return new RecordingModelClient(mock);
    }

    private static String systemText(RecordingModelClient director) {
        return director.requests.get(0).messages().stream()
                .filter(message -> message.role() == ChatRole.SYSTEM)
                .map(ChatMessage::content)
                .findFirst()
                .orElse("");
    }

    private static String userText(RecordingModelClient director) {
        return director.requests.get(0).messages().stream()
                .filter(message -> message.role() == ChatRole.USER)
                .map(ChatMessage::content)
                .findFirst()
                .orElse("");
    }
}
