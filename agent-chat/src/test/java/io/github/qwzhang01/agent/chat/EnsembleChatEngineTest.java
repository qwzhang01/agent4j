package io.github.qwzhang01.agent.chat;

import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.model.Room;
import io.github.qwzhang01.agent.chat.speaker.BeatPolicy;
import io.github.qwzhang01.agent.chat.speaker.SoloSpeaker;
import io.github.qwzhang01.agent.core.agent.AgentEvent;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnsembleChatEngineTest {

    private static final ChatPersona ALICE = ChatPersona.of("alice", "You are Alice.");
    private static final ChatPersona BOB = ChatPersona.of("bob", "You are Bob.");

    @Test
    void oneUserLineProducesTwoBeats() {
        // chat model: beat1 Alice replies, beat2 Bob replies
        MockModelClient chat = MockModelClient.scripted()
                .respondText("from alice")
                .respondText("from bob");
        // beat policy: after alice, bob speaks; after bob, stop
        BeatPolicy beat = (room, lastSpeaker, lastReply) ->
                "alice".equals(lastSpeaker.personaId())
                        ? room.member("bob")
                        : Optional.empty();

        RecordingEvents events = new RecordingEvents();
        ensemble(beat, chat).stream("hello", events);

        assertEquals(1, events.beatStarted);
        assertEquals(1, events.done);
        assertEquals("from bob", events.lastDoneAnswer);
    }

    @Test
    void beatPolicyStopYieldsSingleReply() {
        MockModelClient chat = MockModelClient.scripted().respondText("from alice");
        BeatPolicy stop = (room, lastSpeaker, lastReply) -> Optional.empty();

        RecordingEvents events = new RecordingEvents();
        ensemble(stop, chat).stream("hello", events);

        assertEquals(0, events.beatStarted);
        assertEquals(1, events.done);
        assertEquals("from alice", events.lastDoneAnswer);
    }

    @Test
    void selfFollowupIsRejected() {
        MockModelClient chat = MockModelClient.scripted().respondText("from alice");
        // policy insists on re-picking the same speaker — engine must stop
        BeatPolicy same = (room, lastSpeaker, lastReply) -> Optional.of(lastSpeaker);

        RecordingEvents events = new RecordingEvents();
        ensemble(same, chat).stream("hello", events);

        assertEquals(0, events.beatStarted);
        assertEquals(1, events.done);
    }

    @Test
    void beatFailureStopsTurn() {
        // beat 1 ok; beat 2 model fails (no scripted response left)
        MockModelClient chat = MockModelClient.scripted()
                .respondText("from alice");
        BeatPolicy next = (room, lastSpeaker, lastReply) -> room.member("bob");

        RecordingEvents events = new RecordingEvents();
        ensemble(next, chat).stream("hello", events);

        assertEquals(1, events.beatStarted);
        assertEquals(1, events.done);
        assertEquals("from alice", events.lastDoneAnswer);
    }

    @Test
    void noFirstSpeakerMeansNoEnsemble() {
        MockModelClient chat = MockModelClient.scripted().respondText("x");
        BeatPolicy next = (room, lastSpeaker, lastReply) -> room.member("bob");
        RecordingEvents events = new RecordingEvents();

        ChatEngine engine = ChatEngine.builder()
                .room(new Room("g", List.of(ALICE, BOB)))
                .speakerPolicy((r, t) -> Optional.empty())
                .modelClient(chat)
                .build();
        new EnsembleChatEngine(engine, next).stream("hello", events);

        assertEquals(0, events.done);
    }

    @Test
    void beatsAppendToRoomHistory() {
        MockModelClient chat = MockModelClient.scripted()
                .respondText("from alice")
                .respondText("from bob");
        BeatPolicy beat = (room, lastSpeaker, lastReply) ->
                "alice".equals(lastSpeaker.personaId())
                        ? room.member("bob")
                        : Optional.empty();

        Room room = new Room("g", List.of(ALICE, BOB));
        ChatEngine engine = ChatEngine.builder()
                .room(room)
                .speakerPolicy((r, t) -> r.member("alice"))
                .modelClient(chat)
                .build();

        new EnsembleChatEngine(engine, beat).say("hello");

        assertEquals(3, room.history().size());  // user + alice + bob
        assertEquals("bob", room.history().get(2).speakerId());
    }

    private static EnsembleChatEngine ensemble(BeatPolicy beat, MockModelClient chat) {
        ChatEngine engine = ChatEngine.builder()
                .room(new Room("g", List.of(ALICE, BOB)))
                .speakerPolicy((r, t) -> r.member("alice"))
                .modelClient(chat)
                .build();
        return new EnsembleChatEngine(engine, beat);
    }

    private static final class RecordingEvents implements java.util.function.Consumer<AgentEvent> {
        int beatStarted;
        int done;
        String lastDoneAnswer;

        @Override
        public void accept(AgentEvent event) {
            if (event instanceof AgentEvent.BeatStarted b) {
                beatStarted++;
            } else if (event instanceof AgentEvent.Done d) {
                done++;
                lastDoneAnswer = d.finalAnswer();
            }
        }
    }
}
