package io.github.qwzhang01.agent.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatPersonaTest {

    @Test
    void blankIdRejected() {
        assertThrows(IllegalArgumentException.class, () -> ChatPersona.of(" ", "hi"));
        assertThrows(IllegalArgumentException.class, () -> ChatPersona.of(null, "hi"));
    }

    @Test
    void displayNameFallsBackToId() {
        ChatPersona persona = new ChatPersona("luna", "  ", "You are Luna.", null);
        assertEquals("luna", persona.displayName());
        assertEquals("You are Luna.", persona.systemPrompt());
        assertNull(persona.greeting());
    }

    @Test
    void nullPromptBecomesEmpty() {
        ChatPersona persona = ChatPersona.of("a", null);
        assertEquals("", persona.systemPrompt());
    }
}
