package io.github.qwzhang01.agent.chat;

import io.github.qwzhang01.agent.chat.context.PersonaSource;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonaRendererTest {

    @Test
    void nullRendererUsesSystemPromptAttributeAsIs() {
        PersonaSpec spec = PersonaSpec.of("luna", "You are Luna.");
        ChatPersona persona = ChatPersona.render(spec, null);
        assertEquals("luna", persona.personaId());
        assertEquals("You are Luna.", persona.systemPrompt());
        List<ChatMessage> injected = new PersonaSource().contribute(
                new Room("r", List.of(persona)), persona, "hi");
        assertEquals("You are Luna.", injected.get(0).content());
    }

    @Test
    void rendererOwnsAttributeVocabulary() {
        PersonaRenderer renderer = spec -> spec.attribute("vibe") + " / " + spec.attribute("job");
        PersonaSpec spec = new PersonaSpec("42", "Luna", Map.of(
                "vibe", "quiet",
                "job", "barista",
                PersonaSpec.GREETING, "今晚有空吗"));
        ChatPersona persona = ChatPersona.render(spec, renderer);
        assertEquals("quiet / barista", persona.systemPrompt());
        assertEquals("Luna", persona.displayName());
        assertEquals("今晚有空吗", persona.greeting());
        assertFalse(persona.systemPrompt().contains("{{"));
    }

    @Test
    void engineDoesNotRequirePlaceholders() {
        PersonaRenderer renderer = spec -> spec.promptOrEmpty().replace("{{unused}}", "gone");
        PersonaSpec spec = new PersonaSpec("x", "x", Map.of(PersonaSpec.SYSTEM_PROMPT, "plain"));
        assertEquals("plain", renderer.render(spec));
        assertTrue(ChatPersona.render(spec, renderer).systemPrompt().contains("plain"));
    }

    @Test
    void blankSpecIdRejected() {
        assertThrows(IllegalArgumentException.class, () -> PersonaSpec.of(" ", "hi"));
        assertThrows(IllegalArgumentException.class, () -> ChatPersona.render(null, spec -> "x"));
    }

    @Test
    void nullRendererPromptFromRendererNullBecomesEmpty() {
        ChatPersona persona = ChatPersona.render(PersonaSpec.of("a", null), spec -> null);
        assertEquals("", persona.systemPrompt());
    }
}
