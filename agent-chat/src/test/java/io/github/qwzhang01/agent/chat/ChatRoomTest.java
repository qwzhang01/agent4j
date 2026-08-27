package io.github.qwzhang01.agent.chat;

import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.core.agent.AgentEvent;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRoomTest {

    @Test
    void oneOnOneStreamsWithoutAt() {
        ChatRoom room = ChatRoom.builder()
                .roomId("moonlit:1:luna")
                .persona(ChatPersona.of("luna", "You are Luna."))
                .modelClient(MockModelClient.scripted()
                        .respondText("hey")
                        .respondText("again-hey"))
                .build();

        List<AgentEvent> events = new ArrayList<>();
        room.stream("hi", events::add);

        assertEquals(2, events.size());
        assertEquals(new AgentEvent.ContentDelta("hey"), events.get(0));
        assertInstanceOf(AgentEvent.Done.class, events.get(1));
        assertEquals("again-hey", room.say("again"));
    }

    @Test
    void twoPersonDefaultNeedsMention() {
        ChatRoom room = ChatRoom.builder()
                .roomId("table")
                .persona(ChatPersona.of("luna", "Luna"))
                .persona(ChatPersona.of("bob", "Bob"))
                .modelClient(MockModelClient.scripted().respondText("bob replies"))
                .build();

        assertEquals("", room.say("hello"));
        assertEquals(1, room.room().history().size());
        assertEquals("bob replies", room.say("@bob hello"));
        assertEquals("bob", room.room().history().get(2).speakerId());
    }

    @Test
    void greetingIsDataOnlyAndNotAutoSent() {
        ChatPersona luna = new ChatPersona("luna", "Luna", "You are Luna.", "Nice to meet you.");
        ChatRoom room = ChatRoom.builder()
                .roomId("r")
                .persona(luna)
                .modelClient(MockModelClient.scripted().respondText("later"))
                .build();

        assertTrue(room.room().history().isEmpty());
        assertEquals("Nice to meet you.", room.room().member("luna").orElseThrow().greeting());
        room.say("hi");
        assertEquals("hi", room.room().history().get(0).content());
        assertEquals("later", room.room().history().get(1).content());
    }
}
