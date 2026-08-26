package io.github.qwzhang01.agent.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomTest {

    @Test
    void needsIdAndAtLeastOnePersona() {
        ChatPersona luna = ChatPersona.of("luna", "hi");
        assertThrows(IllegalArgumentException.class, () -> new Room(" ", List.of(luna)));
        assertThrows(IllegalArgumentException.class, () -> new Room("r", List.of()));
    }

    @Test
    void duplicatePersonaRejected() {
        ChatPersona a = ChatPersona.of("luna", "a");
        ChatPersona b = new ChatPersona("luna", "Luna", "b", null);
        assertThrows(IllegalArgumentException.class, () -> new Room("r", List.of(a, b)));
    }

    @Test
    void appendKeepsOrder() {
        Room room = new Room("r", List.of(ChatPersona.of("luna", "hi")));
        room.append(RoomMessage.user("hello"));
        room.append(RoomMessage.assistant("luna", "hey"));
        assertEquals(2, room.history().size());
        assertEquals("hello", room.history().get(0).content());
        assertTrue(room.member("luna").isPresent());
        assertTrue(room.identity().isEmpty());
    }

    @Test
    void identityHoldsOpaqueScopes() {
        RoomIdentity identity = RoomIdentity.of("user:1:7", "agent:42:7", "user:1:7", " ", null);
        Room room = new Room("r", List.of(ChatPersona.of("luna", "hi")), identity);
        assertEquals(List.of("user:1:7", "agent:42:7"), room.scopes());
        assertEquals(room.identity().scopes(), room.scopes());
    }
}
