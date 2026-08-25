package io.github.qwzhang01.agent.chat.context;

import io.github.qwzhang01.agent.chat.ChatPersona;
import io.github.qwzhang01.agent.chat.Room;
import io.github.qwzhang01.agent.chat.RoomMessage;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextSourceTest {

    private final ChatPersona luna = ChatPersona.of("luna", "You are Luna.");

    @Test
    void personaIsVerbatimSystem() {
        Room room = new Room("r", List.of(luna));
        List<ChatMessage> messages = new PersonaSource().contribute(room, luna, "hi");
        assertEquals(1, messages.size());
        assertEquals(ChatRole.SYSTEM, messages.get(0).role());
        assertEquals("You are Luna.", messages.get(0).content());
    }

    @Test
    void blankPersonaContributesNothing() {
        ChatPersona empty = ChatPersona.of("x", "  ");
        Room room = new Room("r", List.of(empty));
        assertTrue(new PersonaSource().contribute(room, empty, "hi").isEmpty());
    }

    @Test
    void extraIsSystemAfterPersonaWhenAssembledInOrder() {
        Room room = new Room("r", List.of(luna));
        ContextAssembler assembler = new ContextAssembler(List.of(
                new PersonaSource(),
                new ExtraTextSource("rainy cafe")));
        List<ChatMessage> messages = assembler.assemble(room, luna, "hi");
        assertEquals(2, messages.size());
        assertEquals("You are Luna.", messages.get(0).content());
        assertEquals("rainy cafe", messages.get(1).content());
        assertEquals(ChatRole.SYSTEM, messages.get(1).role());
    }

    @Test
    void historyOmitsTheJustAppendedUserLine() {
        Room room = new Room("r", List.of(luna));
        room.append(RoomMessage.user("first"));
        room.append(RoomMessage.assistant("luna", "ok"));
        room.append(RoomMessage.user("second"));

        List<ChatMessage> messages = new HistorySource().contribute(room, luna, "second");
        assertEquals(2, messages.size());
        assertEquals("first", messages.get(0).content());
        assertEquals(ChatRole.USER, messages.get(0).role());
        assertEquals("ok", messages.get(1).content());
        assertEquals(ChatRole.ASSISTANT, messages.get(1).role());
    }

    @Test
    void historyRespectsWindow() {
        Room room = new Room("r", List.of(luna));
        room.append(RoomMessage.user("old"));
        room.append(RoomMessage.assistant("luna", "old-reply"));
        room.append(RoomMessage.user("keep"));
        room.append(RoomMessage.assistant("luna", "keep-reply"));
        room.append(RoomMessage.user("now"));

        List<ChatMessage> messages = new HistorySource(2).contribute(room, luna, "now");
        assertEquals(2, messages.size());
        assertEquals("keep", messages.get(0).content());
        assertEquals("keep-reply", messages.get(1).content());
    }

    @Test
    void historyLimitMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new HistorySource(0));
    }
}
