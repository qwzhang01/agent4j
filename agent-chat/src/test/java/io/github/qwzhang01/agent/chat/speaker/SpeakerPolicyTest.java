package io.github.qwzhang01.agent.chat.speaker;

import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.model.Room;
import io.github.qwzhang01.agent.chat.model.RoomMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeakerPolicyTest {

    private static final ChatPersona A = ChatPersona.of("alice", "A");
    private static final ChatPersona B = ChatPersona.of("bob", "B");

    @Test
    void soloPicksTheOnlyMemberWithoutMention() {
        Room room = new Room("solo", List.of(A));
        assertEquals("alice", new SoloSpeaker().pick(room, "hi").orElseThrow().personaId());
    }

    @Test
    void soloRejectsGroupRooms() {
        Room room = new Room("group", List.of(A, B));
        assertThrows(IllegalStateException.class, () -> new SoloSpeaker().pick(room, "@alice"));
    }

    @Test
    void mentionPicksFirstMatchingId() {
        Room room = new Room("group", List.of(A, B));
        assertEquals("bob", new MentionSpeaker().pick(room, "hey @bob and @alice").orElseThrow().personaId());
    }

    @Test
    void mentionIgnoresUnknownAtThenHitsKnown() {
        Room room = new Room("group", List.of(A, B));
        assertEquals("alice", new MentionSpeaker().pick(room, "@ghost then @alice").orElseThrow().personaId());
    }

    @Test
    void mentionWithoutHitAndWithoutFallbackIsEmpty() {
        Room room = new Room("group", List.of(A, B));
        assertTrue(new MentionSpeaker().pick(room, "hello everyone").isEmpty());
    }

    @Test
    void mentionFallsBackWhenConfigured() {
        Room room = new Room("solo", List.of(A));
        SpeakerPolicy policy = new MentionSpeaker(new SoloSpeaker());
        assertEquals("alice", policy.pick(room, "no mention").orElseThrow().personaId());
    }

    @Test
    void roundRobinStartsWithFirstMember() {
        Room room = new Room("group", List.of(A, B));
        assertEquals("alice", new RoundRobinSpeaker().pick(room, "hi").orElseThrow().personaId());
    }

    @Test
    void roundRobinRotatesThroughMembers() {
        Room room = new Room("group", List.of(A, B));
        RoundRobinSpeaker policy = new RoundRobinSpeaker();

        assertEquals("alice", policy.pick(room, "one").orElseThrow().personaId());
        room.append(RoomMessage.user("one"));
        room.append(RoomMessage.assistant("alice", "a1"));

        assertEquals("bob", policy.pick(room, "two").orElseThrow().personaId());
        room.append(RoomMessage.user("two"));
        room.append(RoomMessage.assistant("bob", "b1"));

        assertEquals("alice", policy.pick(room, "three").orElseThrow().personaId());
    }

    @Test
    void roundRobinWrapsThreeMemberRoom() {
        ChatPersona c = ChatPersona.of("carol", "C");
        Room room = new Room("group", List.of(A, B, c));
        RoundRobinSpeaker policy = new RoundRobinSpeaker();

        assertEquals("alice", policy.pick(room, "t1").orElseThrow().personaId());
        room.append(RoomMessage.user("t1"));
        room.append(RoomMessage.assistant("alice", "a"));
        assertEquals("bob", policy.pick(room, "t2").orElseThrow().personaId());
        room.append(RoomMessage.user("t2"));
        room.append(RoomMessage.assistant("bob", "b"));
        assertEquals("carol", policy.pick(room, "t3").orElseThrow().personaId());
        room.append(RoomMessage.user("t3"));
        room.append(RoomMessage.assistant("carol", "c"));
        assertEquals("alice", policy.pick(room, "t4").orElseThrow().personaId());
    }

    @Test
    void roundRobinSoloRoomAlwaysPicksTheMember() {
        Room room = new Room("solo", List.of(A));
        assertEquals("alice", new RoundRobinSpeaker().pick(room, "hi").orElseThrow().personaId());
        room.append(RoomMessage.user("hi"));
        room.append(RoomMessage.assistant("alice", "hey"));
        assertEquals("alice", new RoundRobinSpeaker().pick(room, "again").orElseThrow().personaId());
    }

    @Test
    void mentionCanFallBackToRoundRobin() {
        Room room = new Room("group", List.of(A, B));
        SpeakerPolicy policy = new MentionSpeaker(new RoundRobinSpeaker());

        assertEquals("alice", policy.pick(room, "hello").orElseThrow().personaId());
        room.append(RoomMessage.user("hello"));
        room.append(RoomMessage.assistant("alice", "a"));

        assertEquals("bob", policy.pick(room, "next").orElseThrow().personaId());
        assertEquals("bob", policy.pick(room, "@bob ping").orElseThrow().personaId());
    }
}
