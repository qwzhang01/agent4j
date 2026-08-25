package io.github.qwzhang01.agent.chat.speaker;

import io.github.qwzhang01.agent.chat.ChatPersona;
import io.github.qwzhang01.agent.chat.Room;
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
}
