package io.github.qwzhang01.agent.chat.speaker;

import io.github.qwzhang01.agent.chat.RecordingModelClient;
import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.model.Room;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectorBeatPolicyTest {

    private static final ChatPersona ALICE = ChatPersona.of("alice", "You are Alice.");
    private static final ChatPersona BOB = ChatPersona.of("bob", "You are Bob.");
    private static final String INSTRUCTIONS =
            "Pick the member who naturally speaks next, or reply STOP. Id only.";

    @Test
    void picksNextSpeaker() {
        RecordingModelClient director = recording(
                MockModelClient.scripted().respondText("bob"));
        Room room = new Room("g", List.of(ALICE, BOB));
        DirectorBeatPolicy policy = new DirectorBeatPolicy(director, INSTRUCTIONS);

        assertEquals("bob", policy.nextBeat(room, ALICE, "hi").orElseThrow().personaId());
    }

    @Test
    void stopLiteralEndsTurn() {
        RecordingModelClient director = recording(
                MockModelClient.scripted().respondText("STOP"));
        Room room = new Room("g", List.of(ALICE, BOB));
        DirectorBeatPolicy policy = new DirectorBeatPolicy(director, INSTRUCTIONS);

        assertTrue(policy.nextBeat(room, ALICE, "hi").isEmpty());
    }

    @Test
    void lastSpeakerIsNeverRePicked() {
        RecordingModelClient director = recording(
                MockModelClient.scripted().respondText("alice"));
        Room room = new Room("g", List.of(ALICE, BOB));
        DirectorBeatPolicy policy = new DirectorBeatPolicy(director, INSTRUCTIONS);

        assertTrue(policy.nextBeat(room, ALICE, "hi").isEmpty());
    }

    @Test
    void directorFailureStopsTurn() {
        RecordingModelClient director = recording(MockModelClient.scripted());
        Room room = new Room("g", List.of(ALICE, BOB));
        DirectorBeatPolicy policy = new DirectorBeatPolicy(director, INSTRUCTIONS);

        assertTrue(policy.nextBeat(room, ALICE, "hi").isEmpty());
    }

    @Test
    void soloRoomNeverContinues() {
        RecordingModelClient director = recording(
                MockModelClient.scripted().respondText("alice"));
        Room room = new Room("solo", List.of(ALICE));
        DirectorBeatPolicy policy = new DirectorBeatPolicy(director, INSTRUCTIONS);

        assertTrue(policy.nextBeat(room, ALICE, "hi").isEmpty());
        assertTrue(director.requests.isEmpty());
    }

    @Test
    void requestCarriesRosterAndTranscript() {
        RecordingModelClient director = recording(
                MockModelClient.scripted().respondText("bob"));
        Room room = new Room("g", List.of(ALICE, BOB));
        room.append(io.github.qwzhang01.agent.chat.model.RoomMessage.user("hello"));
        room.append(io.github.qwzhang01.agent.chat.model.RoomMessage.assistant("alice", "hi there"));
        DirectorBeatPolicy policy = new DirectorBeatPolicy(director, INSTRUCTIONS);

        policy.nextBeat(room, ALICE, "hi there");

        String system = director.requests.get(0).messages().get(0).content();
        assertTrue(system.contains("- alice [just spoke]"));
        assertTrue(system.contains("- bob"));
        String user = director.requests.get(0).messages().get(1).content();
        assertTrue(user.contains("alice: hi there"));
    }

    @Test
    void blankInstructionsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new DirectorBeatPolicy(MockModelClient.scripted(), "  "));
    }

    private static RecordingModelClient recording(MockModelClient mock) {
        return new RecordingModelClient(mock);
    }
}
